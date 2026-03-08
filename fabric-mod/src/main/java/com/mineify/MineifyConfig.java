package com.mineify;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class MineifyConfig {
    private static final int CURRENT_CONFIG_VERSION = 2;
    private static int configVersion = CURRENT_CONFIG_VERSION;
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
    private static boolean metricsEnabled = false;
    private static int metricsLogIntervalSeconds = 300;

    private static void load() {
        Path tomlPath = Path.of("config", "mineify.toml");
        Path jsonPath = Path.of("config", "mineify.json");
        if (!Files.exists(tomlPath) && !Files.exists(jsonPath)) {
            writeDefaultToml(tomlPath);
        }
        if (Files.exists(tomlPath)) {
            if (loadFromToml(tomlPath)) {
                return;
            }
        }
        if (Files.exists(jsonPath)) {
            loadFromJson(jsonPath);
        }
    }

    public static void reload() {
        load();
    }

    private static void writeDefaultToml(Path tomlPath) {
        try {
            Files.createDirectories(tomlPath.getParent());
            Files.writeString(tomlPath, DEFAULT_TOML);
        } catch (IOException e) {
            Mineify.LOGGER.warn("Failed to create default mineify.toml", e);
        }
    }

    private static final String DEFAULT_TOML = String.join("\n",
            "# Mineify configuration (TOML)",
            "# Generated on first run. Edit and restart the server to apply.",
            "",
            "configVersion = " + CURRENT_CONFIG_VERSION,
            "companionServiceUrl = \"http://localhost:3001\"",
            "maxPlaylistSize = 50",
            "audioSessionFolder = \"./mineify-sessions\"",
            "",
            "[playback]",
            "trackEndPaddingMs = 2000",
            "progressBroadcastIntervalMs = 1000",
            "crossfadeMs = 180",
            "preloadBufferMs = 200",
            "prefetchCount = 2",
            "queueUndoMaxHistory = 20",
            "moderationRequireOpForGlobalQueueControls = true",
            "queueVotingEnabled = false",
            "queueVotingMinVotes = 2",
            "queueVotingThreshold = 0.6",
            "",
            "[recentlyPlayed]",
            "enabled = true",
            "maxEntries = 200",
            "uiMaxVisibleEntries = 20",
            "persistPath = \"MineifyCompanion/recently_played.json\"",
            "broadcastOnUpdate = true",
            "",
            "[spotifyImport]",
            "enabled = true",
            "requireClientCredentials = true",
            "autoMatchThreshold = 0.72",
            "maxTracksPerImport = 300",
            "previewMaxTracks = 300",
            "",
            "[playlists]",
            "maxPlaylistsPerUser = 25",
            "maxTracksPerPlaylist = 500",
            "maxNameLength = 50",
            "defaultPublic = true",
            "enableLikes = true",
            "",
            "[permissions]",
            "enabled = false",
            "",
            "[announcements]",
            "nowPlayingChatCards = true",
            "",
            "[ui]",
            "compactDefault = false",
            "",
            "[metrics]",
            "enabled = false",
            "logIntervalSeconds = 300",
            ""
    );

    static {
        load();
    }

    private static boolean loadFromToml(Path configPath) {
        try {
            TomlParseResult result = Toml.parse(configPath);
            if (result.hasErrors()) {
                Mineify.LOGGER.warn("Mineify TOML config has errors, falling back to defaults: {}", result.errors());
                return false;
            }
            configVersion = intValue(result, "configVersion", CURRENT_CONFIG_VERSION, 1);
            companionUrl = stringValue(result, "companionServiceUrl", companionUrl);
            maxPlaylistSize = intValue(result, "maxPlaylistSize", maxPlaylistSize, 1);
            audioSessionFolder = stringValue(result, "audioSessionFolder", audioSessionFolder);

            TomlTable playback = result.getTable("playback");
            if (playback != null) {
                playbackTrackEndPaddingMs = intValue(playback, "trackEndPaddingMs", playbackTrackEndPaddingMs, 0);
                playbackProgressBroadcastIntervalMs = intValue(playback, "progressBroadcastIntervalMs", playbackProgressBroadcastIntervalMs, 250);
                playbackCrossfadeMs = intValue(playback, "crossfadeMs", playbackCrossfadeMs, 0);
                playbackPreloadBufferMs = intValue(playback, "preloadBufferMs", playbackPreloadBufferMs, 0);
                queueUndoMaxHistory = intValue(playback, "queueUndoMaxHistory", queueUndoMaxHistory, 1);
                playbackPrefetchCount = intValue(playback, "prefetchCount", playbackPrefetchCount, 0);
                moderationRequireOpForGlobalQueueControls = boolValue(playback, "moderationRequireOpForGlobalQueueControls", moderationRequireOpForGlobalQueueControls);
                queueVotingEnabled = boolValue(playback, "queueVotingEnabled", queueVotingEnabled);
                queueVotingMinVotes = intValue(playback, "queueVotingMinVotes", queueVotingMinVotes, 1);
                queueVotingThreshold = doubleValue(playback, "queueVotingThreshold", queueVotingThreshold, 0.1d, 1.0d);
            }

            TomlTable recent = result.getTable("recentlyPlayed");
            if (recent != null) {
                recentlyPlayedEnabled = boolValue(recent, "enabled", recentlyPlayedEnabled);
                recentlyPlayedMaxEntries = intValue(recent, "maxEntries", recentlyPlayedMaxEntries, 1);
                recentlyPlayedUiMaxVisibleEntries = intValue(recent, "uiMaxVisibleEntries", recentlyPlayedUiMaxVisibleEntries, 1);
                recentlyPlayedPersistPath = stringValue(recent, "persistPath", recentlyPlayedPersistPath);
                recentlyPlayedBroadcastOnUpdate = boolValue(recent, "broadcastOnUpdate", recentlyPlayedBroadcastOnUpdate);
            }

            TomlTable spotify = result.getTable("spotifyImport");
            if (spotify != null) {
                spotifyImportEnabled = boolValue(spotify, "enabled", spotifyImportEnabled);
                spotifyImportRequireClientCredentials = boolValue(spotify, "requireClientCredentials", spotifyImportRequireClientCredentials);
                spotifyImportAutoMatchThreshold = doubleValue(spotify, "autoMatchThreshold", spotifyImportAutoMatchThreshold, 0.0d, 1.0d);
                spotifyImportMaxTracksPerImport = intValue(spotify, "maxTracksPerImport", spotifyImportMaxTracksPerImport, 1);
                spotifyImportPreviewMaxTracks = intValue(spotify, "previewMaxTracks", spotifyImportPreviewMaxTracks, 1);
            }

            TomlTable playlists = result.getTable("playlists");
            if (playlists != null) {
                playlistsMaxPerUser = intValue(playlists, "maxPlaylistsPerUser", playlistsMaxPerUser, 1);
                playlistsMaxTracksPerPlaylist = intValue(playlists, "maxTracksPerPlaylist", playlistsMaxTracksPerPlaylist, 1);
                playlistsMaxNameLength = intValue(playlists, "maxNameLength", playlistsMaxNameLength, 4);
                playlistsDefaultPublic = boolValue(playlists, "defaultPublic", playlistsDefaultPublic);
                playlistsEnableLikes = boolValue(playlists, "enableLikes", playlistsEnableLikes);
            }

            TomlTable permissions = result.getTable("permissions");
            if (permissions != null) {
                permissionsEnabled = boolValue(permissions, "enabled", permissionsEnabled);
            }

            TomlTable announcements = result.getTable("announcements");
            if (announcements != null) {
                announcementsNowPlayingChatCards = boolValue(announcements, "nowPlayingChatCards", announcementsNowPlayingChatCards);
            }

            TomlTable ui = result.getTable("ui");
            if (ui != null) {
                uiCompactDefault = boolValue(ui, "compactDefault", uiCompactDefault);
            }
            TomlTable metrics = result.getTable("metrics");
            if (metrics != null) {
                metricsEnabled = boolValue(metrics, "enabled", metricsEnabled);
                metricsLogIntervalSeconds = intValue(metrics, "logIntervalSeconds", metricsLogIntervalSeconds, 30);
            }

            if (configVersion < CURRENT_CONFIG_VERSION) {
                Mineify.LOGGER.info("Mineify configVersion {} detected (current {}). Defaults are used for new options.",
                        configVersion, CURRENT_CONFIG_VERSION);
                configVersion = CURRENT_CONFIG_VERSION;
            }
            return true;
        } catch (Exception e) {
            Mineify.LOGGER.warn("Failed to load mineify.toml config, falling back to defaults", e);
            return false;
        }
    }

    private static void loadFromJson(Path configPath) {
        try {
            String json = Files.readString(configPath);
            Gson gson = new Gson();
            JsonObject obj = gson.fromJson(json, JsonObject.class);
            if (obj.has("configVersion")) {
                configVersion = obj.get("configVersion").getAsInt();
            } else {
                configVersion = CURRENT_CONFIG_VERSION;
            }
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

            JsonObject metricsObj = obj.has("metrics") && obj.get("metrics").isJsonObject()
                    ? obj.getAsJsonObject("metrics")
                    : null;
            if (metricsObj != null) {
                if (metricsObj.has("enabled")) {
                    metricsEnabled = metricsObj.get("enabled").getAsBoolean();
                }
                if (metricsObj.has("logIntervalSeconds")) {
                    metricsLogIntervalSeconds = Math.max(30, metricsObj.get("logIntervalSeconds").getAsInt());
                }
            }

            if (configVersion < CURRENT_CONFIG_VERSION) {
                Mineify.LOGGER.info("Mineify configVersion {} detected (current {}). Defaults are used for new options.",
                        configVersion, CURRENT_CONFIG_VERSION);
                configVersion = CURRENT_CONFIG_VERSION;
            }
        } catch (IOException e) {
            Mineify.LOGGER.warn("Failed to load mineify config, using defaults", e);
        }
    }

    private static int intValue(TomlTable table, String key, int fallback, int min) {
        if (table.contains(key)) {
            Long value = table.getLong(key);
            if (value != null) {
                return Math.max(min, value.intValue());
            }
        }
        return fallback;
    }

    private static double doubleValue(TomlTable table, String key, double fallback, double min, double max) {
        if (table.contains(key)) {
            Double value = table.getDouble(key);
            if (value != null) {
                return Math.max(min, Math.min(max, value));
            }
        }
        return fallback;
    }

    private static boolean boolValue(TomlTable table, String key, boolean fallback) {
        if (table.contains(key)) {
            Boolean value = table.getBoolean(key);
            if (value != null) {
                return value;
            }
        }
        return fallback;
    }

    private static String stringValue(TomlTable table, String key, String fallback) {
        if (table.contains(key)) {
            String value = table.getString(key);
            if (value != null) {
                return value;
            }
        }
        return fallback;
    }

    private static int intValue(TomlParseResult table, String key, int fallback, int min) {
        if (table.contains(key)) {
            Long value = table.getLong(key);
            if (value != null) {
                return Math.max(min, value.intValue());
            }
        }
        return fallback;
    }

    private static double doubleValue(TomlParseResult table, String key, double fallback, double min, double max) {
        if (table.contains(key)) {
            Double value = table.getDouble(key);
            if (value != null) {
                return Math.max(min, Math.min(max, value));
            }
        }
        return fallback;
    }

    private static boolean boolValue(TomlParseResult table, String key, boolean fallback) {
        if (table.contains(key)) {
            Boolean value = table.getBoolean(key);
            if (value != null) {
                return value;
            }
        }
        return fallback;
    }

    private static String stringValue(TomlParseResult table, String key, String fallback) {
        if (table.contains(key)) {
            String value = table.getString(key);
            if (value != null) {
                return value;
            }
        }
        return fallback;
    }

    public static String getCompanionUrl() {
        return companionUrl;
    }

    public static int getConfigVersion() {
        return configVersion;
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

    public static boolean isMetricsEnabled() {
        return metricsEnabled;
    }

    public static int getMetricsLogIntervalSeconds() {
        return metricsLogIntervalSeconds;
    }

    public static boolean isNowPlayingChatCardsEnabled() {
        return announcementsNowPlayingChatCards;
    }
}
