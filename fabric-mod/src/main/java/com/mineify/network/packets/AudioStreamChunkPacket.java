package com.mineify.network.packets;

import com.mineify.Mineify;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record AudioStreamChunkPacket(
        long streamId,
        int sequence,
        byte[] data,
        boolean last
) implements CustomPayload {
    public static final CustomPayload.Id<AudioStreamChunkPacket> ID =
            new CustomPayload.Id<>(Identifier.of(Mineify.MOD_ID, "audio_stream_chunk"));

    public static final PacketCodec<RegistryByteBuf, AudioStreamChunkPacket> CODEC =
            PacketCodec.of(
                    (value, buf) -> {
                        buf.writeLong(value.streamId());
                        buf.writeInt(value.sequence());
                        buf.writeVarInt(value.data().length);
                        buf.writeBytes(value.data());
                        buf.writeBoolean(value.last());
                    },
                    buf -> {
                        long streamId = buf.readLong();
                        int sequence = buf.readInt();
                        int len = buf.readVarInt();
                        byte[] data = new byte[len];
                        buf.readBytes(data);
                        boolean last = buf.readBoolean();
                        return new AudioStreamChunkPacket(streamId, sequence, data, last);
                    }
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
