package com.mineify.network.packets;

import com.mineify.Mineify;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record ReorderQueuePacket(int fromIndex, int toIndex) implements CustomPayload {
    public static final CustomPayload.Id<ReorderQueuePacket> ID =
            new CustomPayload.Id<>(Identifier.of(Mineify.MOD_ID, "reorder_queue"));

    public static final PacketCodec<RegistryByteBuf, ReorderQueuePacket> CODEC =
            PacketCodec.of(
                    (value, buf) -> {
                        buf.writeInt(value.fromIndex());
                        buf.writeInt(value.toIndex());
                    },
                    buf -> new ReorderQueuePacket(buf.readInt(), buf.readInt())
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
