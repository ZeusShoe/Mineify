package com.mineify.network.packets;

import com.mineify.Mineify;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record RequestRecentlyPlayedPacket() implements CustomPayload {
    public static final CustomPayload.Id<RequestRecentlyPlayedPacket> ID =
            new CustomPayload.Id<>(Identifier.of(Mineify.MOD_ID, "request_recently_played"));

    public static final PacketCodec<RegistryByteBuf, RequestRecentlyPlayedPacket> CODEC =
            PacketCodec.of(
                    (value, buf) -> {
                    },
                    buf -> new RequestRecentlyPlayedPacket()
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
