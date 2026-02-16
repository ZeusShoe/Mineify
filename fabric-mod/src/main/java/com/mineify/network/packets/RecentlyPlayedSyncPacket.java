package com.mineify.network.packets;

import com.mineify.Mineify;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

public record RecentlyPlayedSyncPacket(List<Entry> entries) implements CustomPayload {
    public static final CustomPayload.Id<RecentlyPlayedSyncPacket> ID =
            new CustomPayload.Id<>(Identifier.of(Mineify.MOD_ID, "recently_played_sync"));

    public static final PacketCodec<RegistryByteBuf, RecentlyPlayedSyncPacket> CODEC =
            PacketCodec.of(
                    (value, buf) -> {
                        buf.writeVarInt(value.entries().size());
                        for (Entry entry : value.entries()) {
                            buf.writeString(entry.videoId());
                            buf.writeString(entry.title());
                            buf.writeString(entry.duration());
                            buf.writeLong(entry.playedAtEpochMs());
                        }
                    },
                    buf -> {
                        int size = buf.readVarInt();
                        List<Entry> entries = new ArrayList<>(size);
                        for (int i = 0; i < size; i++) {
                            entries.add(new Entry(
                                    buf.readString(),
                                    buf.readString(),
                                    buf.readString(),
                                    buf.readLong()
                            ));
                        }
                        return new RecentlyPlayedSyncPacket(entries);
                    }
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    public record Entry(String videoId, String title, String duration, long playedAtEpochMs) {
    }
}
