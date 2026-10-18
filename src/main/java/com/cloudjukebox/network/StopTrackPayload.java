package com.cloudjukebox.network;

import com.cloudjukebox.CloudJukebox;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record StopTrackPayload(
        BlockPos pos
) implements CustomPacketPayload {
    public static final Type<StopTrackPayload> TYPE = new Type<>(CloudJukebox.id("stop_track"));

    public static final StreamCodec<FriendlyByteBuf, StopTrackPayload> CODEC = StreamCodec.of(
            (buf, payload) -> buf.writeBlockPos(payload.pos()),
            buf -> new StopTrackPayload(buf.readBlockPos())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
