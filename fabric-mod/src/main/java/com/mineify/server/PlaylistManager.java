package com.mineify.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mineify.Mineify;
import com.mineify.MineifyConfig;
import com.mineify.network.packets.NowPlayingPacket;
import com.mineify.network.packets.PlayAudioPacket;
import com.mineify.network.packets.PlaybackStatePacket;
import com.mineify.network.packets.PlaylistSyncPacket;
import com.mineify.network.packets.ProfilesSyncPacket;
import com.mineify.network.packets.RecentlyPlayedSyncPacket;
import com.mineify.network.packets.SearchResultsPacket;
import com.mineify.network.packets.SpotifyImportFinishedPacket;
import com.mineify.network.packets.SpotifyImportPromptPacket;
import com.mineify.network.packets.UserPlaylistsSyncPacket;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.nio.file.Files;
import java.nio.file.Path;

public class PlaylistManager {
    private final MinecraftServer server;
    private final CompanionClient companionClient;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final List<PlaylistSyncPacket.Entry> playlist = new CopyOnWriteArrayList<>();
    private final Map<String, List<UserPlaylist>> userPlaylistsByOwner = new HashMap<>();
    private final List<RecentlyPlayedEntry> recentlyPlayed = new ArrayList<>();
    private final Map<String, SpotifyImportSession> spotifyImportSessions = new ConcurrentHashMap<>();
    private final Path userPlaylistsFile;
    private final Path recentlyPlayedFile;
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
        this.userPlaylistsFile = server.getRunDirectory()
                .resolve("MineifyCompanion")
                .resolve("playlists.json");
        Path configuredRecentlyPath = Path.of(MineifyConfig.getRecentlyPlayedPersistPath());
        this.recentlyPlayedFile = configuredRecentlyPath.isAbsolute()
                ? configuredRecentlyPath
                : server.getRunDirectory().resolve(configuredRecentlyPath);
        loadUserPlaylists();
        loadRecentlyPlayed();

        this.progressFuture = scheduler.scheduleAtFixedRate(() -> {
            if (isPlaying && currentIndex >= 0 && currentIndex < playlist.size()) {
                PlaylistSyncPacket.Entry entry = playlist.get(currentIndex);
                server.execute(() -> broadcastNowPlaying(entry.title(), getElapsedPlaybackMs()));
            }
        }, 1000, Math.max(250, MineifyConfig.getPlaybackProgressBroadcastIntervalMs()), TimeUnit.MILLISECONDS);
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
        if (playlist.size() >= MineifyConfig.getMaxPlaylistSize()) {
            player.sendMessage(net.minecraft.text.Text.literal("Queue is full (max " + MineifyConfig.getMaxPlaylistSize() + ")."), false);
            return;
        }

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

    public void handleQueueReorder(ServerPlayerEntity player, int fromIndex, int toIndex) {
        int size = playlist.size();
        if (fromIndex < 0 || toIndex < 0 || fromIndex >= size || toIndex >= size || fromIndex == toIndex) {
            return;
        }

        PlaylistSyncPacket.Entry moved = playlist.remove(fromIndex);
        playlist.add(toIndex, moved);

        if (isPlaying && currentIndex >= 0) {
            if (currentIndex == fromIndex) {
                currentIndex = toIndex;
            } else if (fromIndex < currentIndex && toIndex >= currentIndex) {
                currentIndex--;
            } else if (fromIndex > currentIndex && toIndex <= currentIndex) {
                currentIndex++;
            }
        }

        Mineify.LOGGER.info("Player {} reordered queue: {} -> {}", player.getName().getString(), fromIndex, toIndex);
        syncToAll();
    }

    public void handleRequestUserPlaylists(ServerPlayerEntity player) {
        syncUserPlaylistsToPlayer(player);
    }

