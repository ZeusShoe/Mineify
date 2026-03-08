package com.mineify.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mineify.Mineify;
import com.mineify.MineifyConfig;
import com.mineify.network.packets.AudioStreamChunkPacket;
import com.mineify.network.packets.AudioStreamEndPacket;
import com.mineify.network.packets.AudioStreamStartPacket;
import com.mineify.network.packets.NowPlayingPacket;
import com.mineify.network.packets.PlaybackLockPacket;
import com.mineify.network.packets.PlaybackStatePacket;
import com.mineify.network.packets.PlaylistSyncPacket;
import com.mineify.network.packets.SpotifyImportPreviewPacket;
import com.mineify.network.packets.ProfilesSyncPacket;
import com.mineify.network.packets.RecentlyPlayedSyncPacket;
import com.mineify.network.packets.SearchResultsPacket;
import com.mineify.network.packets.SpotifyImportFinishedPacket;
import com.mineify.network.packets.SpotifyImportPromptPacket;
import com.mineify.network.packets.UserPlaylistsSyncPacket;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.IOException;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URL;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import java.util.Deque;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.nio.file.Files;
import java.nio.file.Path;

public class PlaylistManager {
    private static final String PERM_SEARCH = "mineify.search";
    private static final String PERM_QUEUE_ADD = "mineify.queue.add";
    private static final String PERM_QUEUE_REMOVE = "mineify.queue.remove";
    private static final String PERM_QUEUE_REORDER = "mineify.queue.reorder";
    private static final String PERM_QUEUE_UNDO = "mineify.queue.undo";
    private static final String PERM_QUEUE_CLEAR = "mineify.queue.clear";
    private static final String PERM_PLAYBACK_SKIP = "mineify.playback.skip";
    private static final String PERM_PLAYBACK_PAUSE = "mineify.playback.pause";
    private static final String PERM_PLAYBACK_RESUME = "mineify.playback.resume";
    private static final String PERM_PLAYBACK_SEEK = "mineify.playback.seek";
    private static final String PERM_PLAYLISTS_VIEW = "mineify.playlists.view";
    private static final String PERM_PLAYLISTS_CREATE = "mineify.playlists.create";
    private static final String PERM_PLAYLISTS_ADD_TRACK = "mineify.playlists.add_track";
    private static final String PERM_PLAYLISTS_LIKE = "mineify.playlists.like";
    private static final String PERM_SPOTIFY_IMPORT = "mineify.spotify.import";
    private static final String PERM_RECENTLY_PLAYED_VIEW = "mineify.recently_played.view";
    private static final long MAX_TRACK_DURATION_MS = 20L * 60L * 1000L;
    private static volatile Method permissionsCheckMethod;
    private static volatile boolean permissionsLookupDone = false;

    public static List<String> getPermissionNodes() {
        return List.of(
                PERM_SEARCH,
                PERM_QUEUE_ADD,
                PERM_QUEUE_REMOVE,
                PERM_QUEUE_REORDER,
                PERM_QUEUE_UNDO,
                PERM_QUEUE_CLEAR,
                PERM_PLAYBACK_SKIP,
                PERM_PLAYBACK_PAUSE,
                PERM_PLAYBACK_RESUME,
                PERM_PLAYBACK_SEEK,
                PERM_PLAYLISTS_VIEW,
                PERM_PLAYLISTS_CREATE,
                PERM_PLAYLISTS_ADD_TRACK,
                PERM_PLAYLISTS_LIKE,
                PERM_SPOTIFY_IMPORT,
                PERM_RECENTLY_PLAYED_VIEW
        );
    }

    public record PlaylistStatus(
            boolean playing,
            boolean paused,
            String nowPlayingTitle,
            int queueSize,
            boolean waitingForReady
    ) {}

