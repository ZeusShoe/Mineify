package com.mineify;

import com.mineify.client.MineifyKeybinds;
import com.mineify.client.MineifyScreen;
import com.mineify.client.audio.AudioPlayer;
import com.mineify.network.packets.NowPlayingPacket;
import com.mineify.network.packets.PlayAudioPacket;
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

    public static List<MineifyScreen.PlaylistEntry> getCachedPlaylist() {
        return new ArrayList<>(cachedPlaylist);
    }

    public static String getCachedNowPlaying() {
        return cachedNowPlaying;
    }

    public static float getCachedProgress() {
        return cachedProgress;
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

                // Also update screen if open
                if (MinecraftClient.getInstance().currentScreen instanceof MineifyScreen screen) {
                    screen.updateNowPlaying(payload.title(), payload.progress());
                }
            });
        });

        // Play audio when server sends PlayAudioPacket
        ClientPlayNetworking.registerGlobalReceiver(PlayAudioPacket.ID, (payload, context) -> {
            long packetReceivedAtNanos = System.nanoTime();
            context.client().execute(() -> {
<<<<<<< Updated upstream
                LOGGER.info("Received play audio: {} ({})", payload.title(), payload.downloadUrl());
                AudioPlayer.getInstance().play(payload.downloadUrl(), payload.title(), payload.startEpochMs());
=======
                LOGGER.info("Received play audio: {} ({}) with server elapsed {} ms",
                        payload.title(), payload.downloadUrl(), payload.serverElapsedMs());
                AudioPlayer.getInstance().play(
                        payload.downloadUrl(),
                        payload.title(),
                        payload.serverElapsedMs(),
                        packetReceivedAtNanos
                );
>>>>>>> Stashed changes
            });
        });

        // Stop audio when disconnecting from server
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            LOGGER.info("Disconnected from server, stopping audio");
            AudioPlayer.getInstance().stop();
        });
    }
}
