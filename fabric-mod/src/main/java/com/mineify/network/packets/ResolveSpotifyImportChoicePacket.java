package com.mineify.network.packets;

import com.mineify.Mineify;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record ResolveSpotifyImportChoicePacket(String videoId) implements CustomPayload {
    public static final CustomPayload.Id<ResolveSpotifyImportChoicePacket> ID =
            new CustomPayload.Id<>(Identifier.of(Mineify.MOD_ID, "resolve_spotify_import_choice"));

    public static final PacketCodec<RegistryByteBuf, ResolveSpotifyImportChoicePacket> CODEC =
            PacketCodec.of(
                    (value, buf) -> buf.writeString(value.videoId()),
                    buf -> new ResolveSpotifyImportChoicePacket(buf.readString())
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
