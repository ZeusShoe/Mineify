package com.mineify.server;

import com.mineify.Mineify;
import com.mineify.network.packets.NowPlayingPacket;
import com.mineify.network.packets.PlayAudioPacket;
import com.mineify.network.packets.PlaybackStatePacket;
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
    private boolean paused = false;
    private long pausedElapsedMs = 0;
    private long playbackStartNanos = 0;
    private long currentTrackDurationMs = 0;
    private String currentDownloadUrl = null;
    private ScheduledFuture<?> advanceFuture;
    private ScheduledFuture<?> progressFuture;

    public PlaylistManager(MinecraftServer server, CompanionClient companionClient) {
        this.server = server;
        this.companionClient = companionClient;

        this.progressFuture = scheduler.scheduleAtFixedRate(() -> {
            if (isPlaying && currentIndex >= 0 && currentIndex < playlist.size()) {
                PlaylistSyncPacket.Entry entry = playlist.get(currentIndex);
                server.execute(() -> broadcastNowPlaying(entry.title(), getElapsedPlaybackMs()));
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
        companionClient.deleteDownload(videoId);

        if (currentIndex >= 0) {
            if (removeIndex < currentIndex) {
                currentIndex--;
            } else if (removeIndex == currentIndex) {
                if (advanceFuture != null) {
                    advanceFuture.cancel(false);
                }
                currentIndex--;
                playNext();
            }
        }

        syncToAll();
    }

    public void handlePlaybackControl(ServerPlayerEntity player, String action) {
        if (action == null) {
            return;
        }

        switch (action.toLowerCase()) {
            case "skip" -> {
                Mineify.LOGGER.info("Player {} requested skip", player.getName().getString());
                if (isPlaying) {
                    if (advanceFuture != null) {
                        advanceFuture.cancel(false);
                    }
                    advanceAfterTrackEnd();
                }
            }
            case "pause" -> {
                Mineify.LOGGER.info("Player {} requested pause", player.getName().getString());
                pausePlayback();
            }
            case "resume" -> {
                Mineify.LOGGER.info("Player {} requested resume", player.getName().getString());
                resumePlayback();
            }
            default -> Mineify.LOGGER.warn("Unknown playback action '{}'", action);
        }
    }

    public void syncToPlayer(ServerPlayerEntity player) {
        ServerPlayNetworking.send(player, new PlaylistSyncPacket(new ArrayList<>(playlist)));
        if (isPlaying && currentIndex >= 0 && currentIndex < playlist.size()) {
            PlaylistSyncPacket.Entry entry = playlist.get(currentIndex);
            long elapsedMs = getElapsedPlaybackMs();
            ServerPlayNetworking.send(player, buildNowPlayingPacket(entry.title(), elapsedMs));

            if (currentDownloadUrl != null) {
                ServerPlayNetworking.send(player, new PlayAudioPacket(
                        currentDownloadUrl,
                        entry.title(),
                        entry.videoId(),
                        elapsedMs
                ));
            }

            ServerPlayNetworking.send(player, new PlaybackStatePacket(paused));
        }
    }

    private void playNext() {
        currentIndex++;
        if (currentIndex >= playlist.size()) {
            isPlaying = false;
            paused = false;
            pausedElapsedMs = 0;
            currentIndex = -1;
            currentDownloadUrl = null;
            playbackStartNanos = 0;
            broadcastNowPlaying("", 0);
            broadcastPlaybackState(false);
            return;
        }

        PlaylistSyncPacket.Entry entry = playlist.get(currentIndex);
        isPlaying = true;
        paused = false;
        pausedElapsedMs = 0;
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
                playbackStartNanos = System.nanoTime();

                PlayAudioPacket packet = new PlayAudioPacket(
                        downloadUrl,
                        entry.title(),
                        entry.videoId(),
                        0L
                );
                for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                    ServerPlayNetworking.send(p, packet);
                }

                broadcastPlaybackState(false);
                broadcastNowPlaying(entry.title(), 0L);
                scheduleAdvanceFromCurrentState();
            });
        });
    }

    private void advanceAfterTrackEnd() {
        if (currentIndex >= 0 && currentIndex < playlist.size()) {
            PlaylistSyncPacket.Entry finished = playlist.remove(currentIndex);
            companionClient.deleteDownload(finished.videoId());
            currentIndex--;
            syncToAll();
        }
        playNext();
    }

    private void pausePlayback() {
        if (!isPlaying || paused || currentIndex < 0 || currentIndex >= playlist.size()) {
            return;
        }

        pausedElapsedMs = getElapsedPlaybackMs();
        paused = true;
        cancelAdvanceSchedule();
        broadcastPlaybackState(true);
        broadcastNowPlaying(playlist.get(currentIndex).title(), pausedElapsedMs);
    }

    private void resumePlayback() {
        if (!isPlaying || !paused || currentIndex < 0 || currentIndex >= playlist.size()) {
            return;
        }

        playbackStartNanos = System.nanoTime() - (pausedElapsedMs * 1_000_000L);
        paused = false;
        broadcastPlaybackState(false);
        broadcastNowPlaying(playlist.get(currentIndex).title(), getElapsedPlaybackMs());
        scheduleAdvanceFromCurrentState();
    }

    private void scheduleAdvanceFromCurrentState() {
        cancelAdvanceSchedule();
        if (!isPlaying || paused || currentTrackDurationMs <= 0) {
            return;
        }

        long remainingMs = Math.max(0, currentTrackDurationMs - getElapsedPlaybackMs());
        advanceFuture = scheduler.schedule(
                () -> server.execute(this::advanceAfterTrackEnd),
                remainingMs + 2000,
                TimeUnit.MILLISECONDS
        );
    }

    private void cancelAdvanceSchedule() {
        if (advanceFuture != null) {
            advanceFuture.cancel(false);
            advanceFuture = null;
        }
    }

    private void syncToAll() {
        PlaylistSyncPacket packet = new PlaylistSyncPacket(new ArrayList<>(playlist));
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ServerPlayNetworking.send(player, packet);
        }
    }

    private void broadcastNowPlaying(String title, long elapsedMs) {
        NowPlayingPacket packet = buildNowPlayingPacket(title, elapsedMs);
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ServerPlayNetworking.send(player, packet);
        }
    }

    private NowPlayingPacket buildNowPlayingPacket(String title, long elapsedMs) {
        long safeDurationMs = Math.max(0, currentTrackDurationMs);
        long safeElapsedMs = Math.max(0, Math.min(elapsedMs, safeDurationMs));
        float progress = safeDurationMs > 0 ? (float) safeElapsedMs / safeDurationMs : 0f;
        return new NowPlayingPacket(title, Math.min(progress, 1f), safeElapsedMs, safeDurationMs, paused);
    }

    private void broadcastPlaybackState(boolean pausedValue) {
        PlaybackStatePacket packet = new PlaybackStatePacket(pausedValue);
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ServerPlayNetworking.send(player, packet);
        }
    }

    private long parseDuration(String duration) {
        if (duration == null || duration.isEmpty()) {
            return 3 * 60 * 1000;
        }
        try {
            String[] parts = duration.split(":");
            long seconds = 0;
            for (String part : parts) {
                seconds = seconds * 60 + Long.parseLong(part.trim());
            }
            return seconds * 1000;
        } catch (NumberFormatException e) {
            return 3 * 60 * 1000;
        }
    }

    private long getElapsedPlaybackMs() {
        if (!isPlaying) {
            return 0;
        }
        if (paused) {
            return Math.max(0, pausedElapsedMs);
        }
        if (playbackStartNanos <= 0) {
            return 0;
        }
        return Math.max(0, (System.nanoTime() - playbackStartNanos) / 1_000_000L);
    }

    public void shutdown() {
        cancelAdvanceSchedule();
        if (progressFuture != null) {
            progressFuture.cancel(true);
        }
        scheduler.shutdownNow();
        playlist.clear();
        Mineify.LOGGER.info("Mineify: Playlist manager shut down");
    }
}