    public void handleRequestProfiles(ServerPlayerEntity player, String query) {
        String normalizedQuery = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        List<ProfilesSyncPacket.ProfileEntry> entries = new ArrayList<>();
        String selfId = player.getUuidAsString();

        List<UserPlaylist> selfPlaylists = userPlaylistsByOwner.getOrDefault(selfId, List.of());
        entries.add(new ProfilesSyncPacket.ProfileEntry(
                selfId,
                player.getName().getString(),
                true,
                toProfilePlaylistEntries(selfPlaylists, true)
        ));

        for (Map.Entry<String, List<UserPlaylist>> entry : userPlaylistsByOwner.entrySet()) {
            String ownerId = entry.getKey();
            if (ownerId.equals(selfId)) {
                continue;
            }

            List<UserPlaylist> ownerPlaylists = entry.getValue();
            List<ProfilesSyncPacket.PlaylistEntry> publicPlaylists = toProfilePlaylistEntries(ownerPlaylists, false);
            if (publicPlaylists.isEmpty()) {
                continue;
            }

            String ownerName = resolveOwnerName(ownerId, ownerPlaylists);
            if (!normalizedQuery.isEmpty() && !ownerName.toLowerCase(Locale.ROOT).contains(normalizedQuery)) {
                continue;
            }

            entries.add(new ProfilesSyncPacket.ProfileEntry(ownerId, ownerName, false, publicPlaylists));
        }

        entries.sort((a, b) -> {
            if (a.isSelf() != b.isSelf()) {
                return a.isSelf() ? -1 : 1;
            }
            return a.ownerName().compareToIgnoreCase(b.ownerName());
        });

        ServerPlayNetworking.send(player, new ProfilesSyncPacket(entries));
    }

    public void handleStartSpotifyImport(ServerPlayerEntity player, String spotifyUrl, String targetPlaylistId) {
        if (!MineifyConfig.isSpotifyImportEnabled()) {
            player.sendMessage(net.minecraft.text.Text.literal("Spotify import is disabled by server config."), false);
            return;
        }
        if (spotifyUrl == null || spotifyUrl.isBlank() || targetPlaylistId == null || targetPlaylistId.isBlank()) {
            return;
        }

        UserPlaylist targetPlaylist = findUserPlaylist(player.getUuidAsString(), targetPlaylistId);
        if (targetPlaylist == null) {
            return;
        }

        String playerId = player.getUuidAsString();
        spotifyImportSessions.remove(playerId);

        companionClient.getSpotifyPlaylistTracks(spotifyUrl).thenAccept(tracks -> {
            server.execute(() -> {
                if (tracks.isEmpty()) {
                    player.sendMessage(net.minecraft.text.Text.literal("Spotify import failed: no tracks found."), false);
                    return;
                }

                int maxTracks = Math.max(1, MineifyConfig.getSpotifyImportMaxTracksPerImport());
                List<CompanionClient.SpotifyTrack> limitedTracks = tracks.size() > maxTracks
                        ? new ArrayList<>(tracks.subList(0, maxTracks))
                        : tracks;
                SpotifyImportSession session = new SpotifyImportSession(playerId, targetPlaylistId, limitedTracks);
                spotifyImportSessions.put(playerId, session);
                processSpotifyImportNext(player);
            });
        });
    }

    public void handleResolveSpotifyImportChoice(ServerPlayerEntity player, String videoId) {
        SpotifyImportSession session = spotifyImportSessions.get(player.getUuidAsString());
        if (session == null || session.pendingTrack == null) {
            return;
        }

        CompanionClient.SearchResult chosen = null;
        if (videoId != null && !videoId.isBlank()) {
            for (CompanionClient.SearchResult option : session.pendingOptions) {
                if (option.videoId().equals(videoId)) {
                    chosen = option;
                    break;
                }
            }
        }

        if (chosen != null) {
            boolean added = addTrackToUserPlaylistInternal(player.getUuidAsString(), session.targetPlaylistId, chosen.videoId(), chosen.title(), chosen.duration());
            if (added) {
                session.addedCount++;
            } else {
                session.skippedCount++;
            }
        } else {
            session.skippedCount++;
            session.unresolvedCount++;
        }

        session.pendingTrack = null;
        session.pendingOptions = List.of();
        session.currentIndex++;
        processSpotifyImportNext(player);
    }

