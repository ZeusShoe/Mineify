package com.mineify;

import com.mineify.network.MineifyPackets;
import com.mineify.server.CompanionClient;
import com.mineify.server.PlaylistManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.DirectoryStream;
import java.io.IOException;
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

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(CommandManager.literal("mineify")
                    .then(CommandManager.literal("reload")
                            .requires(CommandManager.requirePermissionLevel(CommandManager.OWNERS_CHECK))
                            .executes(ctx -> {
                                MineifyConfig.reload();
                                companionClient = new CompanionClient(MineifyConfig.getCompanionUrl());
                                if (playlistManager != null) {
                                    playlistManager.setCompanionClient(companionClient);
                                }
                                ctx.getSource().sendFeedback(() -> Text.literal("Mineify config reloaded."), false);
                                return 1;
                            }))
                    .then(CommandManager.literal("status")
                            .requires(CommandManager.requirePermissionLevel(CommandManager.OWNERS_CHECK))
                            .executes(ctx -> {
                                PlaylistManager manager = Mineify.getPlaylistManager();
                                if (manager == null) {
                                    ctx.getSource().sendFeedback(() -> Text.literal("Mineify is not initialized."), false);
                                    return 0;
                                }
                                PlaylistManager.PlaylistStatus status = manager.getStatus();
                                ctx.getSource().sendFeedback(() -> Text.literal(
                                        "Mineify status: playing=" + status.playing()
                                                + ", paused=" + status.paused()
                                                + ", waitingForReady=" + status.waitingForReady()
                                                + ", queueSize=" + status.queueSize()
                                                + ", nowPlaying=\"" + status.nowPlayingTitle() + "\""
                                ), false);
                                return 1;
                            }))
                    .then(CommandManager.literal("perms")
                            .requires(CommandManager.requirePermissionLevel(CommandManager.OWNERS_CHECK))
                            .executes(ctx -> {
                                String perms = String.join(", ", PlaylistManager.getPermissionNodes());
                                ctx.getSource().sendFeedback(() -> Text.literal("Mineify permission nodes: " + perms), false);
                                return 1;
                            }))
            );
        });

        // Server lifecycle events
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            LOGGER.info("Mineify: Server starting, initializing components...");

            // Initialize the companion service client
            companionClient = new CompanionClient(MineifyConfig.getCompanionUrl());

            // Initialize the playlist manager
            playlistManager = new PlaylistManager(server, companionClient);

            // Clear server-side companion downloads on startup
            try {
                Path downloadsDir = server.getRunDirectory()
                        .resolve("MineifyCompanion")
                        .resolve("downloads");
                clearDirectory(downloadsDir);
                LOGGER.info("Mineify: Cleared companion downloads at {}", downloadsDir);
            } catch (Exception e) {
                LOGGER.warn("Mineify: Failed to clear companion downloads", e);
            }

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

    private static boolean isOp(ServerCommandSource source) {
        return CommandManager.requirePermissionLevel(CommandManager.OWNERS_CHECK).test(source);
    }

    private static void clearDirectory(Path dir) throws IOException {
        if (dir == null) {
            return;
        }
        Files.createDirectories(dir);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    deleteRecursively(entry);
                } else {
                    Files.deleteIfExists(entry);
                }
            }
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        if (Files.isDirectory(path)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
                for (Path entry : stream) {
                    deleteRecursively(entry);
                }
            }
        }
        Files.deleteIfExists(path);
    }
}
