package com.cloudjukebox.client.audio;

import com.cloudjukebox.CloudJukebox;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.util.concurrent.atomic.AtomicBoolean;

public class AudioStreamer {
    private static final float SAMPLE_RATE = 48000.0f;
    private static final int CHANNELS = 2; // Stereo
    private static final int SAMPLE_SIZE_IN_BITS = 16;
    private static final AudioFormat AUDIO_FORMAT = new AudioFormat(
            SAMPLE_RATE, SAMPLE_SIZE_IN_BITS, CHANNELS, true, false
    );

    private final BlockPos pos;
    private final AudioPlayer player;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean isStopped = new AtomicBoolean(false);
    private Thread streamThread;
    private SourceDataLine line;
    private float masterVolume = 1.0f;

    public AudioStreamer(BlockPos pos) {
        this.pos = pos;
        this.player = SoundCloudAudioService.INSTANCE.createPlayer();
    }

    public BlockPos getPos() {
        return pos;
    }

    public boolean isStopped() {
        return isStopped.get();
    }

    public void setVolume(float volume) {
        this.masterVolume = Math.max(0.0f, Math.min(1.0f, volume));
    }

    public void start(AudioTrack track, long seekMs) {
        if (isStopped.get()) {
            CloudJukebox.LOGGER.info("AudioStreamer.start skipped because streamer at {} is already stopped", pos);
            return;
        }

        if (isRunning.get()) {
            stop();
            return;
        }

        isRunning.set(true);
        streamThread = new Thread(() -> runStream(track, seekMs), "CloudJukebox-AudioStream-" + pos.toShortString());
        streamThread.setDaemon(true);
        streamThread.start();
    }

    private void runStream(AudioTrack track, long seekMs) {
        try {
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, AUDIO_FORMAT);
            if (!AudioSystem.isLineSupported(info)) {
                CloudJukebox.LOGGER.error("AudioSystem does not support 48kHz stereo line!");
                return;
            }

            line = (SourceDataLine) AudioSystem.getLine(info);
            // 9600 bytes = 50ms buffer for ultra-low latency and zero stutter
            line.open(AUDIO_FORMAT, 19200);
            line.start();

            CloudJukebox.LOGGER.info("AudioStreamer opened SourceDataLine successfully for track '{}' at {}", track.getInfo().title, pos);

            if (seekMs > 0 && seekMs < track.getDuration()) {
                track.setPosition(seekMs);
            }
            player.playTrack(track);

            byte[] sampleBuf = new byte[3840]; // 20ms of 48kHz stereo 16-bit PCM

            while (!isStopped.get() && isRunning.get() && player.getPlayingTrack() != null) {
                AudioFrame frame = player.provide();
                if (frame == null) {
                    Thread.sleep(5);
                    continue;
                }

                byte[] data = frame.getData();
                if (data == null || data.length == 0) {
                    continue;
                }

                if (sampleBuf.length < data.length) {
                    sampleBuf = new byte[data.length];
                }

                // Compute 3D Spatial Attenuation and Panning based on player distance and angle
                float leftGain = masterVolume;
                float rightGain = masterVolume;

                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) {
                    double dx = (pos.getX() + 0.5) - mc.player.getX();
                    double dy = (pos.getY() + 0.5) - mc.player.getY();
                    double dz = (pos.getZ() + 0.5) - mc.player.getZ();
                    double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

                    // Fade smoothly up to 64 blocks (matches Minecraft jukebox record propagation)
                    float distGain = (float) Math.max(0.0, 1.0 - (dist / 64.0));
                    distGain = distGain * distGain; // Smooth quadratic decay

                    // Stereo panning from player orientation
                    double yawRad = Math.toRadians(mc.player.getYRot());
                    double relX = dx * Math.cos(yawRad) + dz * Math.sin(yawRad);
                    float pan = 0.0f;
                    if (dist > 0.5) {
                        pan = (float) Math.max(-1.0, Math.min(1.0, relX / dist));
                    }

                    leftGain = masterVolume * distGain * (1.0f - Math.max(0.0f, pan * 0.6f));
                    rightGain = masterVolume * distGain * (1.0f - Math.max(0.0f, -pan * 0.6f));
                }

                // Apply spatial gain to 16-bit PCM samples
                for (int i = 0; i < data.length; i += 4) {
                    short left = (short) ((data[i] & 0xFF) | (data[i + 1] << 8));
                    short right = (short) ((data[i + 2] & 0xFF) | (data[i + 3] << 8));

                    short adjLeft = (short) Math.max(-32768, Math.min(32767, Math.round(left * leftGain)));
                    short adjRight = (short) Math.max(-32768, Math.min(32767, Math.round(right * rightGain)));

                    sampleBuf[i] = (byte) (adjLeft & 0xFF);
                    sampleBuf[i + 1] = (byte) ((adjLeft >> 8) & 0xFF);
                    sampleBuf[i + 2] = (byte) (adjRight & 0xFF);
                    sampleBuf[i + 3] = (byte) ((adjRight >> 8) & 0xFF);
                }

                if (isStopped.get() || line == null || !line.isOpen()) {
                    break;
                }
                line.write(sampleBuf, 0, data.length);
            }
        } catch (InterruptedException ignored) {
        } catch (Exception e) {
            CloudJukebox.LOGGER.error("Error in AudioStreamer thread for jukebox at {}: {}", pos, e.getMessage(), e);
        } finally {
            cleanup();
        }
    }

    public void stop() {
        isStopped.set(true);
        isRunning.set(false);
        try {
            player.stopTrack();
        } catch (Exception ignored) {}

        if (line != null) {
            try {
                line.stop();
                line.flush();
                line.close();
            } catch (Exception ignored) {}
            line = null;
        }

        if (streamThread != null) {
            streamThread.interrupt();
        }

        try {
            player.destroy();
        } catch (Exception ignored) {}
    }

    private void cleanup() {
        if (line != null) {
            try {
                line.stop();
                line.flush();
                line.close();
            } catch (Exception ignored) {}
            line = null;
        }
        try {
            player.destroy();
        } catch (Exception ignored) {}
    }
}
