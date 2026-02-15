package com.mineify.network.packets;

import com.mineify.Mineify;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record PlaybackStatePacket(boolean paused) implements CustomPayload {
    public static final CustomPayload.Id<PlaybackStatePacket> ID =
            new CustomPayload.Id<>(Identifier.of(Mineify.MOD_ID, "playback_state"));

    public static final PacketCodec<RegistryByteBuf, PlaybackStatePacket> CODEC =
            PacketCodec.of(
                    (value, buf) -> buf.writeBoolean(value.paused()),
                    buf -> new PlaybackStatePacket(buf.readBoolean())
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
