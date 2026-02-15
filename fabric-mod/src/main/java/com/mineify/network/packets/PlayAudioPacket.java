package com.mineify.network.packets;

import com.mineify.Mineify;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

<<<<<<< Updated upstream
public record PlayAudioPacket(String downloadUrl, String title, String videoId, long startEpochMs) implements CustomPayload {
    public static final CustomPayload.Id ID =
            new CustomPayload.Id<>(Identifier.of(Mineify.MOD_ID, "play_audio"));

    public static final PacketCodec<RegistryByteBuf, PlayAudioPacket> CODEC = PacketCodec.of(
            (value, buf) -> {
                buf.writeString(value.downloadUrl());
                buf.writeString(value.title());
                buf.writeString(value.videoId());
                buf.writeLong(value.startEpochMs());
            },
            buf -> new PlayAudioPacket(
                    buf.readString(),
                    buf.readString(),
                    buf.readString(),
                    buf.readLong()
            )
    );
=======
public record PlayAudioPacket(String downloadUrl, String title, String videoId, long serverElapsedMs) implements CustomPayload {
    public static final CustomPayload.Id<PlayAudioPacket> ID =
            new CustomPayload.Id<>(Identifier.of(Mineify.MOD_ID, "play_audio"));

    public static final PacketCodec<RegistryByteBuf, PlayAudioPacket> CODEC =
            PacketCodec.of(
                    (value, buf) -> {
                        buf.writeString(value.downloadUrl);
                        buf.writeString(value.title);
                        buf.writeString(value.videoId);
                        buf.writeLong(value.serverElapsedMs);
                    },
                    buf -> new PlayAudioPacket(buf.readString(), buf.readString(), buf.readString(), buf.readLong())
            );
>>>>>>> Stashed changes

    @Override
    public Id getId() {
        return ID;
    }
}
