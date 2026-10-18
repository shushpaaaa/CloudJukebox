package com.cloudjukebox.client.audio;

import com.cloudjukebox.CloudJukebox;
import com.cloudjukebox.network.SyncJukeboxPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;

import net.minecraft.world.level.block.Blocks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ClientJukeboxManager {
    public static final ClientJukeboxManager INSTANCE = new ClientJukeboxManager();

    private final Map<BlockPos, AudioStreamer> activeStreamers = new ConcurrentHashMap<>();
    private float masterVolume = 1.0f;

    private BlockPos currentPlayingPos = null;
    private String currentPlayingTitle = "";
    private String currentPlayingArtist = "";

    public float getMasterVolume() {
        return masterVolume;
    }

    public BlockPos getCurrentPlayingPos() {
        return currentPlayingPos;
    }

    public String getCurrentPlayingTitle() {
        return currentPlayingTitle;
    }

    public String getCurrentPlayingArtist() {
        return currentPlayingArtist;
    }

    public boolean isPlaying() {
        return currentPlayingPos != null && !activeStreamers.isEmpty();
    }

    public void setMasterVolume(float masterVolume) {
        this.masterVolume = masterVolume;
        for (AudioStreamer streamer : activeStreamers.values()) {
            streamer.setVolume(masterVolume);
        }
    }

    public void handleSync(SyncJukeboxPayload payload) {
        BlockPos pos = payload.pos().immutable();

        if (!payload.isPlaying()) {
            stopJukebox(pos);
            Minecraft client = Minecraft.getInstance();
            if (client.player != null) {
                client.player.displayClientMessage(Component.literal("§e♪ Jukebox stopped"), true);
            }
            return;
        }

        // Only ONE song can play in the world at a time.
        // Stop all other currently active streamers immediately so songs NEVER overlap!
        for (BlockPos existingPos : activeStreamers.keySet()) {
            stopJukebox(existingPos);
        }

        long seekMs = Math.max(0, System.currentTimeMillis() - payload.startEpochMs());
        // If track has already finished playing, don't start
        if (payload.durationMs() > 0 && seekMs >= payload.durationMs()) {
            return;
        }

        currentPlayingPos = pos;
        currentPlayingTitle = payload.title();
        currentPlayingArtist = payload.artist();

        AudioStreamer streamer = new AudioStreamer(pos);
        streamer.setVolume(masterVolume);
        activeStreamers.put(pos, streamer);

        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.displayClientMessage(
                    Component.literal("§6♪ Now Playing: §f" + payload.title() + " §7- " + payload.artist()),
                    true
            );
        }

        SoundCloudAudioService.INSTANCE.loadTrack(payload.trackUrl(), track -> {
            // Ensure this streamer is still the active one and wasn't stopped while loading
            if (activeStreamers.get(pos) == streamer && !streamer.isStopped()) {
                streamer.start(track, seekMs);
                CloudJukebox.LOGGER.info("Started playing SoundCloud track '{}' at Jukebox {}", payload.title(), pos);
            } else {
                CloudJukebox.LOGGER.info("Discarded loaded track '{}' at {} because jukebox was stopped or replaced", payload.title(), pos);
                streamer.stop();
            }
        }, error -> {
            CloudJukebox.LOGGER.error("Failed to load track '{}' for Jukebox at {}: {}", payload.title(), pos, error.getMessage());
            if (activeStreamers.get(pos) == streamer) {
                activeStreamers.remove(pos);
            }
        });
    }

    public void stopJukebox(BlockPos pos) {
        AudioStreamer streamer = activeStreamers.remove(pos);
        if (streamer != null) {
            streamer.stop();
        }
        if (pos.equals(currentPlayingPos)) {
            currentPlayingPos = null;
            currentPlayingTitle = "";
            currentPlayingArtist = "";
        }
    }

    public void stopAll() {
        for (AudioStreamer streamer : activeStreamers.values()) {
            streamer.stop();
        }
        activeStreamers.clear();
        currentPlayingPos = null;
        currentPlayingTitle = "";
        currentPlayingArtist = "";
    }

    /**
     * Called on client tick: stops streams if jukebox was broken, and renders note particles.
     */
    public void tick() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || activeStreamers.isEmpty()) return;

        // Check if any active jukebox was broken or removed in the client world
        for (BlockPos pos : activeStreamers.keySet()) {
            if (!client.level.getBlockState(pos).is(Blocks.JUKEBOX)) {
                CloudJukebox.LOGGER.info("Jukebox at {} was broken or removed, stopping audio stream immediately", pos);
                stopJukebox(pos);
            }
        }

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
