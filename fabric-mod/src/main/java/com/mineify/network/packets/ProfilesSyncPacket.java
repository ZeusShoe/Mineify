package com.mineify.network.packets;

import com.mineify.Mineify;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

public record ProfilesSyncPacket(List<ProfileEntry> profiles) implements CustomPayload {
    public static final CustomPayload.Id<ProfilesSyncPacket> ID =
            new CustomPayload.Id<>(Identifier.of(Mineify.MOD_ID, "profiles_sync"));

    public static final PacketCodec<RegistryByteBuf, ProfilesSyncPacket> CODEC =
            PacketCodec.of(
                    (value, buf) -> {
                        buf.writeVarInt(value.profiles().size());
                        for (ProfileEntry profile : value.profiles()) {
                            buf.writeString(profile.ownerId());
                            buf.writeString(profile.ownerName());
                            buf.writeBoolean(profile.isSelf());
                            writePlaylists(buf, profile.playlists());
                            writePlaylists(buf, profile.likedPlaylists());
                        }
                    },
                    buf -> {
                        int profileCount = buf.readVarInt();
                        List<ProfileEntry> profiles = new ArrayList<>(profileCount);
                        for (int i = 0; i < profileCount; i++) {
                            String ownerId = buf.readString();
                            String ownerName = buf.readString();
                            boolean isSelf = buf.readBoolean();
                            List<PlaylistEntry> playlists = readPlaylists(buf);
                            List<PlaylistEntry> likedPlaylists = readPlaylists(buf);
                            profiles.add(new ProfileEntry(ownerId, ownerName, isSelf, playlists, likedPlaylists));
                        }
                        return new ProfilesSyncPacket(profiles);
                    }
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    private static void writePlaylists(RegistryByteBuf buf, List<PlaylistEntry> playlists) {
        buf.writeVarInt(playlists.size());
        for (PlaylistEntry playlist : playlists) {
            buf.writeString(playlist.id());
            buf.writeString(playlist.name());
            buf.writeBoolean(playlist.isPublic());
            buf.writeVarInt(playlist.trackCount());
            buf.writeString(playlist.ownerId());
            buf.writeString(playlist.ownerName());
            buf.writeBoolean(playlist.likedByRequester());
            buf.writeVarInt(playlist.tracks().size());
            for (TrackEntry track : playlist.tracks()) {
                buf.writeString(track.videoId());
                buf.writeString(track.title());
                buf.writeString(track.duration());
            }
        }
    }

    private static List<PlaylistEntry> readPlaylists(RegistryByteBuf buf) {
        int count = buf.readVarInt();
        List<PlaylistEntry> playlists = new ArrayList<>(count);
        for (int p = 0; p < count; p++) {
            String id = buf.readString();
            String name = buf.readString();
            boolean isPublic = buf.readBoolean();
            int trackCount = buf.readVarInt();
            String ownerId = buf.readString();
            String ownerName = buf.readString();
            boolean likedByRequester = buf.readBoolean();
            int trackSize = buf.readVarInt();
            List<TrackEntry> tracks = new ArrayList<>(trackSize);
            for (int t = 0; t < trackSize; t++) {
                tracks.add(new TrackEntry(
                        buf.readString(),
                        buf.readString(),
                        buf.readString()
                ));
            }
            playlists.add(new PlaylistEntry(id, name, isPublic, trackCount, ownerId, ownerName, likedByRequester, tracks));
        }
        return playlists;
    }

    public record ProfileEntry(
            String ownerId,
            String ownerName,
            boolean isSelf,
            List<PlaylistEntry> playlists,
            List<PlaylistEntry> likedPlaylists
    ) {
    }

    public record PlaylistEntry(
            String id,
            String name,
            boolean isPublic,
            int trackCount,
            String ownerId,
            String ownerName,
            boolean likedByRequester,
            List<TrackEntry> tracks
    ) {
    }

    public record TrackEntry(String videoId, String title, String duration) {
    }
}
