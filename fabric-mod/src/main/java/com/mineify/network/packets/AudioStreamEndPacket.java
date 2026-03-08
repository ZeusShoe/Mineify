package com.mineify.network.packets;

import com.mineify.Mineify;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record AudioStreamEndPacket(long streamId) implements CustomPayload {
    public static final CustomPayload.Id<AudioStreamEndPacket> ID =
            new CustomPayload.Id<>(Identifier.of(Mineify.MOD_ID, "audio_stream_end"));

    public static final PacketCodec<RegistryByteBuf, AudioStreamEndPacket> CODEC =
            PacketCodec.of(
                    (value, buf) -> buf.writeLong(value.streamId()),
                    buf -> new AudioStreamEndPacket(buf.readLong())
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
