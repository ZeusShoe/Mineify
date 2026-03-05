package com.mineify;

import com.mineify.network.MineifyPackets;
import com.mineify.server.CompanionClient;
import com.mineify.server.PlaylistManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main entry point for the Mineify mod.
 * Handles server-side initialization and lifecycle events.
 */
public class Mineify implements ModInitializer {
    public static final String MOD_ID = "mineify";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // Server-side components
    private static PlaylistManager playlistManager;
    private static CompanionClient companionClient;

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Mineify - Server-Wide Music Player");

        // Register network packets
        MineifyPackets.registerServerPackets();

        // Server lifecycle events
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            LOGGER.info("Mineify: Server starting, initializing components...");

            // Initialize the companion service client
            companionClient = new CompanionClient(MineifyConfig.getCompanionUrl());

            // Initialize the playlist manager
            playlistManager = new PlaylistManager(server, companionClient);

            LOGGER.info("Mineify: Components initialized successfully");
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            LOGGER.info("Mineify: Server stopping, cleaning up...");

            if (playlistManager != null) {
                playlistManager.shutdown();
            }
        });

        // Player connection events
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            LOGGER.info("Mineify: Player {} joined, syncing playlist state",
                    handler.player.getName().getString());

            if (playlistManager != null) {
                playlistManager.syncToPlayer(handler.player);
            }
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            LOGGER.info("Mineify: Player {} disconnected",
                    handler.player.getName().getString());
            if (playlistManager != null) {
                playlistManager.handlePlayerDisconnect(handler.player);
            }
        });

        LOGGER.info("Mineify initialized successfully!");
    }

    public static PlaylistManager getPlaylistManager() {
        return playlistManager;
    }

    public static CompanionClient getCompanionClient() {
        return companionClient;
    }
}