    public void handleRequestRecentlyPlayed(ServerPlayerEntity player) {
        syncRecentlyPlayedToPlayer(player);
    }

    public void handleCreateUserPlaylist(
            ServerPlayerEntity player,
            String name,
            boolean isPublic,
            boolean addInitialTrack,
            String videoId,
            String title,
            String duration
    ) {
        String trimmedName = name == null ? "" : name.trim();
        if (trimmedName.isEmpty()) {
            return;
        }
        if (trimmedName.length() > MineifyConfig.getPlaylistsMaxNameLength()) {
            trimmedName = trimmedName.substring(0, MineifyConfig.getPlaylistsMaxNameLength());
        }

        List<UserPlaylist> userPlaylists = userPlaylistsByOwner.computeIfAbsent(player.getUuidAsString(), key -> new ArrayList<>());
        if (userPlaylists.size() >= MineifyConfig.getPlaylistsMaxPerUser()) {
            player.sendMessage(net.minecraft.text.Text.literal("You reached the max playlists limit (" + MineifyConfig.getPlaylistsMaxPerUser() + ")."), false);
            return;
        }
        UserPlaylist playlistModel = new UserPlaylist(
                UUID.randomUUID().toString(),
                player.getUuidAsString(),
                player.getName().getString(),
                trimmedName,
                isPublic
        );
        if (addInitialTrack && videoId != null && !videoId.isBlank()) {
            playlistModel.tracks.add(new UserPlaylistTrack(videoId, title, duration));
        }
        userPlaylists.add(playlistModel);

        saveUserPlaylists();
        syncUserPlaylistsToPlayer(player);
    }

