package com.cloudjukebox.network;

import com.cloudjukebox.CloudJukebox;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record SyncJukeboxPayload(
        BlockPos pos,
        String trackUrl,
        String title,
        String artist,
        long durationMs,
        long startEpochMs,
        boolean isPlaying
) implements CustomPacketPayload {
    public static final Type<SyncJukeboxPayload> TYPE = new Type<>(CloudJukebox.id("sync_jukebox"));

    public static final StreamCodec<FriendlyByteBuf, SyncJukeboxPayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBlockPos(payload.pos());
                buf.writeUtf(payload.trackUrl());
                buf.writeUtf(payload.title());
                buf.writeUtf(payload.artist());
                buf.writeLong(payload.durationMs());
                buf.writeLong(payload.startEpochMs());
                buf.writeBoolean(payload.isPlaying());
            },
            buf -> new SyncJukeboxPayload(
                    buf.readBlockPos(),
                    buf.readUtf(),
                    buf.readUtf(),
                    buf.readUtf(),
                    buf.readLong(),
                    buf.readLong(),
                    buf.readBoolean()
            )
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
