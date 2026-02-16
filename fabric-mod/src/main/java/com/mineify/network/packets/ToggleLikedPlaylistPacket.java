package com.mineify.network.packets;

import com.mineify.Mineify;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record ToggleLikedPlaylistPacket(String playlistId, boolean liked) implements CustomPayload {
    public static final CustomPayload.Id<ToggleLikedPlaylistPacket> ID =
            new CustomPayload.Id<>(Identifier.of(Mineify.MOD_ID, "toggle_liked_playlist"));

    public static final PacketCodec<RegistryByteBuf, ToggleLikedPlaylistPacket> CODEC =
            PacketCodec.of(
                    (value, buf) -> {
                        buf.writeString(value.playlistId());
                        buf.writeBoolean(value.liked());
                    },
                    buf -> new ToggleLikedPlaylistPacket(buf.readString(), buf.readBoolean())
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
