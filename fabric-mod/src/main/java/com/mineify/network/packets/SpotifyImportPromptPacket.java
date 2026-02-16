package com.mineify.network.packets;

import com.mineify.Mineify;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

public record SpotifyImportPromptPacket(
        String spotifyTitle,
        String spotifyArtist,
        int currentIndex,
        int totalTracks,
        List<Option> options
) implements CustomPayload {
    public static final CustomPayload.Id<SpotifyImportPromptPacket> ID =
            new CustomPayload.Id<>(Identifier.of(Mineify.MOD_ID, "spotify_import_prompt"));

    public static final PacketCodec<RegistryByteBuf, SpotifyImportPromptPacket> CODEC =
            PacketCodec.of(
                    (value, buf) -> {
                        buf.writeString(value.spotifyTitle());
                        buf.writeString(value.spotifyArtist());
                        buf.writeVarInt(value.currentIndex());
                        buf.writeVarInt(value.totalTracks());
                        buf.writeVarInt(value.options().size());
                        for (Option option : value.options()) {
                            buf.writeString(option.videoId());
                            buf.writeString(option.title());
                            buf.writeString(option.channel());
                            buf.writeString(option.duration());
                        }
                    },
                    buf -> {
                        String title = buf.readString();
                        String artist = buf.readString();
                        int current = buf.readVarInt();
                        int total = buf.readVarInt();
                        int size = buf.readVarInt();
                        List<Option> options = new ArrayList<>(size);
                        for (int i = 0; i < size; i++) {
                            options.add(new Option(
                                    buf.readString(),
                                    buf.readString(),
                                    buf.readString(),
                                    buf.readString()
                            ));
                        }
                        return new SpotifyImportPromptPacket(title, artist, current, total, options);
                    }
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    public record Option(String videoId, String title, String channel, String duration) {
    }
}
