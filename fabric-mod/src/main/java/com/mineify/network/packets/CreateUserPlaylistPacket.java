package com.mineify.network.packets;

import com.mineify.Mineify;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record CreateUserPlaylistPacket(
        String name,
        boolean isPublic,
        boolean addInitialTrack,
        String videoId,
        String title,
        String duration
) implements CustomPayload {
    public static final CustomPayload.Id<CreateUserPlaylistPacket> ID =
            new CustomPayload.Id<>(Identifier.of(Mineify.MOD_ID, "create_user_playlist"));

    public static final PacketCodec<RegistryByteBuf, CreateUserPlaylistPacket> CODEC =
            PacketCodec.of(
                    (value, buf) -> {
                        buf.writeString(value.name());
                        buf.writeBoolean(value.isPublic());
                        buf.writeBoolean(value.addInitialTrack());
                        buf.writeString(value.videoId());
                        buf.writeString(value.title());
                        buf.writeString(value.duration());
                    },
                    buf -> new CreateUserPlaylistPacket(
                            buf.readString(),
                            buf.readBoolean(),
                            buf.readBoolean(),
                            buf.readString(),
                            buf.readString(),
                            buf.readString()
                    )
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
