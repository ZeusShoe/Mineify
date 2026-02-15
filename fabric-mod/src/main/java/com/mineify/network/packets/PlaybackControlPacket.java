package com.mineify.network.packets;

import com.mineify.Mineify;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record PlaybackControlPacket(String action) implements CustomPayload {
    public static final CustomPayload.Id<PlaybackControlPacket> ID =
            new CustomPayload.Id<>(Identifier.of(Mineify.MOD_ID, "playback_control"));

    public static final PacketCodec<RegistryByteBuf, PlaybackControlPacket> CODEC =
            PacketCodec.of(
                    (value, buf) -> buf.writeString(value.action()),
                    buf -> new PlaybackControlPacket(buf.readString())
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
