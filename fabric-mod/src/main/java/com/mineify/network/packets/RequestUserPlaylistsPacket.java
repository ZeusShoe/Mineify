package com.mineify.network.packets;

import com.mineify.Mineify;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record RequestUserPlaylistsPacket() implements CustomPayload {
    public static final CustomPayload.Id<RequestUserPlaylistsPacket> ID =
            new CustomPayload.Id<>(Identifier.of(Mineify.MOD_ID, "request_user_playlists"));

    public static final PacketCodec<RegistryByteBuf, RequestUserPlaylistsPacket> CODEC =
            PacketCodec.of(
                    (value, buf) -> {
                    },
                    buf -> new RequestUserPlaylistsPacket()
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
