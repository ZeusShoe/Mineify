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
                            buf.writeVarInt(profile.playlists().size());
                            for (PlaylistEntry playlist : profile.playlists()) {
                                buf.writeString(playlist.id());
                                buf.writeString(playlist.name());
                                buf.writeBoolean(playlist.isPublic());
                                buf.writeVarInt(playlist.trackCount());
                            }
                        }
                    },
                    buf -> {
                        int profileCount = buf.readVarInt();
                        List<ProfileEntry> profiles = new ArrayList<>(profileCount);
                        for (int i = 0; i < profileCount; i++) {
                            String ownerId = buf.readString();
                            String ownerName = buf.readString();
                            boolean isSelf = buf.readBoolean();
                            int playlistCount = buf.readVarInt();
                            List<PlaylistEntry> playlists = new ArrayList<>(playlistCount);
                            for (int p = 0; p < playlistCount; p++) {
                                playlists.add(new PlaylistEntry(
                                        buf.readString(),
                                        buf.readString(),
                                        buf.readBoolean(),
                                        buf.readVarInt()
                                ));
                            }
                            profiles.add(new ProfileEntry(ownerId, ownerName, isSelf, playlists));
                        }
                        return new ProfilesSyncPacket(profiles);
                    }
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    public record ProfileEntry(String ownerId, String ownerName, boolean isSelf, List<PlaylistEntry> playlists) {
    }

    public record PlaylistEntry(String id, String name, boolean isPublic, int trackCount) {
    }
}
