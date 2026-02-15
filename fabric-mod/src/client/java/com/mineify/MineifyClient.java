package com.mineify;

import com.mineify.client.MineifyKeybinds;
import com.mineify.client.MineifyScreen;
import com.mineify.client.audio.AudioPlayer;
import com.mineify.network.packets.NowPlayingPacket;
import com.mineify.network.packets.PlayAudioPacket;
import com.mineify.network.packets.PlaybackStatePacket;
import com.mineify.network.packets.PlaylistSyncPacket;
import com.mineify.network.packets.SearchResultsPacket;
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
                // Always update the cache
                cachedNowPlaying = payload.title().isEmpty() ? null : payload.title();
                cachedProgress = payload.progress();
                cachedElapsedMs = cachedNowPlaying == null ? 0 : payload.elapsedMs();
                cachedDurationMs = cachedNowPlaying == null ? 0 : payload.durationMs();
                cachedPaused = cachedNowPlaying != null && payload.paused();

                // Also update screen if open
                if (MinecraftClient.getInstance().currentScreen instanceof MineifyScreen screen) {
                    screen.updateNowPlaying(
                            payload.title(),
                            payload.progress(),
                            payload.elapsedMs(),
                            payload.durationMs(),
                            payload.paused()
                    );
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
                        packetReceivedAtNanos
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
        });
    }
}
