package com.mineify.network.packets;

import com.mineify.Mineify;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record AudioStreamStartPacket(
        String videoId,
        String title,
        long streamId,
        int sampleRate,
        int channels,
        int bitsPerSample,
        long dataSize,
        long durationMs,
        long startOffsetMs,
        long scheduledDelayMs,
        boolean preloadOnly,
        boolean serverSkipped
) implements CustomPayload {
    public static final CustomPayload.Id<AudioStreamStartPacket> ID =
            new CustomPayload.Id<>(Identifier.of(Mineify.MOD_ID, "audio_stream_start"));

    public static final PacketCodec<RegistryByteBuf, AudioStreamStartPacket> CODEC =
            PacketCodec.of(
                    (value, buf) -> {
                        buf.writeString(value.videoId());
                        buf.writeString(value.title());
                        buf.writeLong(value.streamId());
                        buf.writeInt(value.sampleRate());
                        buf.writeInt(value.channels());
                        buf.writeInt(value.bitsPerSample());
                        buf.writeLong(value.dataSize());
                        buf.writeLong(value.durationMs());
                        buf.writeLong(value.startOffsetMs());
                        buf.writeLong(value.scheduledDelayMs());
                        buf.writeBoolean(value.preloadOnly());
                        buf.writeBoolean(value.serverSkipped());
                    },
                    buf -> new AudioStreamStartPacket(
                            buf.readString(),
                            buf.readString(),
                            buf.readLong(),
                            buf.readInt(),
                            buf.readInt(),
                            buf.readInt(),
                            buf.readLong(),
                            buf.readLong(),
                            buf.readLong(),
                            buf.readLong(),
                            buf.readBoolean(),
                            buf.readBoolean()
                    )
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
