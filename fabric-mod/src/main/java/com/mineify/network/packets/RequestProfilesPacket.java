package com.mineify.network.packets;

import com.mineify.Mineify;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record RequestProfilesPacket(String query) implements CustomPayload {
    public static final CustomPayload.Id<RequestProfilesPacket> ID =
            new CustomPayload.Id<>(Identifier.of(Mineify.MOD_ID, "request_profiles"));

    public static final PacketCodec<RegistryByteBuf, RequestProfilesPacket> CODEC =
            PacketCodec.of(
                    (value, buf) -> buf.writeString(value.query()),
                    buf -> new RequestProfilesPacket(buf.readString())
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
