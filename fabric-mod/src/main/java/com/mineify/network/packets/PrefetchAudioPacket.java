package com.mineify.network.packets;

import com.mineify.Mineify;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record PrefetchAudioPacket(String downloadUrl, String videoId) implements CustomPayload {
    public static final CustomPayload.Id<PrefetchAudioPacket> ID =
            new CustomPayload.Id<>(Identifier.of(Mineify.MOD_ID, "prefetch_audio"));

    public static final PacketCodec<RegistryByteBuf, PrefetchAudioPacket> CODEC =
            PacketCodec.of(
                    (value, buf) -> {
                        buf.writeString(value.downloadUrl());
                        buf.writeString(value.videoId());
                    },
                    buf -> new PrefetchAudioPacket(
                            buf.readString(),
                            buf.readString()
                    )
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
