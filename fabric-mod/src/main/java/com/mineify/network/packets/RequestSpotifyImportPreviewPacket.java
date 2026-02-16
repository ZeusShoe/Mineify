package com.mineify.network.packets;

import com.mineify.Mineify;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record RequestSpotifyImportPreviewPacket(String spotifyUrl) implements CustomPayload {
    public static final CustomPayload.Id<RequestSpotifyImportPreviewPacket> ID =
            new CustomPayload.Id<>(Identifier.of(Mineify.MOD_ID, "request_spotify_import_preview"));

    public static final PacketCodec<RegistryByteBuf, RequestSpotifyImportPreviewPacket> CODEC =
            PacketCodec.of(
                    (value, buf) -> buf.writeString(value.spotifyUrl()),
                    buf -> new RequestSpotifyImportPreviewPacket(buf.readString())
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
