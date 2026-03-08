package com.mineify.network.packets;

import com.mineify.Mineify;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record SeekPlaybackPacket(String videoId, long elapsedMs, boolean paused) implements CustomPayload {
    public static final CustomPayload.Id<SeekPlaybackPacket> ID =
            new CustomPayload.Id<>(Identifier.of(Mineify.MOD_ID, "seek_playback"));

    public static final PacketCodec<RegistryByteBuf, SeekPlaybackPacket> CODEC =
            PacketCodec.of(
                    (value, buf) -> {
                        buf.writeString(value.videoId());
                        buf.writeLong(value.elapsedMs());
                        buf.writeBoolean(value.paused());
                    },
                    buf -> new SeekPlaybackPacket(
                            buf.readString(),
                            buf.readLong(),
                            buf.readBoolean()
                    )
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
