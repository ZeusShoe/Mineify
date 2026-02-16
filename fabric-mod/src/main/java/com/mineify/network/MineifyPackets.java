package com.mineify.network;

import com.mineify.Mineify;
import com.mineify.network.packets.AddToPlaylistPacket;
import com.mineify.network.packets.AddToUserPlaylistPacket;
import com.mineify.network.packets.CreateUserPlaylistPacket;
import com.mineify.network.packets.PlaybackControlPacket;
import com.mineify.network.packets.PlaybackStatePacket;
import com.mineify.network.packets.ProfilesSyncPacket;
import com.mineify.network.packets.ReorderQueuePacket;
import com.mineify.network.packets.RequestProfilesPacket;
import com.mineify.network.packets.RequestUserPlaylistsPacket;
import com.mineify.network.packets.RemoveFromPlaylistPacket;
import com.mineify.network.packets.SearchRequestPacket;
import com.mineify.network.packets.UserPlaylistsSyncPacket;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public class MineifyPackets {
    public static void registerServerPackets() {
        // Register C2S (client-to-server) packet types
        PayloadTypeRegistry.playC2S().register(SearchRequestPacket.ID, SearchRequestPacket.CODEC);
        PayloadTypeRegistry.playC2S().register(AddToPlaylistPacket.ID, AddToPlaylistPacket.CODEC);
        PayloadTypeRegistry.playC2S().register(RemoveFromPlaylistPacket.ID, RemoveFromPlaylistPacket.CODEC);
        PayloadTypeRegistry.playC2S().register(PlaybackControlPacket.ID, PlaybackControlPacket.CODEC);
        PayloadTypeRegistry.playC2S().register(ReorderQueuePacket.ID, ReorderQueuePacket.CODEC);
        PayloadTypeRegistry.playC2S().register(RequestUserPlaylistsPacket.ID, RequestUserPlaylistsPacket.CODEC);
        PayloadTypeRegistry.playC2S().register(CreateUserPlaylistPacket.ID, CreateUserPlaylistPacket.CODEC);
        PayloadTypeRegistry.playC2S().register(AddToUserPlaylistPacket.ID, AddToUserPlaylistPacket.CODEC);
        PayloadTypeRegistry.playC2S().register(RequestProfilesPacket.ID, RequestProfilesPacket.CODEC);

        // Register S2C (server-to-client) packet types
        PayloadTypeRegistry.playS2C().register(
                com.mineify.network.packets.SearchResultsPacket.ID,
                com.mineify.network.packets.SearchResultsPacket.CODEC
        );
        PayloadTypeRegistry.playS2C().register(
                com.mineify.network.packets.PlaylistSyncPacket.ID,
                com.mineify.network.packets.PlaylistSyncPacket.CODEC
        );
        PayloadTypeRegistry.playS2C().register(
                com.mineify.network.packets.NowPlayingPacket.ID,
                com.mineify.network.packets.NowPlayingPacket.CODEC
        );
        PayloadTypeRegistry.playS2C().register(
                com.mineify.network.packets.PlayAudioPacket.ID,
                com.mineify.network.packets.PlayAudioPacket.CODEC
        );
        PayloadTypeRegistry.playS2C().register(
                PlaybackStatePacket.ID,
                PlaybackStatePacket.CODEC
        );
        PayloadTypeRegistry.playS2C().register(
                UserPlaylistsSyncPacket.ID,
                UserPlaylistsSyncPacket.CODEC
        );
        PayloadTypeRegistry.playS2C().register(
                ProfilesSyncPacket.ID,
                ProfilesSyncPacket.CODEC
        );

        // Register server-side handlers
        ServerPlayNetworking.registerGlobalReceiver(SearchRequestPacket.ID, (payload, context) -> {
            context.server().execute(() -> {
                var manager = Mineify.getPlaylistManager();
                if (manager != null) {
                    manager.handleSearch(context.player(), payload.query());
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(AddToPlaylistPacket.ID, (payload, context) -> {
            context.server().execute(() -> {
                var manager = Mineify.getPlaylistManager();
                if (manager != null) {
                    manager.handleAddToPlaylist(context.player(), payload.videoId(), payload.title(), payload.duration());
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(RemoveFromPlaylistPacket.ID, (payload, context) -> {
            context.server().execute(() -> {
                var manager = Mineify.getPlaylistManager();
                if (manager != null) {
                    manager.handleRemoveFromPlaylist(context.player(), payload.videoId());
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(PlaybackControlPacket.ID, (payload, context) -> {
            context.server().execute(() -> {
                var manager = Mineify.getPlaylistManager();
                if (manager != null) {
                    manager.handlePlaybackControl(context.player(), payload.action());
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(ReorderQueuePacket.ID, (payload, context) -> {
            context.server().execute(() -> {
                var manager = Mineify.getPlaylistManager();
                if (manager != null) {
                    manager.handleQueueReorder(context.player(), payload.fromIndex(), payload.toIndex());
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(RequestUserPlaylistsPacket.ID, (payload, context) -> {
            context.server().execute(() -> {
                var manager = Mineify.getPlaylistManager();
                if (manager != null) {
                    manager.handleRequestUserPlaylists(context.player());
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(CreateUserPlaylistPacket.ID, (payload, context) -> {
            context.server().execute(() -> {
                var manager = Mineify.getPlaylistManager();
                if (manager != null) {
                    manager.handleCreateUserPlaylist(
                            context.player(),
                            payload.name(),
                            payload.isPublic(),
                            payload.addInitialTrack(),
                            payload.videoId(),
                            payload.title(),
                            payload.duration()
                    );
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(AddToUserPlaylistPacket.ID, (payload, context) -> {
            context.server().execute(() -> {
                var manager = Mineify.getPlaylistManager();
                if (manager != null) {
                    manager.handleAddToUserPlaylist(
                            context.player(),
                            payload.playlistId(),
                            payload.videoId(),
                            payload.title(),
                            payload.duration()
                    );
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(RequestProfilesPacket.ID, (payload, context) -> {
            context.server().execute(() -> {
                var manager = Mineify.getPlaylistManager();
                if (manager != null) {
                    manager.handleRequestProfiles(context.player(), payload.query());
                }
            });
        });
    }

    public static void registerClientPackets() {
        // Client-side packet handlers are registered in MineifyClient
    }
}
