package com.mineify.network.packets;

import com.mineify.Mineify;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

public record SpotifyImportPreviewPacket(
        String spotifyPlaylistId,
        String spotifyPlaylistName,
        String spotifyOwnerName,
        List<TrackEntry> tracks
) implements CustomPayload {
    public static final CustomPayload.Id<SpotifyImportPreviewPacket> ID =
            new CustomPayload.Id<>(Identifier.of(Mineify.MOD_ID, "spotify_import_preview"));

    public static final PacketCodec<RegistryByteBuf, SpotifyImportPreviewPacket> CODEC =
            PacketCodec.of(
                    (value, buf) -> {
                        buf.writeString(value.spotifyPlaylistId());
                        buf.writeString(value.spotifyPlaylistName());
                        buf.writeString(value.spotifyOwnerName());
                        buf.writeVarInt(value.tracks().size());
                        for (TrackEntry track : value.tracks()) {
                            buf.writeString(track.spotifyTrackId());
                            buf.writeString(track.title());
                            buf.writeString(track.artist());
                            buf.writeString(track.query());
                            buf.writeString(track.duration());
                        }
                    },
                    buf -> {
                        String playlistId = buf.readString();
                        String playlistName = buf.readString();
                        String ownerName = buf.readString();
                        int size = buf.readVarInt();
                        List<TrackEntry> tracks = new ArrayList<>(size);
                        for (int i = 0; i < size; i++) {
                            tracks.add(new TrackEntry(
                                    buf.readString(),
                                    buf.readString(),
                                    buf.readString(),
                                    buf.readString(),
                                    buf.readString()
                            ));
                        }
                        return new SpotifyImportPreviewPacket(playlistId, playlistName, ownerName, tracks);
                    }
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    public record TrackEntry(
            String spotifyTrackId,
            String title,
            String artist,
            String query,
            String duration
    ) {
    }
}
