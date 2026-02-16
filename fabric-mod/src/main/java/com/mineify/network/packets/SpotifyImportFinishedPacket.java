package com.mineify.network.packets;

import com.mineify.Mineify;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record SpotifyImportFinishedPacket(int addedCount, int skippedCount, int unresolvedCount) implements CustomPayload {
    public static final CustomPayload.Id<SpotifyImportFinishedPacket> ID =
            new CustomPayload.Id<>(Identifier.of(Mineify.MOD_ID, "spotify_import_finished"));

    public static final PacketCodec<RegistryByteBuf, SpotifyImportFinishedPacket> CODEC =
            PacketCodec.of(
                    (value, buf) -> {
                        buf.writeVarInt(value.addedCount());
                        buf.writeVarInt(value.skippedCount());
                        buf.writeVarInt(value.unresolvedCount());
                    },
                    buf -> new SpotifyImportFinishedPacket(
                            buf.readVarInt(),
                            buf.readVarInt(),
                            buf.readVarInt()
                    )
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
