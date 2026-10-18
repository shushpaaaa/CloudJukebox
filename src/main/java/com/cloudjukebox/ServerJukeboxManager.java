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
        // Stop all other currently active jukeboxes on this server so only the latest song plays
        for (Map.Entry<JukeboxKey, ActiveJukebox> entry : activeJukeboxes.entrySet()) {
            BlockPos oldPos = entry.getKey().pos();
            if (!oldPos.equals(pos)) {
                ActiveJukebox stopped = new ActiveJukebox(oldPos, "", "", "", 0, 0, false);
                broadcastSync(level, oldPos, stopped);
            }
        }
        activeJukeboxes.clear();

        JukeboxKey key = new JukeboxKey(level.dimension(), pos);
        ActiveJukebox active = new ActiveJukebox(
                pos, trackUrl, title, artist, durationMs, System.currentTimeMillis(), true
        );
        activeJukeboxes.put(key, active);

        broadcastSync(level, pos, active);
    }

    public void stopTrack(ServerLevel level, BlockPos pos) {
        JukeboxKey key = new JukeboxKey(level.dimension(), pos);
        activeJukeboxes.remove(key);

        ActiveJukebox stopped = new ActiveJukebox(
                pos, "", "", "", 0, 0, false
        );
        broadcastSync(level, pos, stopped);
    }

    public java.util.Set<JukeboxKey> getActiveKeys() {
        return activeJukeboxes.keySet();
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

        // Broadcast to all players in the level so old songs stop globally
        // and new songs are heard with client-side 3D spatial attenuation
        for (ServerPlayer player : level.players()) {
            ServerPlayNetworking.send(player, payload);
        }
    }
}
