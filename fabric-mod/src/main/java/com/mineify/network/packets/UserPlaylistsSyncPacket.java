package com.mineify.network.packets;

import com.mineify.Mineify;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

public record UserPlaylistsSyncPacket(List<Entry> playlists) implements CustomPayload {
    public static final CustomPayload.Id<UserPlaylistsSyncPacket> ID =
            new CustomPayload.Id<>(Identifier.of(Mineify.MOD_ID, "user_playlists_sync"));

    public static final PacketCodec<RegistryByteBuf, UserPlaylistsSyncPacket> CODEC =
            PacketCodec.of(
                    (value, buf) -> {
                        buf.writeVarInt(value.playlists.size());
                        for (Entry entry : value.playlists) {
                            buf.writeString(entry.id());
                            buf.writeString(entry.name());
                            buf.writeBoolean(entry.isPublic());
                            buf.writeVarInt(entry.trackCount());
                        }
                    },
                    buf -> {
                        int size = buf.readVarInt();
                        List<Entry> entries = new ArrayList<>(size);
                        for (int i = 0; i < size; i++) {
                            entries.add(new Entry(
                                    buf.readString(),
                                    buf.readString(),
                                    buf.readBoolean(),
                                    buf.readVarInt()
                            ));
                        }
                        return new UserPlaylistsSyncPacket(entries);
                    }
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    public record Entry(String id, String name, boolean isPublic, int trackCount) {
    }
}
