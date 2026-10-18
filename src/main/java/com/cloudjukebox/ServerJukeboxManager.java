package com.cloudjukebox;

import com.cloudjukebox.network.OpenJukeboxGuiPayload;
import com.cloudjukebox.network.SyncJukeboxPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ServerJukeboxManager {
    public static final ServerJukeboxManager INSTANCE = new ServerJukeboxManager();

    public record JukeboxKey(ResourceKey<Level> dimension, BlockPos pos) {}

    public record ActiveJukebox(
            BlockPos pos,
            String trackUrl,
            String title,
            String artist,
            long durationMs,
            long startEpochMs,
            boolean isPlaying
    ) {
        public long getElapsedMs() {
            if (!isPlaying) return 0;
            return Math.max(0, System.currentTimeMillis() - startEpochMs);
        }
    }

    private final Map<JukeboxKey, ActiveJukebox> activeJukeboxes = new ConcurrentHashMap<>();

    public void playTrack(ServerLevel level, BlockPos pos, String trackUrl, String title, String artist, long durationMs) {
        JukeboxKey key = new JukeboxKey(level.dimension(), pos);
        ActiveJukebox active = new ActiveJukebox(
                pos, trackUrl, title, artist, durationMs, System.currentTimeMillis(), true
        );
        activeJukeboxes.put(key, active);

        broadcastSync(level, pos, active);
    }

    public void stopTrack(ServerLevel level, BlockPos pos) {
        JukeboxKey key = new JukeboxKey(level.dimension(), pos);
        ActiveJukebox active = activeJukeboxes.remove(key);
        if (active != null) {
            ActiveJukebox stopped = new ActiveJukebox(
                    pos, "", "", "", 0, 0, false
            );
            broadcastSync(level, pos, stopped);
        }
    }

    public void openGuiForPlayer(ServerPlayer player, BlockPos pos) {
        JukeboxKey key = new JukeboxKey(player.serverLevel().dimension(), pos);
        ActiveJukebox active = activeJukeboxes.get(key);

        OpenJukeboxGuiPayload payload;
        if (active != null && active.isPlaying()) {
            payload = new OpenJukeboxGuiPayload(
                    pos, active.title(), active.artist(), true, active.getElapsedMs(), active.durationMs()
            );
        } else {
            payload = new OpenJukeboxGuiPayload(
                    pos, "", "", false, 0, 0
            );
        }

        ServerPlayNetworking.send(player, payload);
    }

    public void syncNearbyPlayer(ServerPlayer player, BlockPos pos) {
        JukeboxKey key = new JukeboxKey(player.serverLevel().dimension(), pos);
        ActiveJukebox active = activeJukeboxes.get(key);
        if (active != null && active.isPlaying()) {
            ServerPlayNetworking.send(player, new SyncJukeboxPayload(
                    pos, active.trackUrl(), active.title(), active.artist(),
                    active.durationMs(), active.startEpochMs(), true
            ));
        }
    }

    private void broadcastSync(ServerLevel level, BlockPos pos, ActiveJukebox active) {
        SyncJukeboxPayload payload = new SyncJukeboxPayload(
                pos, active.trackUrl(), active.title(), active.artist(),
                active.durationMs(), active.startEpochMs(), active.isPlaying()
        );

        double radiusSquared = 64.0 * 64.0;
        for (ServerPlayer player : level.players()) {
            if (player.blockPosition().distSqr(pos) <= radiusSquared) {
                ServerPlayNetworking.send(player, payload);
            }
        }
    }
}
