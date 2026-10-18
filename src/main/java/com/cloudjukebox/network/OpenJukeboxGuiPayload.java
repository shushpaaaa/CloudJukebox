package com.cloudjukebox.network;

import com.cloudjukebox.CloudJukebox;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record OpenJukeboxGuiPayload(
        BlockPos pos,
        String currentTitle,
        String currentArtist,
        boolean isPlaying,
        long elapsedMs,
        long totalDurationMs
) implements CustomPacketPayload {
    public static final Type<OpenJukeboxGuiPayload> TYPE = new Type<>(CloudJukebox.id("open_gui"));

    public static final StreamCodec<FriendlyByteBuf, OpenJukeboxGuiPayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBlockPos(payload.pos());
                buf.writeUtf(payload.currentTitle());
                buf.writeUtf(payload.currentArtist());
                buf.writeBoolean(payload.isPlaying());
                buf.writeLong(payload.elapsedMs());
                buf.writeLong(payload.totalDurationMs());
            },
            buf -> new OpenJukeboxGuiPayload(
                    buf.readBlockPos(),
                    buf.readUtf(),
                    buf.readUtf(),
                    buf.readBoolean(),
                    buf.readLong(),
                    buf.readLong()
            )
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