    public void handleAddToUserPlaylist(
            ServerPlayerEntity player,
            String playlistId,
            String videoId,
            String title,
            String duration
    ) {
        if (playlistId == null || playlistId.isBlank() || videoId == null || videoId.isBlank()) {
            return;
        }

        List<UserPlaylist> userPlaylists = userPlaylistsByOwner.get(player.getUuidAsString());
        if (userPlaylists == null) {
            return;
        }
        if (addTrackToUserPlaylistInternal(player.getUuidAsString(), playlistId, videoId, title, duration)) {
            saveUserPlaylists();
            syncUserPlaylistsToPlayer(player);
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
        syncUserPlaylistsToPlayer(player);
        syncRecentlyPlayedToPlayer(player);
    }

    private void playNext() {
        currentIndex++;
        if (currentIndex >= playlist.size()) {
            cancelAdvanceSchedule();
            isPlaying = false;
            paused = false;
            pausedElapsedMs = 0;
            currentIndex = -1;
            currentDownloadUrl = null;
            playbackStartNanos = 0;
            currentTrackDurationMs = 0;
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

                recordRecentlyPlayed(entry);
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
                remainingMs + Math.max(0, MineifyConfig.getPlaybackTrackEndPaddingMs()),
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

    private void syncUserPlaylistsToPlayer(ServerPlayerEntity player) {
        List<UserPlaylist> playlists = userPlaylistsByOwner.getOrDefault(player.getUuidAsString(), List.of());
        List<UserPlaylistsSyncPacket.Entry> entries = new ArrayList<>(playlists.size());
        for (UserPlaylist playlistModel : playlists) {
            entries.add(new UserPlaylistsSyncPacket.Entry(
                    playlistModel.id,
                    playlistModel.name,
                    playlistModel.isPublic,
                    playlistModel.tracks.size()
            ));
        }
        ServerPlayNetworking.send(player, new UserPlaylistsSyncPacket(entries));
    }

    private void syncRecentlyPlayedToPlayer(ServerPlayerEntity player) {
        if (!MineifyConfig.isRecentlyPlayedEnabled()) {
            ServerPlayNetworking.send(player, new RecentlyPlayedSyncPacket(List.of()));
            return;
        }
        List<RecentlyPlayedSyncPacket.Entry> entries = new ArrayList<>(recentlyPlayed.size());
        for (RecentlyPlayedEntry entry : recentlyPlayed) {
            entries.add(new RecentlyPlayedSyncPacket.Entry(
                    entry.videoId,
                    entry.title,
                    entry.duration,
                    entry.playedAtEpochMs
            ));
        }
        ServerPlayNetworking.send(player, new RecentlyPlayedSyncPacket(entries));
    }

    private void broadcastRecentlyPlayed() {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            syncRecentlyPlayedToPlayer(player);
        }
    }

    private void recordRecentlyPlayed(PlaylistSyncPacket.Entry entry) {
        if (!MineifyConfig.isRecentlyPlayedEnabled()) {
            return;
        }
        recentlyPlayed.add(0, new RecentlyPlayedEntry(
                entry.videoId(),
                entry.title(),
                entry.duration(),
                System.currentTimeMillis()
        ));
        if (recentlyPlayed.size() > MineifyConfig.getRecentlyPlayedMaxEntries()) {
            recentlyPlayed.remove(recentlyPlayed.size() - 1);
        }
        saveRecentlyPlayed();
        if (MineifyConfig.isRecentlyPlayedBroadcastOnUpdate()) {
            broadcastRecentlyPlayed();
        }
    }

    private void processSpotifyImportNext(ServerPlayerEntity player) {
        SpotifyImportSession session = spotifyImportSessions.get(player.getUuidAsString());
        if (session == null) {
            return;
        }

        if (session.currentIndex >= session.tracks.size()) {
            spotifyImportSessions.remove(player.getUuidAsString());
            saveUserPlaylists();
            syncUserPlaylistsToPlayer(player);
            ServerPlayNetworking.send(player, new SpotifyImportFinishedPacket(
                    session.addedCount,
                    session.skippedCount,
                    session.unresolvedCount
            ));
            return;
        }

        CompanionClient.SpotifyTrack spotifyTrack = session.tracks.get(session.currentIndex);
        companionClient.search(spotifyTrack.query()).thenAccept(results ->
                server.execute(() -> handleSpotifySearchResults(player, session, spotifyTrack, results))
        );
    }

    private void handleSpotifySearchResults(
            ServerPlayerEntity player,
            SpotifyImportSession session,
            CompanionClient.SpotifyTrack spotifyTrack,
            List<CompanionClient.SearchResult> results
    ) {
        if (results == null || results.isEmpty()) {
            session.skippedCount++;
            session.unresolvedCount++;
            session.currentIndex++;
            processSpotifyImportNext(player);
            return;
        }

        int topCount = Math.min(3, results.size());
        List<CompanionClient.SearchResult> topResults = new ArrayList<>(results.subList(0, topCount));
        double bestScore = scoreMatch(spotifyTrack, topResults.get(0));

        if (bestScore >= MineifyConfig.getSpotifyImportAutoMatchThreshold()) {
            CompanionClient.SearchResult best = topResults.get(0);
            boolean added = addTrackToUserPlaylistInternal(player.getUuidAsString(), session.targetPlaylistId, best.videoId(), best.title(), best.duration());
            if (added) {
                session.addedCount++;
            } else {
                session.skippedCount++;
            }
            session.currentIndex++;
            processSpotifyImportNext(player);
            return;
        }

        session.pendingTrack = spotifyTrack;
        session.pendingOptions = topResults;

        List<SpotifyImportPromptPacket.Option> options = new ArrayList<>();
        for (CompanionClient.SearchResult option : topResults) {
            options.add(new SpotifyImportPromptPacket.Option(
                    option.videoId(),
                    option.title(),
                    option.channel(),
                    option.duration()
            ));
        }

        ServerPlayNetworking.send(player, new SpotifyImportPromptPacket(
                spotifyTrack.title(),
                spotifyTrack.artist(),
                session.currentIndex + 1,
                session.tracks.size(),
                options
        ));
    }

    private double scoreMatch(CompanionClient.SpotifyTrack spotifyTrack, CompanionClient.SearchResult youtube) {
        String yt = normalizeText(youtube.title());
        String title = normalizeText(spotifyTrack.title());
        String artist = normalizeText(spotifyTrack.artist());

        double score = 0.0d;
        if (!title.isBlank() && yt.contains(title)) {
            score += 0.65d;
        }
        if (!artist.isBlank()) {
            String[] parts = artist.split(",");
            for (String part : parts) {
                String trimmed = part.trim();
                if (!trimmed.isBlank() && yt.contains(trimmed)) {
                    score += 0.18d;
                    break;
                }
            }
        }
        if (yt.contains("karaoke") || yt.contains("slowed") || yt.contains("sped up") || yt.contains("nightcore")) {
            score -= 0.22d;
        }
        return Math.max(0.0d, Math.min(1.0d, score));
    }

    private String normalizeText(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\s,]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private UserPlaylist findUserPlaylist(String ownerId, String playlistId) {
        List<UserPlaylist> userPlaylists = userPlaylistsByOwner.get(ownerId);
        if (userPlaylists == null) {
            return null;
        }
        for (UserPlaylist playlistModel : userPlaylists) {
            if (playlistModel.id.equals(playlistId)) {
                return playlistModel;
            }
        }
        return null;
    }

    private boolean addTrackToUserPlaylistInternal(String ownerId, String playlistId, String videoId, String title, String duration) {
        UserPlaylist playlistModel = findUserPlaylist(ownerId, playlistId);
        if (playlistModel == null) {
            return false;
        }
        if (playlistModel.tracks.size() >= MineifyConfig.getPlaylistsMaxTracksPerPlaylist()) {
            return false;
        }
        playlistModel.tracks.add(new UserPlaylistTrack(videoId, title, duration));
        return true;
    }

    private List<ProfilesSyncPacket.PlaylistEntry> toProfilePlaylistEntries(List<UserPlaylist> playlists, boolean includePrivate) {
        List<ProfilesSyncPacket.PlaylistEntry> entries = new ArrayList<>();
        for (UserPlaylist playlistModel : playlists) {
            if (!includePrivate && !playlistModel.isPublic) {
                continue;
            }
            entries.add(new ProfilesSyncPacket.PlaylistEntry(
                    playlistModel.id,
                    playlistModel.name,
                    playlistModel.isPublic,
                    playlistModel.tracks.size()
            ));
        }
        return entries;
    }

    private String resolveOwnerName(String ownerId, List<UserPlaylist> ownerPlaylists) {
        if (ownerPlaylists != null && !ownerPlaylists.isEmpty()) {
            String name = ownerPlaylists.get(0).ownerName;
            if (name != null && !name.isBlank()) {
                return name;
            }
        }

        for (ServerPlayerEntity onlinePlayer : server.getPlayerManager().getPlayerList()) {
            if (onlinePlayer.getUuidAsString().equals(ownerId)) {
                return onlinePlayer.getName().getString();
            }
        }
        return ownerId.length() > 8 ? ownerId.substring(0, 8) : ownerId;
    }

    private void loadUserPlaylists() {
        if (!Files.exists(userPlaylistsFile)) {
            return;
        }

        try (Reader reader = Files.newBufferedReader(userPlaylistsFile)) {
            Type type = new TypeToken<Map<String, List<UserPlaylist>>>() {
            }.getType();
            Map<String, List<UserPlaylist>> loaded = gson.fromJson(reader, type);
            userPlaylistsByOwner.clear();
            if (loaded != null) {
                userPlaylistsByOwner.putAll(loaded);
            }
            Mineify.LOGGER.info("Loaded {} user playlist groups", userPlaylistsByOwner.size());
        } catch (IOException e) {
            Mineify.LOGGER.error("Failed to load user playlists from {}", userPlaylistsFile, e);
        }
    }

    private void loadRecentlyPlayed() {
        if (!MineifyConfig.isRecentlyPlayedEnabled()) {
            recentlyPlayed.clear();
            return;
        }
        if (!Files.exists(recentlyPlayedFile)) {
            return;
        }

        try (Reader reader = Files.newBufferedReader(recentlyPlayedFile)) {
            Type type = new TypeToken<List<RecentlyPlayedEntry>>() {
            }.getType();
            List<RecentlyPlayedEntry> loaded = gson.fromJson(reader, type);
            recentlyPlayed.clear();
            if (loaded != null) {
                recentlyPlayed.addAll(loaded);
                while (recentlyPlayed.size() > MineifyConfig.getRecentlyPlayedMaxEntries()) {
                    recentlyPlayed.remove(recentlyPlayed.size() - 1);
                }
            }
            Mineify.LOGGER.info("Loaded {} recently played entries", recentlyPlayed.size());
        } catch (IOException e) {
            Mineify.LOGGER.error("Failed to load recently played from {}", recentlyPlayedFile, e);
        }
    }

    private void saveUserPlaylists() {
        try {
            Files.createDirectories(userPlaylistsFile.getParent());
            try (Writer writer = Files.newBufferedWriter(userPlaylistsFile)) {
                gson.toJson(userPlaylistsByOwner, writer);
            }
        } catch (IOException e) {
            Mineify.LOGGER.error("Failed to save user playlists to {}", userPlaylistsFile, e);
        }
    }

    private void saveRecentlyPlayed() {
        if (!MineifyConfig.isRecentlyPlayedEnabled()) {
            return;
        }
        try {
            Files.createDirectories(recentlyPlayedFile.getParent());
            try (Writer writer = Files.newBufferedWriter(recentlyPlayedFile)) {
                gson.toJson(recentlyPlayed, writer);
            }
        } catch (IOException e) {
            Mineify.LOGGER.error("Failed to save recently played to {}", recentlyPlayedFile, e);
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
        saveUserPlaylists();
        saveRecentlyPlayed();
        spotifyImportSessions.clear();
        scheduler.shutdownNow();
        playlist.clear();
        Mineify.LOGGER.info("Mineify: Playlist manager shut down");
    }

    private static class UserPlaylist {
        String id;
        String ownerId;
        String ownerName;
        String name;
        boolean isPublic;
        List<UserPlaylistTrack> tracks = new ArrayList<>();

        UserPlaylist() {
        }

        UserPlaylist(String id, String ownerId, String ownerName, String name, boolean isPublic) {
            this.id = id;
            this.ownerId = ownerId;
            this.ownerName = ownerName;
            this.name = name;
            this.isPublic = isPublic;
        }
    }

    private static class UserPlaylistTrack {
        String videoId;
        String title;
        String duration;

        UserPlaylistTrack() {
        }

        UserPlaylistTrack(String videoId, String title, String duration) {
            this.videoId = videoId;
            this.title = title;
            this.duration = duration;
        }
    }

    private static class RecentlyPlayedEntry {
        String videoId;
        String title;
        String duration;
        long playedAtEpochMs;

        RecentlyPlayedEntry() {
        }

        RecentlyPlayedEntry(String videoId, String title, String duration, long playedAtEpochMs) {
            this.videoId = videoId;
            this.title = title;
            this.duration = duration;
            this.playedAtEpochMs = playedAtEpochMs;
        }
    }

    private static class SpotifyImportSession {
        final String ownerId;
        final String targetPlaylistId;
        final List<CompanionClient.SpotifyTrack> tracks;
        int currentIndex = 0;
        int addedCount = 0;
        int skippedCount = 0;
        int unresolvedCount = 0;
        CompanionClient.SpotifyTrack pendingTrack;
        List<CompanionClient.SearchResult> pendingOptions = List.of();

        SpotifyImportSession(String ownerId, String targetPlaylistId, List<CompanionClient.SpotifyTrack> tracks) {
            this.ownerId = ownerId;
            this.targetPlaylistId = targetPlaylistId;
            this.tracks = tracks;
        }
    }
}
