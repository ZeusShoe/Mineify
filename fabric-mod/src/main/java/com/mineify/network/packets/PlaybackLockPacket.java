package com.mineify.network.packets;

import com.mineify.Mineify;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record PlaybackLockPacket(boolean locked) implements CustomPayload {
    public static final CustomPayload.Id<PlaybackLockPacket> ID =
            new CustomPayload.Id<>(Identifier.of(Mineify.MOD_ID, "playback_lock"));

    public static final PacketCodec<RegistryByteBuf, PlaybackLockPacket> CODEC =
            PacketCodec.of(
                    (value, buf) -> buf.writeBoolean(value.locked()),
                    buf -> new PlaybackLockPacket(buf.readBoolean())
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
