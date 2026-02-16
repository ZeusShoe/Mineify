package com.mineify.network.packets;

import com.mineify.Mineify;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

public record ConfirmSpotifyImportPacket(
        String mineifyPlaylistName,
        boolean isPublic,
        List<String> selectedSpotifyTrackIds
) implements CustomPayload {
    public static final CustomPayload.Id<ConfirmSpotifyImportPacket> ID =
            new CustomPayload.Id<>(Identifier.of(Mineify.MOD_ID, "confirm_spotify_import"));

    public static final PacketCodec<RegistryByteBuf, ConfirmSpotifyImportPacket> CODEC =
            PacketCodec.of(
                    (value, buf) -> {
                        buf.writeString(value.mineifyPlaylistName());
                        buf.writeBoolean(value.isPublic());
                        buf.writeVarInt(value.selectedSpotifyTrackIds().size());
                        for (String trackId : value.selectedSpotifyTrackIds()) {
                            buf.writeString(trackId);
                        }
                    },
                    buf -> {
                        String name = buf.readString();
                        boolean isPublic = buf.readBoolean();
                        int size = buf.readVarInt();
                        List<String> ids = new ArrayList<>(size);
                        for (int i = 0; i < size; i++) {
                            ids.add(buf.readString());
                        }
                        return new ConfirmSpotifyImportPacket(name, isPublic, ids);
                    }
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
