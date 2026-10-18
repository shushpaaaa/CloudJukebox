package com.cloudjukebox.client.audio;

import com.cloudjukebox.CloudJukebox;
import com.cloudjukebox.network.SyncJukeboxPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ClientJukeboxManager {
    public static final ClientJukeboxManager INSTANCE = new ClientJukeboxManager();

    private final Map<BlockPos, OpenALStreamer> activeStreamers = new ConcurrentHashMap<>();
    private float masterVolume = 1.0f;

    public float getMasterVolume() {
        return masterVolume;
    }

    public void setMasterVolume(float masterVolume) {
        this.masterVolume = masterVolume;
        for (OpenALStreamer streamer : activeStreamers.values()) {
            streamer.setVolume(masterVolume);
        }
    }

    public void handleSync(SyncJukeboxPayload payload) {
        BlockPos pos = payload.pos();

        if (!payload.isPlaying()) {
            stopJukebox(pos);
            return;
        }

        long seekMs = Math.max(0, System.currentTimeMillis() - payload.startEpochMs());
        // If track has already finished playing, don't start
        if (payload.durationMs() > 0 && seekMs >= payload.durationMs()) {
            stopJukebox(pos);
            return;
        }

        stopJukebox(pos);

        OpenALStreamer streamer = new OpenALStreamer(pos);
        streamer.setVolume(masterVolume);
        activeStreamers.put(pos, streamer);

        SoundCloudAudioService.INSTANCE.loadTrack(payload.trackUrl(), track -> {
            streamer.start(track, seekMs);
            CloudJukebox.LOGGER.info("Started playing SoundCloud track '{}' at Jukebox {}", payload.title(), pos);
        }, error -> {
            CloudJukebox.LOGGER.error("Failed to load track '{}' for Jukebox at {}: {}", payload.title(), pos, error.getMessage());
            activeStreamers.remove(pos);
        });
    }

    public void stopJukebox(BlockPos pos) {
        OpenALStreamer streamer = activeStreamers.remove(pos);
        if (streamer != null) {
            streamer.stop();
        }
    }

    public void stopAll() {
        for (OpenALStreamer streamer : activeStreamers.values()) {
            streamer.stop();
        }
        activeStreamers.clear();
    }

    /**
     * Called on client tick to render musical note particles around active jukeboxes.
     */
    public void tick() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || activeStreamers.isEmpty()) return;

        // Spawn musical note particle periodically around active jukeboxes
        if (client.level.getGameTime() % 15 == 0) {
            for (BlockPos pos : activeStreamers.keySet()) {
                double x = pos.getX() + 0.5 + (client.level.random.nextDouble() - 0.5) * 0.4;
                double y = pos.getY() + 1.2;
                double z = pos.getZ() + 0.5 + (client.level.random.nextDouble() - 0.5) * 0.4;
                // Note particle color depends on X velocity in vanilla
                double noteColor = client.level.random.nextDouble();
                client.level.addParticle(ParticleTypes.NOTE, x, y, z, noteColor, 0.0, 0.0);
            }
        }
    }
}
