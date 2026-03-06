package com.mineify;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class MineifyConfig {
    private static String companionUrl = "http://localhost:3001";
    private static int maxPlaylistSize = 50;
    private static String audioSessionFolder = "./mineify-sessions";
    private static int playbackTrackEndPaddingMs = 2000;
    private static int playbackProgressBroadcastIntervalMs = 1000;
    private static int playbackCrossfadeMs = 180;
    private static int playbackPreloadBufferMs = 200;
    private static int playbackPrefetchCount = 2;
    private static int queueUndoMaxHistory = 20;
    private static boolean moderationRequireOpForGlobalQueueControls = true;
    private static boolean queueVotingEnabled = false;
    private static int queueVotingMinVotes = 2;
    private static double queueVotingThreshold = 0.6d;
    private static boolean announcementsNowPlayingChatCards = true;
    private static boolean uiCompactDefault = false;
    private static boolean recentlyPlayedEnabled = true;
    private static int recentlyPlayedMaxEntries = 200;
    private static int recentlyPlayedUiMaxVisibleEntries = 20;
    private static String recentlyPlayedPersistPath = "MineifyCompanion/recently_played.json";
    private static boolean recentlyPlayedBroadcastOnUpdate = true;
    private static boolean spotifyImportEnabled = true;
    private static boolean spotifyImportRequireClientCredentials = true;
    private static double spotifyImportAutoMatchThreshold = 0.72d;
    private static int spotifyImportMaxTracksPerImport = 300;
    private static int spotifyImportPreviewMaxTracks = 300;
    private static int playlistsMaxPerUser = 25;
    private static int playlistsMaxTracksPerPlaylist = 500;
    private static int playlistsMaxNameLength = 50;
    private static boolean playlistsDefaultPublic = true;
    private static boolean playlistsEnableLikes = true;
    private static boolean permissionsEnabled = false;

    static {
        load();
    }

    private static void load() {
        Path configPath = Path.of("config", "mineify.json");
        if (Files.exists(configPath)) {
            try {
                String json = Files.readString(configPath);
                Gson gson = new Gson();
                JsonObject obj = gson.fromJson(json, JsonObject.class);
                if (obj.has("companionServiceUrl")) {
                    companionUrl = obj.get("companionServiceUrl").getAsString();
                }
                if (obj.has("maxPlaylistSize")) {
                    maxPlaylistSize = obj.get("maxPlaylistSize").getAsInt();
                }
                if (obj.has("audioSessionFolder")) {
                    audioSessionFolder = obj.get("audioSessionFolder").getAsString();
                }
                JsonObject playbackObj = obj.has("playback") && obj.get("playback").isJsonObject()
                        ? obj.getAsJsonObject("playback")
                        : null;
                if (playbackObj != null) {
                    if (playbackObj.has("trackEndPaddingMs")) {
                        playbackTrackEndPaddingMs = Math.max(0, playbackObj.get("trackEndPaddingMs").getAsInt());
                    }
                    if (playbackObj.has("progressBroadcastIntervalMs")) {
                        playbackProgressBroadcastIntervalMs = Math.max(250, playbackObj.get("progressBroadcastIntervalMs").getAsInt());
                    }
                    if (playbackObj.has("crossfadeMs")) {
                        playbackCrossfadeMs = Math.max(0, playbackObj.get("crossfadeMs").getAsInt());
                    }
                    if (playbackObj.has("preloadBufferMs")) {
                        playbackPreloadBufferMs = Math.max(0, playbackObj.get("preloadBufferMs").getAsInt());
                    }
                    if (playbackObj.has("queueUndoMaxHistory")) {
                        queueUndoMaxHistory = Math.max(1, playbackObj.get("queueUndoMaxHistory").getAsInt());
                    }
                    if (playbackObj.has("prefetchCount")) {
                        playbackPrefetchCount = Math.max(0, playbackObj.get("prefetchCount").getAsInt());
                    }
                    if (playbackObj.has("moderationRequireOpForGlobalQueueControls")) {
                        moderationRequireOpForGlobalQueueControls = playbackObj.get("moderationRequireOpForGlobalQueueControls").getAsBoolean();
                    }
                    if (playbackObj.has("queueVotingEnabled")) {
                        queueVotingEnabled = playbackObj.get("queueVotingEnabled").getAsBoolean();
                    }
                    if (playbackObj.has("queueVotingMinVotes")) {
                        queueVotingMinVotes = Math.max(1, playbackObj.get("queueVotingMinVotes").getAsInt());
                    }
                    if (playbackObj.has("queueVotingThreshold")) {
                        queueVotingThreshold = Math.max(0.1d, Math.min(1.0d, playbackObj.get("queueVotingThreshold").getAsDouble()));
                    }
                }

                JsonObject recentObj = obj.has("recentlyPlayed") && obj.get("recentlyPlayed").isJsonObject()
                        ? obj.getAsJsonObject("recentlyPlayed")
                        : null;
                if (recentObj != null) {
                    if (recentObj.has("enabled")) {
                        recentlyPlayedEnabled = recentObj.get("enabled").getAsBoolean();
                    }
                    if (recentObj.has("maxEntries")) {
                        recentlyPlayedMaxEntries = Math.max(1, recentObj.get("maxEntries").getAsInt());
                    }
                    if (recentObj.has("uiMaxVisibleEntries")) {
                        recentlyPlayedUiMaxVisibleEntries = Math.max(1, recentObj.get("uiMaxVisibleEntries").getAsInt());
                    }
                    if (recentObj.has("persistPath")) {
                        recentlyPlayedPersistPath = recentObj.get("persistPath").getAsString();
                    }
                    if (recentObj.has("broadcastOnUpdate")) {
                        recentlyPlayedBroadcastOnUpdate = recentObj.get("broadcastOnUpdate").getAsBoolean();
                    }
                }

                JsonObject spotifyObj = obj.has("spotifyImport") && obj.get("spotifyImport").isJsonObject()
                        ? obj.getAsJsonObject("spotifyImport")
                        : null;
                if (spotifyObj != null) {
                    if (spotifyObj.has("enabled")) {
                        spotifyImportEnabled = spotifyObj.get("enabled").getAsBoolean();
                    }
                    if (spotifyObj.has("requireClientCredentials")) {
                        spotifyImportRequireClientCredentials = spotifyObj.get("requireClientCredentials").getAsBoolean();
                    }
                    if (spotifyObj.has("autoMatchThreshold")) {
                        spotifyImportAutoMatchThreshold = Math.max(0.0d, Math.min(1.0d, spotifyObj.get("autoMatchThreshold").getAsDouble()));
                    }
                    if (spotifyObj.has("maxTracksPerImport")) {
                        spotifyImportMaxTracksPerImport = Math.max(1, spotifyObj.get("maxTracksPerImport").getAsInt());
                    }
                    if (spotifyObj.has("previewMaxTracks")) {
                        spotifyImportPreviewMaxTracks = Math.max(1, spotifyObj.get("previewMaxTracks").getAsInt());
                    } else {
                        spotifyImportPreviewMaxTracks = spotifyImportMaxTracksPerImport;
                    }
                }

                JsonObject playlistsObj = obj.has("playlists") && obj.get("playlists").isJsonObject()
                        ? obj.getAsJsonObject("playlists")
                        : null;
                if (playlistsObj != null) {
                    if (playlistsObj.has("maxPlaylistsPerUser")) {
                        playlistsMaxPerUser = Math.max(1, playlistsObj.get("maxPlaylistsPerUser").getAsInt());
                    }
                    if (playlistsObj.has("maxTracksPerPlaylist")) {
                        playlistsMaxTracksPerPlaylist = Math.max(1, playlistsObj.get("maxTracksPerPlaylist").getAsInt());
                    }
                    if (playlistsObj.has("maxNameLength")) {
                        playlistsMaxNameLength = Math.max(4, playlistsObj.get("maxNameLength").getAsInt());
                    }
                    if (playlistsObj.has("defaultPublic")) {
                        playlistsDefaultPublic = playlistsObj.get("defaultPublic").getAsBoolean();
                    }
                    if (playlistsObj.has("enableLikes")) {
                        playlistsEnableLikes = playlistsObj.get("enableLikes").getAsBoolean();
                    }
                }

                JsonObject permissionsObj = obj.has("permissions") && obj.get("permissions").isJsonObject()
                        ? obj.getAsJsonObject("permissions")
                        : null;
                if (permissionsObj != null && permissionsObj.has("enabled")) {
                    permissionsEnabled = permissionsObj.get("enabled").getAsBoolean();
                }

                JsonObject announcementsObj = obj.has("announcements") && obj.get("announcements").isJsonObject()
                        ? obj.getAsJsonObject("announcements")
                        : null;
                if (announcementsObj != null && announcementsObj.has("nowPlayingChatCards")) {
                    announcementsNowPlayingChatCards = announcementsObj.get("nowPlayingChatCards").getAsBoolean();
                }

                JsonObject uiObj = obj.has("ui") && obj.get("ui").isJsonObject()
                        ? obj.getAsJsonObject("ui")
                        : null;
                if (uiObj != null && uiObj.has("compactDefault")) {
                    uiCompactDefault = uiObj.get("compactDefault").getAsBoolean();
                }
            } catch (IOException e) {
                Mineify.LOGGER.warn("Failed to load mineify config, using defaults", e);
            }
        }
    }

    public static String getCompanionUrl() {
        return companionUrl;
    }

    public static int getMaxPlaylistSize() {
        return maxPlaylistSize;
    }

    public static String getAudioSessionFolder() {
        return audioSessionFolder;
    }

    public static int getPlaybackTrackEndPaddingMs() {
        return playbackTrackEndPaddingMs;
    }

    public static int getPlaybackProgressBroadcastIntervalMs() {
        return playbackProgressBroadcastIntervalMs;
    }

    public static int getPlaybackCrossfadeMs() {
        return playbackCrossfadeMs;
    }

    public static int getPlaybackPreloadBufferMs() {
        return playbackPreloadBufferMs;
    }

    public static int getPlaybackPrefetchCount() {
        return playbackPrefetchCount;
    }

    public static int getQueueUndoMaxHistory() {
        return queueUndoMaxHistory;
    }

    public static boolean isModerationRequireOpForGlobalQueueControls() {
        return moderationRequireOpForGlobalQueueControls;
    }

    public static boolean isQueueVotingEnabled() {
        return queueVotingEnabled;
    }

    public static int getQueueVotingMinVotes() {
        return queueVotingMinVotes;
    }

    public static double getQueueVotingThreshold() {
        return queueVotingThreshold;
    }

    public static boolean isUiCompactDefault() {
        return uiCompactDefault;
    }

    public static boolean isRecentlyPlayedEnabled() {
        return recentlyPlayedEnabled;
    }

    public static int getRecentlyPlayedMaxEntries() {
        return recentlyPlayedMaxEntries;
    }

    public static int getRecentlyPlayedUiMaxVisibleEntries() {
        return recentlyPlayedUiMaxVisibleEntries;
    }

    public static String getRecentlyPlayedPersistPath() {
        return recentlyPlayedPersistPath;
    }

    public static boolean isRecentlyPlayedBroadcastOnUpdate() {
        return recentlyPlayedBroadcastOnUpdate;
    }

    public static boolean isSpotifyImportEnabled() {
        return spotifyImportEnabled;
    }

    public static double getSpotifyImportAutoMatchThreshold() {
        return spotifyImportAutoMatchThreshold;
    }

    public static int getSpotifyImportMaxTracksPerImport() {
        return spotifyImportMaxTracksPerImport;
    }

    public static boolean isSpotifyImportRequireClientCredentials() {
        return spotifyImportRequireClientCredentials;
    }

    public static int getSpotifyImportPreviewMaxTracks() {
        return spotifyImportPreviewMaxTracks;
    }

    public static int getPlaylistsMaxPerUser() {
        return playlistsMaxPerUser;
    }

    public static int getPlaylistsMaxTracksPerPlaylist() {
        return playlistsMaxTracksPerPlaylist;
    }

    public static int getPlaylistsMaxNameLength() {
        return playlistsMaxNameLength;
    }

    public static boolean isPlaylistsDefaultPublic() {
        return playlistsDefaultPublic;
    }

    public static boolean isPlaylistsLikesEnabled() {
        return playlistsEnableLikes;
    }

    public static boolean isPermissionsEnabled() {
        return permissionsEnabled;
    }

    public static boolean isNowPlayingChatCardsEnabled() {
        return announcementsNowPlayingChatCards;
    }
}
