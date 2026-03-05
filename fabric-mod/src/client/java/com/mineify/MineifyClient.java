package com.mineify;

import com.mineify.client.MineifyKeybinds;
import com.mineify.client.MineifyScreen;
import com.mineify.client.audio.AudioPlayer;
import com.mineify.network.packets.NowPlayingPacket;
import com.mineify.network.packets.PlayAudioPacket;
import com.mineify.network.packets.PlaybackStatePacket;
import com.mineify.network.packets.PlaylistSyncPacket;
import com.mineify.network.packets.ProfilesSyncPacket;
import com.mineify.network.packets.RecentlyPlayedSyncPacket;
import com.mineify.network.packets.SpotifyImportFinishedPacket;
import com.mineify.network.packets.SpotifyImportPreviewPacket;
import com.mineify.network.packets.SpotifyImportPromptPacket;
import com.mineify.network.packets.SearchResultsPacket;
import com.mineify.network.packets.UserPlaylistsSyncPacket;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

@Environment(EnvType.CLIENT)
public class MineifyClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("mineify-client");

    // Cached playlist state (persists when screen is closed)
    private static List<MineifyScreen.PlaylistEntry> cachedPlaylist = new ArrayList<>();
    private static String cachedNowPlaying = null;
    private static float cachedProgress = 0f;
    private static long cachedElapsedMs = 0;
    private static long cachedDurationMs = 0;
    private static boolean cachedPaused = false;
    private static List<MineifyScreen.UserPlaylistSummary> cachedUserPlaylists = new ArrayList<>();
    private static List<MineifyScreen.ProfileSummary> cachedProfiles = new ArrayList<>();
    private static List<MineifyScreen.RecentlyPlayedEntry> cachedRecentlyPlayed = new ArrayList<>();

    public static List<MineifyScreen.PlaylistEntry> getCachedPlaylist() {
        return new ArrayList<>(cachedPlaylist);
    }

    public static String getCachedNowPlaying() {
        return cachedNowPlaying;
    }

    public static float getCachedProgress() {
        return cachedProgress;
    }

    public static long getCachedElapsedMs() {
        return cachedElapsedMs;
    }

    public static long getCachedDurationMs() {
        return cachedDurationMs;
    }

    public static boolean isCachedPaused() {
        return cachedPaused;
    }

    public static List<MineifyScreen.UserPlaylistSummary> getCachedUserPlaylists() {
        return new ArrayList<>(cachedUserPlaylists);
    }

    public static List<MineifyScreen.ProfileSummary> getCachedProfiles() {
        return new ArrayList<>(cachedProfiles);
    }

    public static List<MineifyScreen.RecentlyPlayedEntry> getCachedRecentlyPlayed() {
        return new ArrayList<>(cachedRecentlyPlayed);
    }

    @Override
    public void onInitializeClient() {
        LOGGER.info("Initializing Mineify Client");

        MineifyKeybinds.register();

        // Register client-side packet handlers
        ClientPlayNetworking.registerGlobalReceiver(SearchResultsPacket.ID, (payload, context) -> {
            context.client().execute(() -> {
                if (MinecraftClient.getInstance().currentScreen instanceof MineifyScreen screen) {
                    List<MineifyScreen.SearchResult> results = new ArrayList<>();
                    for (var entry : payload.results()) {
                        results.add(new MineifyScreen.SearchResult(
                                entry.videoId(), entry.title(), entry.channel(),
                                entry.duration(), entry.thumbnail()
                        ));
                    }
                    screen.updateSearchResults(results);
                }
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(PlaylistSyncPacket.ID, (payload, context) -> {
            context.client().execute(() -> {
                // Always update the cache
                List<MineifyScreen.PlaylistEntry> entries = new ArrayList<>();
                for (var entry : payload.entries()) {
                    entries.add(new MineifyScreen.PlaylistEntry(
                            entry.videoId(), entry.title(), entry.duration(), entry.addedBy()
                    ));
                }
                cachedPlaylist = entries;

                // Also update screen if open
                if (MinecraftClient.getInstance().currentScreen instanceof MineifyScreen screen) {
                    screen.updatePlaylist(entries);
                }
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(NowPlayingPacket.ID, (payload, context) -> {
            context.client().execute(() -> {
                boolean hasNowPlaying = payload.title() != null && !payload.title().isEmpty();

                // Always update the cache
                cachedNowPlaying = hasNowPlaying ? payload.title() : null;
                cachedProgress = payload.progress();
                cachedElapsedMs = hasNowPlaying ? payload.elapsedMs() : 0;
                cachedDurationMs = hasNowPlaying ? payload.durationMs() : 0;
                cachedPaused = hasNowPlaying && payload.paused();

                if (!hasNowPlaying) {
                    AudioPlayer.getInstance().stop();
                }

                // Also update screen if open
                if (MinecraftClient.getInstance().currentScreen instanceof MineifyScreen screen) {
                    screen.updateNowPlaying(
                            payload.title(),
                            payload.progress(),
                            payload.elapsedMs(),
                            payload.durationMs(),
                            hasNowPlaying && payload.paused()
                    );
                }
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(UserPlaylistsSyncPacket.ID, (payload, context) -> {
            context.client().execute(() -> {
                List<MineifyScreen.UserPlaylistSummary> entries = new ArrayList<>();
                for (var playlist : payload.playlists()) {
                    entries.add(new MineifyScreen.UserPlaylistSummary(
                            playlist.id(),
                            playlist.name(),
                            playlist.isPublic(),
                            playlist.trackCount()
                    ));
                }
                cachedUserPlaylists = entries;

                if (MinecraftClient.getInstance().currentScreen instanceof MineifyScreen screen) {
                    screen.updateUserPlaylists(entries);
                }
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(ProfilesSyncPacket.ID, (payload, context) -> {
            context.client().execute(() -> {
                List<MineifyScreen.ProfileSummary> profiles = new ArrayList<>();
                for (var profile : payload.profiles()) {
                    List<MineifyScreen.ProfilePlaylistSummary> summaries = new ArrayList<>();
                    for (var playlist : profile.playlists()) {
                        List<MineifyScreen.ProfileTrackEntry> tracks = new ArrayList<>();
                        for (var track : playlist.tracks()) {
                            tracks.add(new MineifyScreen.ProfileTrackEntry(
                                    track.videoId(),
                                    track.title(),
                                    track.duration()
                            ));
                        }
                        summaries.add(new MineifyScreen.ProfilePlaylistSummary(
                                playlist.id(),
                                playlist.name(),
                                playlist.isPublic(),
                                playlist.trackCount(),
                                playlist.ownerId(),
                                playlist.ownerName(),
                                playlist.likedByRequester(),
                                tracks
                        ));
                    }

                    List<MineifyScreen.ProfilePlaylistSummary> likedSummaries = new ArrayList<>();
                    for (var playlist : profile.likedPlaylists()) {
                        List<MineifyScreen.ProfileTrackEntry> tracks = new ArrayList<>();
                        for (var track : playlist.tracks()) {
                            tracks.add(new MineifyScreen.ProfileTrackEntry(
                                    track.videoId(),
                                    track.title(),
                                    track.duration()
                            ));
                        }
                        likedSummaries.add(new MineifyScreen.ProfilePlaylistSummary(
                                playlist.id(),
                                playlist.name(),
                                playlist.isPublic(),
                                playlist.trackCount(),
                                playlist.ownerId(),
                                playlist.ownerName(),
                                playlist.likedByRequester(),
                                tracks
                        ));
                    }
                    profiles.add(new MineifyScreen.ProfileSummary(
                            profile.ownerId(),
                            profile.ownerName(),
                            profile.isSelf(),
                            summaries,
                            likedSummaries
                    ));
                }
                cachedProfiles = profiles;

                if (MinecraftClient.getInstance().currentScreen instanceof MineifyScreen screen) {
                    screen.updateProfiles(profiles);
                }
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(RecentlyPlayedSyncPacket.ID, (payload, context) -> {
            context.client().execute(() -> {
                List<MineifyScreen.RecentlyPlayedEntry> entries = new ArrayList<>();
                for (var entry : payload.entries()) {
                    entries.add(new MineifyScreen.RecentlyPlayedEntry(
                            entry.videoId(),
                            entry.title(),
                            entry.duration(),
                            entry.playedAtEpochMs()
                    ));
                }
                cachedRecentlyPlayed = entries;

                if (MinecraftClient.getInstance().currentScreen instanceof MineifyScreen screen) {
                    screen.updateRecentlyPlayed(entries);
                }
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(SpotifyImportPromptPacket.ID, (payload, context) -> {
            context.client().execute(() -> {
                List<MineifyScreen.SpotifyChoiceOption> options = new ArrayList<>();
                for (var option : payload.options()) {
                    options.add(new MineifyScreen.SpotifyChoiceOption(
                            option.videoId(),
                            option.title(),
                            option.channel(),
                            option.duration()
                    ));
                }
                if (MinecraftClient.getInstance().currentScreen instanceof MineifyScreen screen) {
                    screen.showSpotifyImportPrompt(
                            payload.spotifyTitle(),
                            payload.spotifyArtist(),
                            payload.currentIndex(),
                            payload.totalTracks(),
                            options
                    );
                }
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(SpotifyImportPreviewPacket.ID, (payload, context) -> {
            context.client().execute(() -> {
                List<MineifyScreen.SpotifyPreviewTrack> tracks = new ArrayList<>();
                for (var track : payload.tracks()) {
                    tracks.add(new MineifyScreen.SpotifyPreviewTrack(
                            track.spotifyTrackId(),
                            track.title(),
                            track.artist(),
                            track.query(),
                            track.duration()
                    ));
                }
                if (MinecraftClient.getInstance().currentScreen instanceof MineifyScreen screen) {
                    screen.showSpotifyImportPreview(new MineifyScreen.SpotifyImportPreviewState(
                            payload.spotifyPlaylistId(),
                            payload.spotifyPlaylistName(),
                            payload.spotifyOwnerName(),
                            tracks
                    ));
                }
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(SpotifyImportFinishedPacket.ID, (payload, context) -> {
            context.client().execute(() -> {
                if (MinecraftClient.getInstance().currentScreen instanceof MineifyScreen screen) {
                    screen.showSpotifyImportFinished(payload.addedCount(), payload.skippedCount(), payload.unresolvedCount());
                }
            });
        });

        // Play audio when server sends PlayAudioPacket
        ClientPlayNetworking.registerGlobalReceiver(PlayAudioPacket.ID, (payload, context) -> {
            long packetReceivedAtNanos = System.nanoTime();
            context.client().execute(() -> {
                LOGGER.info("Received play audio: {} ({}) with server elapsed {} ms",
                        payload.title(), payload.downloadUrl(), payload.serverElapsedMs());
                AudioPlayer.getInstance().play(
                        payload.downloadUrl(),
                        payload.title(),
                        payload.serverElapsedMs(),
                        packetReceivedAtNanos,
                        payload.scheduledDelayMs(),
                        () -> ClientPlayNetworking.send(new com.mineify.network.packets.PlaybackControlPacket("ready:" + payload.videoId()))
                );
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(PlaybackStatePacket.ID, (payload, context) -> {
            context.client().execute(() -> {
                cachedPaused = payload.paused();
                if (MinecraftClient.getInstance().currentScreen instanceof MineifyScreen screen) {
                    screen.updatePlaybackPaused(payload.paused());
                }

                if (payload.paused()) {
                    AudioPlayer.getInstance().pause();
                } else {
                    AudioPlayer.getInstance().resume();
                }
            });
        });

        // Stop audio when disconnecting from server
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            LOGGER.info("Disconnected from server, stopping audio");
            AudioPlayer.getInstance().stop();
            cachedNowPlaying = null;
            cachedProgress = 0f;
            cachedElapsedMs = 0;
            cachedDurationMs = 0;
            cachedPaused = false;
            cachedPlaylist = new ArrayList<>();
            cachedUserPlaylists = new ArrayList<>();
            cachedProfiles = new ArrayList<>();
            cachedRecentlyPlayed = new ArrayList<>();
        });
    }
}
