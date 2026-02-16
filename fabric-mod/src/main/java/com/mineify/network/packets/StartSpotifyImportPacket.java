package com.mineify.network.packets;

import com.mineify.Mineify;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record StartSpotifyImportPacket(String spotifyUrl, String targetPlaylistId) implements CustomPayload {
    public static final CustomPayload.Id<StartSpotifyImportPacket> ID =
            new CustomPayload.Id<>(Identifier.of(Mineify.MOD_ID, "start_spotify_import"));

    public static final PacketCodec<RegistryByteBuf, StartSpotifyImportPacket> CODEC =
            PacketCodec.of(
                    (value, buf) -> {
                        buf.writeString(value.spotifyUrl());
                        buf.writeString(value.targetPlaylistId());
                    },
                    buf -> new StartSpotifyImportPacket(buf.readString(), buf.readString())
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
