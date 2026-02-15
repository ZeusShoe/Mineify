package com.mineify.network.packets;

import com.mineify.Mineify;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record NowPlayingPacket(String title, float progress, long elapsedMs, long durationMs, boolean paused) implements CustomPayload {
    public static final CustomPayload.Id<NowPlayingPacket> ID =
            new CustomPayload.Id<>(Identifier.of(Mineify.MOD_ID, "now_playing"));

    public static final PacketCodec<RegistryByteBuf, NowPlayingPacket> CODEC =
            PacketCodec.of(
                    (value, buf) -> {
                        buf.writeString(value.title());
                        buf.writeFloat(value.progress());
                        buf.writeLong(value.elapsedMs());
                        buf.writeLong(value.durationMs());
                        buf.writeBoolean(value.paused());
                    },
                    buf -> new NowPlayingPacket(
                            buf.readString(),
                            buf.readFloat(),
                            buf.readLong(),
                            buf.readLong(),
                            buf.readBoolean()
                    )
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