    private final MinecraftServer server;
    private CompanionClient companionClient;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final List<PlaylistSyncPacket.Entry> playlist = new CopyOnWriteArrayList<>();
    private final Deque<QueueSnapshot> queueUndoHistory = new ArrayDeque<>();
    private final Map<String, List<UserPlaylist>> userPlaylistsByOwner = new HashMap<>();
    private final Map<String, java.util.Set<String>> likedPlaylistRefsByUser = new HashMap<>();
    private final List<RecentlyPlayedEntry> recentlyPlayed = new ArrayList<>();
    private final Map<String, SpotifyImportSession> spotifyImportSessions = new ConcurrentHashMap<>();
    private final Map<String, SpotifyImportPreviewSession> spotifyImportPreviewSessions = new ConcurrentHashMap<>();
    private final Path userPlaylistsFile;
    private final Path playlistLikesFile;
    private final Path recentlyPlayedFile;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Mineify-Scheduler");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService streamExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Mineify-Stream");
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
    private StreamSession currentStream;
    private long streamIdCounter = 0L;
    private long playbackRequestNonce = 0L;
    private long waitingReadyNonce = -1L;
    private String waitingReadyVideoId = null;
    private final java.util.Set<String> waitingReadyPlayers = new java.util.HashSet<>();
    private final Set<String> skipVotes = new java.util.HashSet<>();
    private final Set<String> replayVotes = new java.util.HashSet<>();
    private long metricsNextLogAtMs = 0L;
    private long metricsDownloadCount = 0L;
    private long metricsDownloadTotalMs = 0L;
    private long metricsReadyCount = 0L;
    private long metricsReadyTotalMs = 0L;
    private long waitingReadyStartedAtNs = 0L;
    private ScheduledFuture<?> advanceFuture;
    private ScheduledFuture<?> progressFuture;

    private void pushQueueUndoSnapshot(String reason) {
        int max = Math.max(1, MineifyConfig.getQueueUndoMaxHistory());
        String nowPlayingVideoId = null;
        if (currentIndex >= 0 && currentIndex < playlist.size()) {
            nowPlayingVideoId = playlist.get(currentIndex).videoId();
        }
        queueUndoHistory.addFirst(new QueueSnapshot(new ArrayList<>(playlist), nowPlayingVideoId, reason));
        while (queueUndoHistory.size() > max) {
            queueUndoHistory.removeLast();
        }
    }

    private boolean restoreLatestQueueSnapshot() {
        QueueSnapshot snapshot = queueUndoHistory.pollFirst();
        if (snapshot == null) {
            return false;
        }
        playlist.clear();
        playlist.addAll(snapshot.entries);
        if (snapshot.nowPlayingVideoId != null && !snapshot.nowPlayingVideoId.isBlank()) {
            int idx = -1;
            for (int i = 0; i < playlist.size(); i++) {
                if (snapshot.nowPlayingVideoId.equals(playlist.get(i).videoId())) {
                    idx = i;
                    break;
                }
            }
            currentIndex = idx;
            isPlaying = idx >= 0;
        } else {
            currentIndex = -1;
            isPlaying = false;
            paused = false;
            pausedElapsedMs = 0;
            currentDownloadUrl = null;
            playbackStartNanos = 0;
            currentTrackDurationMs = 0;
        }
        syncToAll();
        return true;
    }

    public PlaylistManager(MinecraftServer server, CompanionClient companionClient) {
        this.server = server;
        this.companionClient = companionClient;
        this.userPlaylistsFile = server.getRunDirectory()
                .resolve("MineifyCompanion")
                .resolve("playlists.json");
        this.playlistLikesFile = server.getRunDirectory()
                .resolve("MineifyCompanion")
                .resolve("playlist_likes.json");
        Path configuredRecentlyPath = Path.of(MineifyConfig.getRecentlyPlayedPersistPath());
        this.recentlyPlayedFile = configuredRecentlyPath.isAbsolute()
                ? configuredRecentlyPath
                : server.getRunDirectory().resolve(configuredRecentlyPath);
        loadUserPlaylists();
        loadPlaylistLikes();
        loadRecentlyPlayed();

        this.progressFuture = scheduler.scheduleAtFixedRate(() -> {
            if (isPlaying && currentIndex >= 0 && currentIndex < playlist.size()) {
                PlaylistSyncPacket.Entry entry = playlist.get(currentIndex);
                server.execute(() -> broadcastNowPlaying(entry.title(), getElapsedPlaybackMs()));
            }
            maybeLogMetrics();
        }, 1000, Math.max(250, MineifyConfig.getPlaybackProgressBroadcastIntervalMs()), TimeUnit.MILLISECONDS);
    }

    public void setCompanionClient(CompanionClient companionClient) {
        if (companionClient != null) {
            this.companionClient = companionClient;
        }
    }

    public void handleSearch(ServerPlayerEntity player, String query) {
        if (!hasPerm(player, PERM_SEARCH, true)) {
            player.sendMessage(Text.literal("You don't have permission to search tracks."), false);
            return;
        }
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
        if (!hasPerm(player, PERM_QUEUE_ADD, true)) {
            player.sendMessage(Text.literal("You don't have permission to add songs to queue."), false);
            return;
        }
        long durationMs = parseDuration(duration);
        if (durationMs > MAX_TRACK_DURATION_MS) {
            player.sendMessage(Text.literal("Track is too long. Max allowed duration is 20:00."), false);
            return;
        }
        Mineify.LOGGER.info("Player {} adding to playlist: {}", player.getName().getString(), title);
        if (playlist.size() >= MineifyConfig.getMaxPlaylistSize()) {
            player.sendMessage(net.minecraft.text.Text.literal("Queue is full (max " + MineifyConfig.getMaxPlaylistSize() + ")."), false);
            return;
        }
        pushQueueUndoSnapshot("add");

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
        if (!hasPerm(player, PERM_QUEUE_REMOVE, true)) {
            player.sendMessage(Text.literal("You don't have permission to remove songs from queue."), false);
            return;
        }
        int removeIndex = -1;
        for (int i = 0; i < playlist.size(); i++) {
            PlaylistSyncPacket.Entry entry = playlist.get(i);
            if (entry.videoId().equals(videoId)) {
                removeIndex = i;
                break;
            }
        }

        if (removeIndex == -1) {
            return;
        }
        pushQueueUndoSnapshot("remove");

        playlist.remove(removeIndex);
        Mineify.LOGGER.info("{} removed {} from queue", player.getName().getString(), videoId);
        companionClient.deleteDownload(videoId);

        if (currentIndex >= 0) {
            if (removeIndex < currentIndex) {
                currentIndex--;
            } else if (removeIndex == currentIndex) {
                playbackRequestNonce++;
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

        String actionLower = action.toLowerCase(Locale.ROOT);
        if (actionLower.startsWith("ready:")) {
            String videoId = action.substring("ready:".length()).trim();
            if (!videoId.isBlank()) {
                handleClientTrackReady(player, videoId);
            }
            return;
        }

        boolean controlsLocked = waitingReadyVideoId != null && !waitingReadyPlayers.isEmpty();
        if (controlsLocked) {
            if (actionLower.equals("pause")
                    || actionLower.equals("resume")
                    || actionLower.equals("replay")
                    || actionLower.startsWith("seek:")) {
                return;
            }
        }

        boolean legacyPlaybackAllowed = hasLegacyPlaybackControl(player);

        switch (actionLower) {
            case "skip" -> {
                if (!hasPerm(player, PERM_PLAYBACK_SKIP, legacyPlaybackAllowed)) {
                    player.sendMessage(net.minecraft.text.Text.literal("You need moderator permissions to skip tracks."), false);
                    return;
                }
                if (MineifyConfig.isQueueVotingEnabled()) {
                    handleVoteSkip(player);
                } else {
                    Mineify.LOGGER.info("Player {} requested skip", player.getName().getString());
                    if (isPlaying) {
                        if (advanceFuture != null) {
                            advanceFuture.cancel(false);
                        }
                        advanceAfterTrackEnd();
                    }
                }
            }
            case "replay" -> {
                if (!hasPerm(player, PERM_PLAYBACK_SEEK, legacyPlaybackAllowed)) {
                    player.sendMessage(Text.literal("You need permissions to replay track."), false);
                    return;
                }
                if (MineifyConfig.isQueueVotingEnabled()) {
                    handleVoteReplay(player);
                } else if (isPlaying) {
                    seekPlaybackTo(0L);
                }
            }
            case "pause" -> {
                if (!hasPerm(player, PERM_PLAYBACK_PAUSE, legacyPlaybackAllowed)) {
                    player.sendMessage(net.minecraft.text.Text.literal("You need moderator permissions to pause playback."), false);
                    return;
                }
                Mineify.LOGGER.info("Player {} requested pause", player.getName().getString());
                pausePlayback();
            }
            case "resume" -> {
                if (!hasPerm(player, PERM_PLAYBACK_RESUME, legacyPlaybackAllowed)) {
                    player.sendMessage(net.minecraft.text.Text.literal("You need moderator permissions to resume playback."), false);
                    return;
                }
                Mineify.LOGGER.info("Player {} requested resume", player.getName().getString());
                resumePlayback();
            }
            case "undo_queue" -> {
                if (!hasPerm(player, PERM_QUEUE_UNDO, true)) {
                    player.sendMessage(Text.literal("You don't have permission to undo queue changes."), false);
                    return;
                }
                if (!restoreLatestQueueSnapshot()) {
                    player.sendMessage(net.minecraft.text.Text.literal("Nothing to undo."), false);
                }
            }
            case "clear_queue" -> {
                if (!hasPerm(player, PERM_QUEUE_CLEAR, legacyPlaybackAllowed)) {
                    player.sendMessage(Text.literal("You don't have permission to clear queue."), false);
                    return;
                }
                clearQueue();
            }
            default -> {
                if (action.toLowerCase().startsWith("seek:")) {
                    if (!isPlaying || currentIndex < 0 || currentIndex >= playlist.size()) {
                        return;
                    }
                    if (!hasPerm(player, PERM_PLAYBACK_SEEK, legacyPlaybackAllowed)) {
                        player.sendMessage(net.minecraft.text.Text.literal("You need moderator permissions to seek playback."), false);
                        return;
                    }
                    try {
                        long requestedMs = Long.parseLong(action.substring("seek:".length()));
                        seekPlaybackTo(requestedMs);
                    } catch (NumberFormatException ignored) {
                        Mineify.LOGGER.warn("Invalid seek payload '{}'", action);
                    }
                    return;
                }
                Mineify.LOGGER.warn("Unknown playback action '{}'", action);
            }
        }
    }

    private void seekPlaybackTo(long requestedMs) {
        if (!isPlaying || currentIndex < 0 || currentIndex >= playlist.size()) {
            return;
        }
        long clampedMs = Math.max(0, Math.min(requestedMs, Math.max(0, currentTrackDurationMs)));
        long scheduledDelayMs = 0L;
        if (paused) {
            pausedElapsedMs = clampedMs;
        } else {
            playbackStartNanos = System.nanoTime() + (scheduledDelayMs * 1_000_000L) - (clampedMs * 1_000_000L);
        }

        PlaylistSyncPacket.Entry entry = playlist.get(currentIndex);
        stopStream();
        restartStreamFromOffset(entry, clampedMs);
        broadcastNowPlaying(entry.title(), clampedMs);
        scheduleAdvanceFromCurrentState();
    }

    public void handleQueueReorder(ServerPlayerEntity player, int fromIndex, int toIndex) {
        if (!hasPerm(player, PERM_QUEUE_REORDER, true)) {
            player.sendMessage(Text.literal("You don't have permission to reorder queue."), false);
            return;
        }
        int size = playlist.size();
        if (fromIndex < 0 || toIndex < 0 || fromIndex >= size || toIndex >= size || fromIndex == toIndex) {
            return;
        }
        if (isPlaying && toIndex == 0) {
            return;
        }
        pushQueueUndoSnapshot("reorder");

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
        if (!hasPerm(player, PERM_PLAYLISTS_VIEW, true)) {
            player.sendMessage(Text.literal("You don't have permission to view playlists."), false);
            return;
        }
        syncUserPlaylistsToPlayer(player);
    }

    public void handleRequestProfiles(ServerPlayerEntity player, String query) {
        if (!hasPerm(player, PERM_PLAYLISTS_VIEW, true)) {
            player.sendMessage(Text.literal("You don't have permission to view playlists."), false);
            return;
        }
        String normalizedQuery = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        List<ProfilesSyncPacket.ProfileEntry> entries = new ArrayList<>();
        String selfId = player.getUuidAsString();
        java.util.Set<String> requesterLikedRefs = getLikedRefsFor(selfId);

        List<UserPlaylist> selfPlaylists = userPlaylistsByOwner.getOrDefault(selfId, List.of());
        entries.add(new ProfilesSyncPacket.ProfileEntry(
                selfId,
                player.getName().getString(),
                true,
                toProfilePlaylistEntries(selfPlaylists, true, requesterLikedRefs),
                toProfileLikedPlaylistEntries(selfId, requesterLikedRefs)
        ));

        for (Map.Entry<String, List<UserPlaylist>> entry : userPlaylistsByOwner.entrySet()) {
            String ownerId = entry.getKey();
            if (ownerId.equals(selfId)) {
                continue;
            }

            List<UserPlaylist> ownerPlaylists = entry.getValue();
            String ownerName = resolveOwnerName(ownerId, ownerPlaylists);
            if (!normalizedQuery.isEmpty() && !ownerName.toLowerCase(Locale.ROOT).contains(normalizedQuery)) {
                continue;
            }

            List<ProfilesSyncPacket.PlaylistEntry> publicPlaylists = toProfilePlaylistEntries(ownerPlaylists, false, requesterLikedRefs);
            List<ProfilesSyncPacket.PlaylistEntry> likedPlaylists = toProfileLikedPlaylistEntries(ownerId, requesterLikedRefs);
            entries.add(new ProfilesSyncPacket.ProfileEntry(ownerId, ownerName, false, publicPlaylists, likedPlaylists));
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
        if (!hasPerm(player, PERM_SPOTIFY_IMPORT, true)) {
            player.sendMessage(Text.literal("You don't have permission to import Spotify playlists."), false);
            return;
        }
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

        companionClient.getSpotifyPlaylistTracks(spotifyUrl, playerId).thenAccept(playlistData -> {
            server.execute(() -> {
                if (playlistData.authRequired()) {
                    sendSpotifyAuthPrompt(player, playlistData.authUrl(), playlistData.error());
                    return;
                }
                if (playlistData.tracks().isEmpty()) {
                    String reason = playlistData.error() == null || playlistData.error().isBlank()
                            ? "Spotify import failed: no tracks found."
                            : "Spotify import failed: " + playlistData.error();
                    player.sendMessage(Text.literal(reason), false);
                    return;
                }

                int maxTracks = Math.max(1, MineifyConfig.getSpotifyImportMaxTracksPerImport());
                List<CompanionClient.SpotifyTrack> sourceTracks = playlistData.tracks();
                List<CompanionClient.SpotifyTrack> limitedTracks = sourceTracks.size() > maxTracks
                        ? new ArrayList<>(sourceTracks.subList(0, maxTracks))
                        : new ArrayList<>(sourceTracks);
                SpotifyImportSession session = new SpotifyImportSession(playerId, targetPlaylistId, limitedTracks);
                spotifyImportSessions.put(playerId, session);
                processSpotifyImportNext(player);
            });
        });
    }

    public void handleRequestSpotifyImportPreview(ServerPlayerEntity player, String spotifyUrl) {
        if (!hasPerm(player, PERM_SPOTIFY_IMPORT, true)) {
            player.sendMessage(Text.literal("You don't have permission to import Spotify playlists."), false);
            return;
        }
        if (!MineifyConfig.isSpotifyImportEnabled()) {
            player.sendMessage(net.minecraft.text.Text.literal("Spotify import is disabled by server config."), false);
            return;
        }
        if (spotifyUrl == null || spotifyUrl.isBlank()) {
            player.sendMessage(net.minecraft.text.Text.literal("Spotify link is empty."), false);
            return;
        }

        String playerId = player.getUuidAsString();
        spotifyImportPreviewSessions.remove(playerId);

        companionClient.getSpotifyPlaylistTracks(spotifyUrl, playerId).thenAccept(playlistData -> {
            server.execute(() -> {
                if (playlistData.authRequired()) {
                    sendSpotifyAuthPrompt(player, playlistData.authUrl(), playlistData.error());
                    return;
                }
                if (playlistData.tracks().isEmpty()) {
                    String reason = playlistData.error() == null || playlistData.error().isBlank()
                            ? "Spotify import failed: no tracks found."
                            : "Spotify import failed: " + playlistData.error();
                    player.sendMessage(Text.literal(reason), false);
                    return;
                }

                int maxTracks = Math.max(1, MineifyConfig.getSpotifyImportPreviewMaxTracks());
                List<CompanionClient.SpotifyTrack> sourceTracks = playlistData.tracks();
                List<CompanionClient.SpotifyTrack> limitedTracks = sourceTracks.size() > maxTracks
                        ? new ArrayList<>(sourceTracks.subList(0, maxTracks))
                        : new ArrayList<>(sourceTracks);

                Map<String, CompanionClient.SpotifyTrack> byId = new HashMap<>();
                List<SpotifyImportPreviewPacket.TrackEntry> previewTracks = new ArrayList<>(limitedTracks.size());
                for (int i = 0; i < limitedTracks.size(); i++) {
                    CompanionClient.SpotifyTrack track = limitedTracks.get(i);
                    String trackId = normalizedSpotifyTrackId(track, i);
                    byId.put(trackId, track);
                    previewTracks.add(new SpotifyImportPreviewPacket.TrackEntry(
                            trackId,
                            track.title(),
                            track.artist(),
                            track.query(),
                            track.duration()
                    ));
                }

                SpotifyImportPreviewSession session = new SpotifyImportPreviewSession(
                        playlistData.playlistId(),
                        playlistData.playlistName(),
                        playlistData.ownerDisplayName(),
                        byId
                );
                spotifyImportPreviewSessions.put(playerId, session);
                ServerPlayNetworking.send(player, new SpotifyImportPreviewPacket(
                        blankToFallback(playlistData.playlistId(), "spotify-preview"),
                        blankToFallback(playlistData.playlistName(), "Imported Playlist"),
                        blankToFallback(playlistData.ownerDisplayName(), "Spotify User"),
                        previewTracks
                ));
            });
        });
    }

    private void sendSpotifyAuthPrompt(ServerPlayerEntity player, String authUrl, String reason) {
        String link = (authUrl == null || authUrl.isBlank())
                ? companionClient.getSpotifyAuthStartUrl(player.getUuidAsString())
                : authUrl;
        player.sendMessage(Text.literal("Spotify needs authorization before importing playlists."), false);
        if (reason != null && !reason.isBlank()) {
            player.sendMessage(Text.literal("Reason: " + reason), false);
        }
        Text clickable = Text.literal("[Connect Spotify]")
                .setStyle(Style.EMPTY
                        .withColor(Formatting.AQUA)
                        .withUnderline(true)
                        .withClickEvent(new ClickEvent.OpenUrl(URI.create(link))));
        player.sendMessage(clickable, false);
        player.sendMessage(Text.literal("Click the link, sign in, then retry the import."), false);
    }

    public void handleConfirmSpotifyImport(
            ServerPlayerEntity player,
            String mineifyPlaylistName,
            boolean isPublic,
            List<String> selectedSpotifyTrackIds
    ) {
        if (!hasPerm(player, PERM_SPOTIFY_IMPORT, true)) {
            player.sendMessage(Text.literal("You don't have permission to import Spotify playlists."), false);
            return;
        }
        if (!MineifyConfig.isSpotifyImportEnabled()) {
            player.sendMessage(net.minecraft.text.Text.literal("Spotify import is disabled by server config."), false);
            return;
        }
        String playerId = player.getUuidAsString();
        SpotifyImportPreviewSession preview = spotifyImportPreviewSessions.remove(playerId);
        if (preview == null) {
            player.sendMessage(net.minecraft.text.Text.literal("Spotify preview expired. Request preview again."), false);
            return;
        }

        List<String> selectedIds = selectedSpotifyTrackIds == null ? List.of() : selectedSpotifyTrackIds;
        List<CompanionClient.SpotifyTrack> selectedTracks = new ArrayList<>();
        for (String selectedId : selectedIds) {
            CompanionClient.SpotifyTrack track = preview.tracksById.get(selectedId);
            if (track != null) {
                selectedTracks.add(track);
            }
        }

        if (selectedTracks.isEmpty()) {
            player.sendMessage(net.minecraft.text.Text.literal("Spotify import canceled: no tracks selected."), false);
            return;
        }

        String playlistName = mineifyPlaylistName == null ? "" : mineifyPlaylistName.trim();
        if (playlistName.isEmpty()) {
            playlistName = blankToFallback(preview.playlistName, "Imported Playlist");
        }
        if (playlistName.length() > MineifyConfig.getPlaylistsMaxNameLength()) {
            playlistName = playlistName.substring(0, MineifyConfig.getPlaylistsMaxNameLength());
        }

        List<UserPlaylist> userPlaylists = userPlaylistsByOwner.computeIfAbsent(playerId, key -> new ArrayList<>());
        if (userPlaylists.size() >= MineifyConfig.getPlaylistsMaxPerUser()) {
            player.sendMessage(net.minecraft.text.Text.literal("You reached the max playlists limit (" + MineifyConfig.getPlaylistsMaxPerUser() + ")."), false);
            return;
        }

        UserPlaylist playlistModel = new UserPlaylist(
                UUID.randomUUID().toString(),
                playerId,
                player.getName().getString(),
                playlistName,
                isPublic
        );
        userPlaylists.add(playlistModel);
        saveUserPlaylists();
        syncUserPlaylistsToPlayer(player);
        syncProfilesForAll();

        int maxTracks = Math.max(1, MineifyConfig.getSpotifyImportMaxTracksPerImport());
        List<CompanionClient.SpotifyTrack> limitedTracks = selectedTracks.size() > maxTracks
                ? new ArrayList<>(selectedTracks.subList(0, maxTracks))
                : selectedTracks;
        spotifyImportSessions.put(playerId, new SpotifyImportSession(playerId, playlistModel.id, limitedTracks));
        processSpotifyImportNext(player);
    }

    public void handleToggleLikedPlaylist(ServerPlayerEntity player, String playlistId, boolean liked) {
        if (!hasPerm(player, PERM_PLAYLISTS_LIKE, true)) {
            player.sendMessage(Text.literal("You don't have permission to like playlists."), false);
            return;
        }
        if (!MineifyConfig.isPlaylistsLikesEnabled() || playlistId == null || playlistId.isBlank()) {
            return;
        }
        UserPlaylist playlistModel = findPlaylistById(playlistId);
        if (playlistModel == null) {
            return;
        }
        if (!playlistModel.isPublic && !playlistModel.ownerId.equals(player.getUuidAsString())) {
            return;
        }

        java.util.Set<String> likedRefs = likedPlaylistRefsByUser.computeIfAbsent(player.getUuidAsString(), key -> new java.util.HashSet<>());
        if (liked) {
            likedRefs.add(playlistId);
        } else {
            likedRefs.remove(playlistId);
            if (likedRefs.isEmpty()) {
                likedPlaylistRefsByUser.remove(player.getUuidAsString());
            }
        }
        savePlaylistLikes();
        syncProfilesForAll();
    }

    public void handleResolveSpotifyImportChoice(ServerPlayerEntity player, String videoId) {
        if (!hasPerm(player, PERM_SPOTIFY_IMPORT, true)) {
            player.sendMessage(Text.literal("You don't have permission to import Spotify playlists."), false);
            return;
        }
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
        if (!hasPerm(player, PERM_RECENTLY_PLAYED_VIEW, true)) {
            player.sendMessage(Text.literal("You don't have permission to view recently played songs."), false);
            return;
        }
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
        if (!hasPerm(player, PERM_PLAYLISTS_CREATE, true)) {
            player.sendMessage(Text.literal("You don't have permission to create playlists."), false);
            return;
        }
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
        if (addInitialTrack && videoId != null && !videoId.isBlank() && parseDuration(duration) <= MAX_TRACK_DURATION_MS) {
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
        if (!hasPerm(player, PERM_PLAYLISTS_ADD_TRACK, true)) {
            player.sendMessage(Text.literal("You don't have permission to add tracks to playlists."), false);
            return;
        }
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
            syncProfilesForAll();
        }
    }

    public void syncToPlayer(ServerPlayerEntity player) {
        ServerPlayNetworking.send(player, new PlaylistSyncPacket(new ArrayList<>(playlist)));
        if (isPlaying && currentIndex >= 0 && currentIndex < playlist.size()) {
            PlaylistSyncPacket.Entry entry = playlist.get(currentIndex);
            long elapsedMs = getElapsedPlaybackMs();
            ServerPlayNetworking.send(player, buildNowPlayingPacket(entry.title(), elapsedMs));
            if (currentDownloadUrl != null) {
                streamToPlayerFromOffset(player, entry, elapsedMs);
            }

            ServerPlayNetworking.send(player, new PlaybackStatePacket(paused));
        }
        boolean locked = waitingReadyVideoId != null && !waitingReadyPlayers.isEmpty();
        ServerPlayNetworking.send(player, new PlaybackLockPacket(locked));
        syncUserPlaylistsToPlayer(player);
        syncRecentlyPlayedToPlayer(player);
    }

    private void playNext() {
        playbackRequestNonce++;
        clearClientReadyWait();
        clearVotes();
        stopStream();
        long requestNonce = playbackRequestNonce;
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
            broadcastPlaybackLock(false);
            return;
        }

        PlaylistSyncPacket.Entry entry = playlist.get(currentIndex);
        isPlaying = true;
        paused = false;
        pausedElapsedMs = 0;
        currentTrackDurationMs = parseDuration(entry.duration());

        Mineify.LOGGER.info("Requesting download for: {} ({})", entry.title(), entry.videoId());
        long downloadStartNs = System.nanoTime();

        companionClient.requestDownload(entry.videoId()).thenAccept(downloadUrl -> {
            long downloadElapsedMs = Math.max(0L, (System.nanoTime() - downloadStartNs) / 1_000_000L);
            metricsDownloadCount++;
            metricsDownloadTotalMs += downloadElapsedMs;
            if (downloadUrl == null) {
                Mineify.LOGGER.error("Download failed for: {}", entry.title());
                server.execute(() -> {
                    if (requestNonce == playbackRequestNonce) {
                        playNext();
                    }
                });
                return;
            }

            server.execute(() -> {
                if (requestNonce != playbackRequestNonce || currentIndex < 0 || currentIndex >= playlist.size()) {
                    return;
                }
                currentDownloadUrl = downloadUrl;
                playbackStartNanos = 0L;
                prepareStream(entry, downloadUrl, requestNonce);
            });
        });
    }

    private void handleClientTrackReady(ServerPlayerEntity player, String videoId) {
        if (waitingReadyNonce != playbackRequestNonce || waitingReadyVideoId == null) {
            return;
        }
        if (!waitingReadyVideoId.equals(videoId)) {
            return;
        }
        if (!waitingReadyPlayers.remove(player.getUuidAsString())) {
            return;
        }
        if (!waitingReadyPlayers.isEmpty()) {
            return;
        }
        if (currentIndex < 0 || currentIndex >= playlist.size()) {
            clearClientReadyWait();
            return;
        }
        startCurrentTrackPlayback(playlist.get(currentIndex));
    }

    private void startCurrentTrackPlayback(PlaylistSyncPacket.Entry entry) {
        clearClientReadyWait();
        clearVotes();
        if (waitingReadyStartedAtNs > 0L) {
            long waitMs = Math.max(0L, (System.nanoTime() - waitingReadyStartedAtNs) / 1_000_000L);
            metricsReadyCount++;
            metricsReadyTotalMs += waitMs;
            waitingReadyStartedAtNs = 0L;
        }
        long startDelayMs = Math.max(50, MineifyConfig.getPlaybackPreloadBufferMs());
        playbackStartNanos = System.nanoTime() + (startDelayMs * 1_000_000L);
        paused = false;
        pausedElapsedMs = 0;
        recordRecentlyPlayed(entry);
        if (MineifyConfig.isNowPlayingChatCardsEnabled()) {
            broadcastNowPlayingCard(entry);
        }
        beginStreaming(entry, 0L, startDelayMs, false);
        broadcastPlaybackLock(false);
        broadcastPlaybackState(false);
        broadcastNowPlaying(entry.title(), 0L);
        scheduleAdvanceFromCurrentState();
    }

    private void prepareStream(PlaylistSyncPacket.Entry entry, String downloadUrl, long requestNonce) {
        streamExecutor.submit(() -> {
            StreamSession session = null;
            try {
                session = openWavStream(downloadUrl, entry.videoId(), entry.title(), 0L);
            } catch (IOException e) {
                Mineify.LOGGER.error("Failed to open WAV stream for {}", entry.title(), e);
            }
            StreamSession finalSession = session;
            server.execute(() -> {
                if (requestNonce != playbackRequestNonce || currentIndex < 0 || currentIndex >= playlist.size()) {
                    if (finalSession != null) {
                        finalSession.close();
                    }
                    return;
                }
                if (finalSession == null) {
                    Mineify.LOGGER.error("Stream prep failed for: {}", entry.title());
                    playNext();
                    return;
                }
                currentStream = finalSession;
                waitingReadyNonce = requestNonce;
                waitingReadyVideoId = entry.videoId();
                waitingReadyPlayers.clear();
                for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                    waitingReadyPlayers.add(p.getUuidAsString());
                }
                waitingReadyStartedAtNs = System.nanoTime();
                paused = false;
                pausedElapsedMs = 0;
                broadcastPlaybackLock(true);
                sendStreamStart(entry, 0L, 0L, false, true);
                if (waitingReadyPlayers.isEmpty()) {
                    startCurrentTrackPlayback(entry);
                }
            });
        });
    }

    private void streamToPlayerFromOffset(ServerPlayerEntity player, PlaylistSyncPacket.Entry entry, long offsetMs) {
        streamExecutor.submit(() -> {
            try {
                StreamSession session = openWavStream(currentDownloadUrl, entry.videoId(), entry.title(), offsetMs);
                if (session == null) {
                    return;
                }
                AudioStreamStartPacket startPacket = new AudioStreamStartPacket(
                        entry.videoId(),
                        entry.title(),
                        session.streamId,
                        session.info.sampleRate,
                        session.info.channels,
                        session.info.bitsPerSample,
                        session.info.dataSize,
                        currentTrackDurationMs,
                        offsetMs,
                        0L,
                        false,
                        true
                );
                server.execute(() -> ServerPlayNetworking.send(player, startPacket));
                streamToPlayers(session.streamId, entry, session, java.util.List.of(player));
            } catch (IOException e) {
                Mineify.LOGGER.warn("Failed to stream to player {} from offset {}", player.getName().getString(), offsetMs, e);
            }
        });
    }

    private void restartStreamFromOffset(PlaylistSyncPacket.Entry entry, long offsetMs) {
        if (currentDownloadUrl == null) {
            return;
        }
        streamExecutor.submit(() -> {
            try {
                StreamSession session = openWavStream(currentDownloadUrl, entry.videoId(), entry.title(), offsetMs);
                if (session == null) {
                    return;
                }
                server.execute(() -> {
                    currentStream = session;
                    sendStreamStart(entry, offsetMs, 0L, true);
                    streamExecutor.submit(() -> streamToPlayers(session.streamId, entry, session));
                });
            } catch (IOException e) {
                Mineify.LOGGER.warn("Failed to restart stream at {}ms for {}", offsetMs, entry.title(), e);
            }
        });
    }

    private void sendStreamStart(PlaylistSyncPacket.Entry entry, long startOffsetMs, long scheduledDelayMs, boolean serverSkipped) {
        sendStreamStart(entry, startOffsetMs, scheduledDelayMs, serverSkipped, false);
    }

    private void sendStreamStart(PlaylistSyncPacket.Entry entry, long startOffsetMs, long scheduledDelayMs, boolean serverSkipped, boolean preloadOnly) {
        StreamSession session = currentStream;
        if (session == null) {
            return;
        }
        AudioStreamStartPacket packet = new AudioStreamStartPacket(
                entry.videoId(),
                entry.title(),
                session.streamId,
                session.info.sampleRate,
                session.info.channels,
                session.info.bitsPerSample,
                session.info.dataSize,
                currentTrackDurationMs,
                startOffsetMs,
                scheduledDelayMs,
                preloadOnly,
                serverSkipped
        );
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            ServerPlayNetworking.send(p, packet);
        }
    }

    private void beginStreaming(PlaylistSyncPacket.Entry entry, long startOffsetMs, long scheduledDelayMs, boolean serverSkipped) {
        StreamSession session = currentStream;
        if (session == null) {
            return;
        }
        long streamId = session.streamId;
        long delayMs = Math.max(0L, scheduledDelayMs);
        sendStreamStart(entry, startOffsetMs, delayMs, serverSkipped);
        streamExecutor.submit(() -> streamToPlayers(streamId, entry, session));
    }

    private void streamToPlayers(long streamId, PlaylistSyncPacket.Entry entry, StreamSession session) {
        streamToPlayers(streamId, entry, session, server.getPlayerManager().getPlayerList());
    }

    private void streamToPlayers(long streamId, PlaylistSyncPacket.Entry entry, StreamSession session, List<ServerPlayerEntity> targets) {
        int sequence = 0;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = session.inputStream) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (session.streamId != streamId || session.cancelled) {
                    return;
                }
                byte[] chunk = java.util.Arrays.copyOf(buffer, read);
                AudioStreamChunkPacket packet = new AudioStreamChunkPacket(streamId, sequence++, chunk, false);
                server.execute(() -> {
                    for (ServerPlayerEntity p : targets) {
                        ServerPlayNetworking.send(p, packet);
                    }
                });
            }
        } catch (IOException e) {
            Mineify.LOGGER.error("Stream failed for {}", entry.title(), e);
        } finally {
            server.execute(() -> {
                AudioStreamEndPacket endPacket = new AudioStreamEndPacket(streamId);
                for (ServerPlayerEntity p : targets) {
                    ServerPlayNetworking.send(p, endPacket);
                }
            });
        }
    }

    private void stopStream() {
        StreamSession session = currentStream;
        if (session == null) {
            return;
        }
        currentStream = null;
        session.cancelled = true;
        session.close();
    }

    private StreamSession openWavStream(String downloadUrl, String videoId, String title, long startOffsetMs) throws IOException {
        long streamId = ++streamIdCounter;
        BufferedInputStream inputStream = new BufferedInputStream(new URL(downloadUrl).openStream());
        WavInfo info = readWavInfo(inputStream);
        if (info == null) {
            inputStream.close();
            throw new IOException("Invalid WAV header");
        }
        if (startOffsetMs > 0) {
            long bytesToSkip = Math.round(startOffsetMs * info.bytesPerMs());
            int frameSize = Math.max(1, info.blockAlign);
            bytesToSkip -= (bytesToSkip % frameSize);
            long maxSkippable = Math.max(0L, info.dataSize - frameSize);
            bytesToSkip = Math.min(bytesToSkip, maxSkippable);
            skipFully(inputStream, bytesToSkip);
        }
        return new StreamSession(streamId, videoId, title, downloadUrl, inputStream, info);
    }

    private WavInfo readWavInfo(InputStream in) throws IOException {
        String riff = readAscii(in, 4);
        if (!"RIFF".equals(riff)) {
            throw new IOException("Invalid WAV header: expected RIFF, got '" + riff + "'");
        }
        readLittleInt(in);
        String wave = readAscii(in, 4);
        if (!"WAVE".equals(wave)) {
            throw new IOException("Invalid WAV header: expected WAVE, got '" + wave + "'");
        }
        int channels = 0;
        int sampleRate = 0;
        int bitsPerSample = 16;
        int blockAlign = 0;
        int byteRate = 0;
        long dataSize = -1;
        while (true) {
            String chunkId = readAscii(in, 4);
            int chunkSize = readLittleInt(in);
            if ("fmt ".equals(chunkId)) {
                int audioFormat = readLittleShort(in);
                channels = readLittleShort(in);
                sampleRate = readLittleInt(in);
                byteRate = readLittleInt(in);
                blockAlign = readLittleShort(in);
                bitsPerSample = readLittleShort(in);
                int remaining = chunkSize - 16;
                if (remaining > 0) {
                    skipFully(in, remaining);
                }
                if (audioFormat != 1) {
                    Mineify.LOGGER.warn("Unexpected WAV encoding {} (expected PCM)", audioFormat);
                }
            } else if ("data".equals(chunkId)) {
                dataSize = Integer.toUnsignedLong(chunkSize);
                break;
            } else {
                skipFully(in, chunkSize);
            }
            if ((chunkSize & 1) != 0) {
                skipFully(in, 1);
            }
        }
        if (channels <= 0 || sampleRate <= 0 || dataSize < 0) {
            return null;
        }
        if (blockAlign <= 0) {
            blockAlign = Math.max(1, channels * Math.max(1, bitsPerSample / 8));
        }
        if (byteRate <= 0) {
            byteRate = sampleRate * blockAlign;
        }
        return new WavInfo(sampleRate, channels, bitsPerSample, blockAlign, byteRate, dataSize);
    }

    private String readAscii(InputStream in, int len) throws IOException {
        byte[] buf = in.readNBytes(len);
        if (buf.length != len) {
            throw new IOException("Unexpected EOF");
        }
        return new String(buf, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private int readLittleInt(InputStream in) throws IOException {
        byte[] buf = in.readNBytes(4);
        if (buf.length != 4) {
            throw new IOException("Unexpected EOF");
        }
        return (buf[0] & 0xFF) | ((buf[1] & 0xFF) << 8) | ((buf[2] & 0xFF) << 16) | ((buf[3] & 0xFF) << 24);
    }

    private short readLittleShort(InputStream in) throws IOException {
        byte[] buf = in.readNBytes(2);
        if (buf.length != 2) {
            throw new IOException("Unexpected EOF");
        }
        return (short) ((buf[0] & 0xFF) | ((buf[1] & 0xFF) << 8));
    }

    private void skipFully(InputStream in, long bytes) throws IOException {
        long remaining = bytes;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() == -1) {
                    throw new IOException("Unexpected EOF");
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    private void clearClientReadyWait() {
        waitingReadyNonce = -1L;
        waitingReadyVideoId = null;
        waitingReadyPlayers.clear();
        waitingReadyStartedAtNs = 0L;
    }

    public void handlePlayerDisconnect(ServerPlayerEntity player) {
        if (player == null || waitingReadyVideoId == null || waitingReadyPlayers.isEmpty()) {
            return;
        }
        if (!waitingReadyPlayers.remove(player.getUuidAsString())) {
            return;
        }
        if (!waitingReadyPlayers.isEmpty()) {
            return;
        }
        if (currentIndex < 0 || currentIndex >= playlist.size()) {
            clearClientReadyWait();
            return;
        }
        startCurrentTrackPlayback(playlist.get(currentIndex));
    }

    private void handleVoteSkip(ServerPlayerEntity player) {
        if (!isPlaying || currentIndex < 0 || currentIndex >= playlist.size()) {
            return;
        }
        String id = player.getUuidAsString();
        if (!skipVotes.add(id)) {
            player.sendMessage(Text.literal("You already voted to skip this track."), false);
            return;
        }
        int required = requiredVotes();
        broadcastSystemMessage(Text.literal(player.getName().getString() + " voted skip (" + skipVotes.size() + "/" + required + ")"));
        if (skipVotes.size() >= required) {
            skipVotes.clear();
            replayVotes.clear();
            if (advanceFuture != null) {
                advanceFuture.cancel(false);
            }
            advanceAfterTrackEnd();
        }
    }

    private void handleVoteReplay(ServerPlayerEntity player) {
        if (!isPlaying || currentIndex < 0 || currentIndex >= playlist.size()) {
            return;
        }
        String id = player.getUuidAsString();
        if (!replayVotes.add(id)) {
            player.sendMessage(Text.literal("You already voted to replay this track."), false);
            return;
        }
        int required = requiredVotes();
        broadcastSystemMessage(Text.literal(player.getName().getString() + " voted replay (" + replayVotes.size() + "/" + required + ")"));
        if (replayVotes.size() >= required) {
            replayVotes.clear();
            skipVotes.clear();
            seekPlaybackTo(0L);
        }
    }

    private int requiredVotes() {
        int online = Math.max(1, server.getPlayerManager().getPlayerList().size());
        int byThreshold = (int) Math.ceil(online * MineifyConfig.getQueueVotingThreshold());
        return Math.max(1, Math.max(MineifyConfig.getQueueVotingMinVotes(), byThreshold));
    }

    private void clearVotes() {
        skipVotes.clear();
        replayVotes.clear();
    }

    private void broadcastSystemMessage(Text text) {
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            p.sendMessage(text, false);
        }
    }

    private void advanceAfterTrackEnd() {
        playbackRequestNonce++;
        if (currentIndex >= 0 && currentIndex < playlist.size()) {
            PlaylistSyncPacket.Entry finished = playlist.remove(currentIndex);
            companionClient.deleteDownload(finished.videoId());
            currentIndex--;
            syncToAll();
        }
        stopStream();
        playNext();
    }

    private void pausePlayback() {
        if (!isPlaying || paused || currentIndex < 0 || currentIndex >= playlist.size()) {
            return;
        }

        pausedElapsedMs = getElapsedPlaybackMs();
        paused = true;
        stopStream();
        playbackRequestNonce++;
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
        playbackRequestNonce++;
        broadcastPlaybackState(false);
        broadcastNowPlaying(playlist.get(currentIndex).title(), getElapsedPlaybackMs());
        restartStreamFromOffset(playlist.get(currentIndex), pausedElapsedMs);
        scheduleAdvanceFromCurrentState();
    }

    private void scheduleAdvanceFromCurrentState() {
        cancelAdvanceSchedule();
        if (!isPlaying || paused || currentTrackDurationMs <= 0) {
            return;
        }

        long remainingUntilStartMs = Math.max(0L, (playbackStartNanos - System.nanoTime()) / 1_000_000L);
        long remainingMs = remainingUntilStartMs + Math.max(0, currentTrackDurationMs - getElapsedPlaybackMs());
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

    private void broadcastPlaybackLock(boolean locked) {
        PlaybackLockPacket packet = new PlaybackLockPacket(locked);
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ServerPlayNetworking.send(player, packet);
        }
    }

    private static class StreamSession {
        final long streamId;
        final String videoId;
        final String title;
        final String downloadUrl;
        final InputStream inputStream;
        final WavInfo info;
        volatile boolean cancelled;

        StreamSession(long streamId, String videoId, String title, String downloadUrl, InputStream inputStream, WavInfo info) {
            this.streamId = streamId;
            this.videoId = videoId;
            this.title = title;
            this.downloadUrl = downloadUrl;
            this.inputStream = inputStream;
            this.info = info;
        }

        void close() {
            try {
                inputStream.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static class WavInfo {
        final int sampleRate;
        final int channels;
        final int bitsPerSample;
        final int blockAlign;
        final int byteRate;
        final long dataSize;

        WavInfo(int sampleRate, int channels, int bitsPerSample, int blockAlign, int byteRate, long dataSize) {
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.bitsPerSample = bitsPerSample;
            this.blockAlign = Math.max(1, blockAlign);
            this.byteRate = Math.max(1, byteRate);
            this.dataSize = dataSize;
        }

        double bytesPerMs() {
            return byteRate / 1000.0;
        }
    }

    private void maybeLogMetrics() {
        if (!MineifyConfig.isMetricsEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (metricsNextLogAtMs <= 0L) {
            metricsNextLogAtMs = now + (MineifyConfig.getMetricsLogIntervalSeconds() * 1000L);
            return;
        }
        if (now < metricsNextLogAtMs) {
            return;
        }
        metricsNextLogAtMs = now + (MineifyConfig.getMetricsLogIntervalSeconds() * 1000L);
        double avgDownload = metricsDownloadCount > 0 ? (metricsDownloadTotalMs / (double) metricsDownloadCount) : 0d;
        double avgReady = metricsReadyCount > 0 ? (metricsReadyTotalMs / (double) metricsReadyCount) : 0d;
        Mineify.LOGGER.info("Mineify metrics: avgDownloadMs={}, avgReadyWaitMs={}, samplesDownload={}, samplesReady={}",
                String.format(Locale.ROOT, "%.1f", avgDownload),
                String.format(Locale.ROOT, "%.1f", avgReady),
                metricsDownloadCount,
                metricsReadyCount);
    }

    private void broadcastNowPlayingCard(PlaylistSyncPacket.Entry entry) {
        String title = blankToFallback(entry.title(), "Unknown Track");
        String addedBy = blankToFallback(entry.addedBy(), "Unknown");
        Text line = Text.literal("♪ Now playing: ").formatted(Formatting.GREEN)
                .append(Text.literal(title).formatted(Formatting.WHITE))
                .append(Text.literal("  •  added by " + addedBy).formatted(Formatting.GRAY));
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            player.sendMessage(line, false);
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
            syncProfilesForAll();
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

    private UserPlaylist findPlaylistById(String playlistId) {
        for (List<UserPlaylist> playlists : userPlaylistsByOwner.values()) {
            for (UserPlaylist playlistModel : playlists) {
                if (playlistModel.id.equals(playlistId)) {
                    return playlistModel;
                }
            }
        }
        return null;
    }

    private boolean addTrackToUserPlaylistInternal(String ownerId, String playlistId, String videoId, String title, String duration) {
        UserPlaylist playlistModel = findUserPlaylist(ownerId, playlistId);
        if (playlistModel == null) {
            return false;
        }
        if (parseDuration(duration) > MAX_TRACK_DURATION_MS) {
            return false;
        }
        if (playlistModel.tracks.size() >= MineifyConfig.getPlaylistsMaxTracksPerPlaylist()) {
            return false;
        }
        playlistModel.tracks.add(new UserPlaylistTrack(videoId, title, duration));
        return true;
    }

    private List<ProfilesSyncPacket.PlaylistEntry> toProfilePlaylistEntries(
            List<UserPlaylist> playlists,
            boolean includePrivate,
            java.util.Set<String> requesterLikedRefs
    ) {
        List<ProfilesSyncPacket.PlaylistEntry> entries = new ArrayList<>();
        for (UserPlaylist playlistModel : playlists) {
            if (!includePrivate && !playlistModel.isPublic) {
                continue;
            }
            List<ProfilesSyncPacket.TrackEntry> tracks = new ArrayList<>(playlistModel.tracks.size());
            for (UserPlaylistTrack track : playlistModel.tracks) {
                tracks.add(new ProfilesSyncPacket.TrackEntry(
                        blankToFallback(track.videoId, ""),
                        blankToFallback(track.title, "Unknown track"),
                        blankToFallback(track.duration, "")
                ));
            }
            entries.add(new ProfilesSyncPacket.PlaylistEntry(
                    playlistModel.id,
                    playlistModel.name,
                    playlistModel.isPublic,
                    playlistModel.tracks.size(),
                    blankToFallback(playlistModel.ownerId, ""),
                    blankToFallback(playlistModel.ownerName, resolveOwnerName(playlistModel.ownerId, List.of())),
                    requesterLikedRefs.contains(playlistModel.id),
                    tracks
            ));
        }
        return entries;
    }

    private List<ProfilesSyncPacket.PlaylistEntry> toProfileLikedPlaylistEntries(
            String profileOwnerId,
            java.util.Set<String> requesterLikedRefs
    ) {
        if (!MineifyConfig.isPlaylistsLikesEnabled()) {
            return List.of();
        }
        java.util.Set<String> refs = likedPlaylistRefsByUser.get(profileOwnerId);
        if (refs == null || refs.isEmpty()) {
            return List.of();
        }
        List<ProfilesSyncPacket.PlaylistEntry> entries = new ArrayList<>();
        for (String likedId : refs) {
            UserPlaylist likedPlaylist = findPlaylistById(likedId);
            if (likedPlaylist == null || !likedPlaylist.isPublic) {
                continue;
            }
            entries.addAll(toProfilePlaylistEntries(List.of(likedPlaylist), true, requesterLikedRefs));
        }
        return entries;
    }

    private java.util.Set<String> getLikedRefsFor(String ownerId) {
        if (!MineifyConfig.isPlaylistsLikesEnabled()) {
            return java.util.Set.of();
        }
        java.util.Set<String> refs = likedPlaylistRefsByUser.get(ownerId);
        return refs == null ? java.util.Set.of() : refs;
    }

    private String normalizedSpotifyTrackId(CompanionClient.SpotifyTrack track, int fallbackIndex) {
        if (track.spotifyTrackId() != null && !track.spotifyTrackId().isBlank()) {
            return track.spotifyTrackId();
        }
        String base = normalizeText(track.title() + "-" + track.artist());
        if (base.isBlank()) {
            base = "track";
        }
        return base + "-" + fallbackIndex;
    }

    private String blankToFallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
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

    private void syncProfilesForAll() {
        for (ServerPlayerEntity onlinePlayer : server.getPlayerManager().getPlayerList()) {
            handleRequestProfiles(onlinePlayer, "");
        }
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

    private void loadPlaylistLikes() {
        likedPlaylistRefsByUser.clear();
        if (!MineifyConfig.isPlaylistsLikesEnabled()) {
            return;
        }
        if (!Files.exists(playlistLikesFile)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(playlistLikesFile)) {
            Type type = new TypeToken<Map<String, java.util.Set<String>>>() {
            }.getType();
            Map<String, java.util.Set<String>> loaded = gson.fromJson(reader, type);
            if (loaded != null) {
                for (Map.Entry<String, java.util.Set<String>> entry : loaded.entrySet()) {
                    if (entry.getValue() == null || entry.getValue().isEmpty()) {
                        continue;
                    }
                    likedPlaylistRefsByUser.put(entry.getKey(), new java.util.HashSet<>(entry.getValue()));
                }
            }
            Mineify.LOGGER.info("Loaded {} liked playlist owners", likedPlaylistRefsByUser.size());
        } catch (IOException e) {
            Mineify.LOGGER.error("Failed to load liked playlists from {}", playlistLikesFile, e);
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

    private void savePlaylistLikes() {
        if (!MineifyConfig.isPlaylistsLikesEnabled()) {
            return;
        }
        try {
            Files.createDirectories(playlistLikesFile.getParent());
            try (Writer writer = Files.newBufferedWriter(playlistLikesFile)) {
                gson.toJson(likedPlaylistRefsByUser, writer);
            }
        } catch (IOException e) {
            Mineify.LOGGER.error("Failed to save liked playlists to {}", playlistLikesFile, e);
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
        if (playbackStartNanos > System.nanoTime()) {
            return 0;
        }
        return Math.max(0, (System.nanoTime() - playbackStartNanos) / 1_000_000L);
    }

    public void shutdown() {
        cancelAdvanceSchedule();
        clearClientReadyWait();
        if (progressFuture != null) {
            progressFuture.cancel(true);
        }
        stopStream();
        saveUserPlaylists();
        savePlaylistLikes();
        saveRecentlyPlayed();
        spotifyImportSessions.clear();
        spotifyImportPreviewSessions.clear();
        scheduler.shutdownNow();
        streamExecutor.shutdownNow();
        playlist.clear();
        Mineify.LOGGER.info("Mineify: Playlist manager shut down");
    }

    public PlaylistStatus getStatus() {
        String title = "";
        if (isPlaying && currentIndex >= 0 && currentIndex < playlist.size()) {
            title = playlist.get(currentIndex).title();
        }
        boolean waiting = waitingReadyVideoId != null && !waitingReadyPlayers.isEmpty();
        return new PlaylistStatus(isPlaying, paused, title, playlist.size(), waiting);
    }

    private boolean hasPerm(ServerPlayerEntity player, String node, boolean fallback) {
        if (!MineifyConfig.isPermissionsEnabled()) {
            return true;
        }
        Method method = resolvePermissionsCheckMethod();
        if (method == null) {
            return fallback;
        }
        try {
            Object value = method.invoke(null, player, node, fallback);
            if (value instanceof Boolean b) {
                return b;
            }
        } catch (IllegalArgumentException ignored) {
            try {
                Object value = method.invoke(null, player.getCommandSource(), node, fallback);
                if (value instanceof Boolean b) {
                    return b;
                }
            } catch (ReflectiveOperationException ignoredAgain) {
                return fallback;
            }
        } catch (ReflectiveOperationException ignored) {
            return fallback;
        }
        return fallback;
    }

    private static Method resolvePermissionsCheckMethod() {
        if (permissionsLookupDone) {
            return permissionsCheckMethod;
        }
        synchronized (PlaylistManager.class) {
            if (permissionsLookupDone) {
                return permissionsCheckMethod;
            }
            try {
                Class<?> clazz = Class.forName("me.lucko.fabric.api.permissions.v0.Permissions");
                for (Method method : clazz.getMethods()) {
                    if (!"check".equals(method.getName()) || method.getParameterCount() != 3) {
                        continue;
                    }
                    Class<?>[] params = method.getParameterTypes();
                    if (params[1] == String.class && (params[2] == boolean.class || params[2] == Boolean.class)) {
                        permissionsCheckMethod = method;
                        break;
                    }
                }
            } catch (ClassNotFoundException ignored) {
                permissionsCheckMethod = null;
            } finally {
                permissionsLookupDone = true;
            }
            return permissionsCheckMethod;
        }
    }

    private boolean hasLegacyPlaybackControl(ServerPlayerEntity player) {
        if (!MineifyConfig.isModerationRequireOpForGlobalQueueControls()) {
            return true;
        }
        if (!isPlaying || currentIndex < 0 || currentIndex >= playlist.size()) {
            return false;
        }
        return playlist.get(currentIndex).addedBy().equals(player.getName().getString());
    }

    private void clearQueue() {
        if (playlist.isEmpty() && !isPlaying) {
            return;
        }
        pushQueueUndoSnapshot("clear");
        playbackRequestNonce++;
        clearClientReadyWait();
        clearVotes();
        stopStream();
        playlist.clear();
        cancelAdvanceSchedule();
        isPlaying = false;
        paused = false;
        pausedElapsedMs = 0;
        currentIndex = -1;
        currentDownloadUrl = null;
        playbackStartNanos = 0;
        currentTrackDurationMs = 0;
        syncToAll();
        broadcastNowPlaying("", 0);
        broadcastPlaybackState(false);
        broadcastPlaybackLock(false);
    }

    private static class QueueSnapshot {
        final List<PlaylistSyncPacket.Entry> entries;
        final String nowPlayingVideoId;
        final String reason;

        QueueSnapshot(List<PlaylistSyncPacket.Entry> entries, String nowPlayingVideoId, String reason) {
            this.entries = entries;
            this.nowPlayingVideoId = nowPlayingVideoId;
            this.reason = reason;
        }
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

    private static class SpotifyImportPreviewSession {
        final String playlistId;
        final String playlistName;
        final String ownerDisplayName;
        final Map<String, CompanionClient.SpotifyTrack> tracksById;

        SpotifyImportPreviewSession(
                String playlistId,
                String playlistName,
                String ownerDisplayName,
                Map<String, CompanionClient.SpotifyTrack> tracksById
        ) {
            this.playlistId = playlistId;
            this.playlistName = playlistName;
            this.ownerDisplayName = ownerDisplayName;
            this.tracksById = tracksById;
        }
    }
}
