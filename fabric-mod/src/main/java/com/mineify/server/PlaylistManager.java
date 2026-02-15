package com.mineify.server;

import com.mineify.Mineify;
import com.mineify.network.packets.NowPlayingPacket;
import com.mineify.network.packets.PlayAudioPacket;
import com.mineify.network.packets.PlaylistSyncPacket;
import com.mineify.network.packets.SearchResultsPacket;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class PlaylistManager {
    private final MinecraftServer server;
    private final CompanionClient companionClient;
    private final List<PlaylistSyncPacket.Entry> playlist = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Mineify-Scheduler");
        t.setDaemon(true);
        return t;
    });

    private int currentIndex = -1;
    private boolean isPlaying = false;
    private long playbackStartTime = 0;
    private long currentTrackDurationMs = 0;
    private String currentDownloadUrl = null;
    private ScheduledFuture<?> advanceFuture;
    private ScheduledFuture<?> progressFuture;

    public PlaylistManager(MinecraftServer server, CompanionClient companionClient) {
        this.server = server;
        this.companionClient = companionClient;

        // Broadcast progress every second
        this.progressFuture = scheduler.scheduleAtFixedRate(() -> {
            if (isPlaying && currentIndex >= 0 && currentIndex < playlist.size()) {
                PlaylistSyncPacket.Entry entry = playlist.get(currentIndex);
                long elapsed = Math.max(0, System.currentTimeMillis() - playbackStartTime);
                float progress = currentTrackDurationMs > 0 ? (float) elapsed / currentTrackDurationMs : 0f;
                server.execute(() -> broadcastNowPlaying(entry.title(), Math.min(progress, 1f)));
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    public void handleSearch(ServerPlayerEntity player, String query) {
        Mineify.LOGGER.info("Player {} searching for: {}", player.getName().getString(), query);

        companionClient.search(query).thenAccept(results -> {
            server.execute(() -> {
                List<SearchResultsPacket.Entry> entries = new ArrayList<>();
                for (var r : results) {
                    entries.add(new SearchResultsPacket.Entry(
                            r.videoId(), r.title(), r.channel(), r.duration(), r.thumbnail()
                    ));
                }
                ServerPlayNetworking.send(player, new SearchResultsPacket(entries));
            });
        });
    }

    public void handleAddToPlaylist(ServerPlayerEntity player, String videoId, String title, String duration) {
        Mineify.LOGGER.info("Player {} adding to playlist: {}", player.getName().getString(), title);

        PlaylistSyncPacket.Entry entry = new PlaylistSyncPacket.Entry(
                videoId, title, duration, player.getName().getString()
        );
        playlist.add(entry);
        syncToAll();

        if (!isPlaying) {
            playNext();
        }
    }

    public void handleRemoveFromPlaylist(ServerPlayerEntity player, String videoId) {
        String playerName = player.getName().getString();

        // Find the index of the entry to remove (only if player is the owner)
        int removeIndex = -1;
        for (int i = 0; i < playlist.size(); i++) {
            PlaylistSyncPacket.Entry entry = playlist.get(i);
            if (entry.videoId().equals(videoId) && entry.addedBy().equals(playerName)) {
                removeIndex = i;
                break;
            }
        }

        if (removeIndex == -1) {
            Mineify.LOGGER.warn("{} tried to remove {} but doesn't own it or it doesn't exist", playerName, videoId);
            return;
        }

        playlist.remove(removeIndex);
        Mineify.LOGGER.info("{} removed {} from playlist", playerName, videoId);

        // Delete the downloaded file from companion service
        companionClient.deleteDownload(videoId);

        // Handle index adjustments when removing songs
        if (currentIndex >= 0) {
            if (removeIndex < currentIndex) {
                // Removed a song before the current one - adjust index
                currentIndex--;
            } else if (removeIndex == currentIndex) {
                // Removed the currently playing song - cancel current playback and play next
                if (advanceFuture != null) {
                    advanceFuture.cancel(false);
                }
                currentIndex--; // playNext will increment it
                playNext();
            }
            // If removeIndex > currentIndex, no adjustment needed
        }

        syncToAll();
    }

    public void syncToPlayer(ServerPlayerEntity player) {
        ServerPlayNetworking.send(player, new PlaylistSyncPacket(new ArrayList<>(playlist)));
        if (isPlaying && currentIndex >= 0 && currentIndex < playlist.size()) {
            PlaylistSyncPacket.Entry entry = playlist.get(currentIndex);
            long elapsed = System.currentTimeMillis() - playbackStartTime;
            float progress = currentTrackDurationMs > 0 ? (float) elapsed / currentTrackDurationMs : 0f;
            ServerPlayNetworking.send(player, new NowPlayingPacket(entry.title(), Math.min(progress, 1f)));

            // Send audio to late-joining player
            if (currentDownloadUrl != null) {
                ServerPlayNetworking.send(player, new PlayAudioPacket(currentDownloadUrl, entry.title(), entry.videoId(), playbackStartTime));
            }
        }
    }

    private void playNext() {
        // Delete the previous song's download if there was one
        if (currentIndex >= 0 && currentIndex < playlist.size()) {
            String previousVideoId = playlist.get(currentIndex).videoId();
            companionClient.deleteDownload(previousVideoId);
        }

        currentIndex++;
        if (currentIndex >= playlist.size()) {
            isPlaying = false;
            currentIndex = -1;
            currentDownloadUrl = null;
            broadcastNowPlaying("", 0f);
            return;
        }

        PlaylistSyncPacket.Entry entry = playlist.get(currentIndex);
        isPlaying = true;
        currentTrackDurationMs = parseDuration(entry.duration());

        Mineify.LOGGER.info("Requesting download for: {} ({})", entry.title(), entry.videoId());

        companionClient.requestDownload(entry.videoId()).thenAccept(downloadUrl -> {
            if (downloadUrl == null) {
                Mineify.LOGGER.error("Download failed for: {}", entry.title());
                server.execute(this::playNext);
                return;
            }

            server.execute(() -> {
                currentDownloadUrl = downloadUrl;
                
                // Shared start time: gives clients time to download/decode before "go"
                final long LEAD_MS = 3000; // tweak 1500–5000
                playbackStartTime = System.currentTimeMillis() + LEAD_MS;
                
                Mineify.LOGGER.info("Broadcasting audio to all players: {}", entry.title());
                PlayAudioPacket packet = new PlayAudioPacket(downloadUrl, entry.title(), entry.videoId(), playbackStartTime);
                for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                    ServerPlayNetworking.send(player, packet);
                }
                broadcastNowPlaying(entry.title(), 0f);
                
                // Schedule advance to next track based on the real (shared) start time
                if (currentTrackDurationMs > 0) {
                    if (advanceFuture != null) {
                        advanceFuture.cancel(false);
                    }
                    long msUntilEnd = (playbackStartTime - System.currentTimeMillis()) + currentTrackDurationMs + 2000;
                    advanceFuture = scheduler.schedule(
                            () -> server.execute(this::playNext),
                            Math.max(msUntilEnd, 0),
                            TimeUnit.MILLISECONDS
                    );
                }
            });
        });
    }

    private void syncToAll() {
        PlaylistSyncPacket packet = new PlaylistSyncPacket(new ArrayList<>(playlist));
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ServerPlayNetworking.send(player, packet);
        }
    }

    private void broadcastNowPlaying(String title, float progress) {
        NowPlayingPacket packet = new NowPlayingPacket(title, progress);
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ServerPlayNetworking.send(player, packet);
        }
    }

    /**
     * Parse duration string like "3:45" or "1:02:30" to milliseconds.
     */
    private long parseDuration(String duration) {
        if (duration == null || duration.isEmpty()) {
            return 3 * 60 * 1000; // default 3 minutes
        }
        try {
            String[] parts = duration.split(":");
            long seconds = 0;
            for (String part : parts) {
                seconds = seconds * 60 + Long.parseLong(part.trim());
            }
            return seconds * 1000;
        } catch (NumberFormatException e) {
            return 3 * 60 * 1000; // default 3 minutes
        }
    }

    public void shutdown() {
        if (advanceFuture != null) advanceFuture.cancel(true);
        if (progressFuture != null) progressFuture.cancel(true);
        scheduler.shutdownNow();
        playlist.clear();
        Mineify.LOGGER.info("Mineify: Playlist manager shut down");
    }
}
