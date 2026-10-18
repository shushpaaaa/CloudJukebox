package com.cloudjukebox.network;

import com.cloudjukebox.CloudJukebox;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record PlayTrackPayload(
        BlockPos pos,
        String trackUrl,
        String title,
        String artist,
        long durationMs
) implements CustomPacketPayload {
    public static final Type<PlayTrackPayload> TYPE = new Type<>(CloudJukebox.id("play_track"));

    public static final StreamCodec<FriendlyByteBuf, PlayTrackPayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBlockPos(payload.pos());
                buf.writeUtf(payload.trackUrl());
                buf.writeUtf(payload.title());
                buf.writeUtf(payload.artist());
                buf.writeLong(payload.durationMs());
            },
            buf -> new PlayTrackPayload(
                    buf.readBlockPos(),
                    buf.readUtf(),
                    buf.readUtf(),
                    buf.readUtf(),
                    buf.readLong()
            )
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
