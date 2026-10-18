package com.cloudjukebox.client.audio;

import com.cloudjukebox.CloudJukebox;
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import net.minecraft.core.BlockPos;
import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.lwjgl.openal.AL10.*;

public class OpenALStreamer {
    private static final int NUM_BUFFERS = 4;
    private static final int SAMPLE_RATE = 48000;
    // 20ms of 48kHz mono 16-bit = 960 samples * 2 bytes = 1920 bytes per frame
    // We bundle 5 frames per OpenAL buffer = 100ms per buffer (9600 bytes)
    private static final int FRAMES_PER_BUFFER = 5;
    private static final int BUFFER_DATA_SIZE = 1920 * FRAMES_PER_BUFFER;

    private final BlockPos pos;
    private final AudioPlayer player;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private Thread streamThread;

    private int sourceId = -1;
    private int[] bufferIds = new int[NUM_BUFFERS];
    private float volume = 1.0f;

    public OpenALStreamer(BlockPos pos) {
        this.pos = pos;
        this.player = SoundCloudAudioService.INSTANCE.createPlayer();
    }

    public BlockPos getPos() {
        return pos;
    }

    public void setVolume(float volume) {
        this.volume = Math.max(0.0f, Math.min(1.0f, volume));
        if (sourceId != -1) {
            alSourcef(sourceId, AL_GAIN, this.volume);
        }
    }

    public void start(AudioTrack track, long seekMs) {
        if (isRunning.get()) {
            stop();
        }

        isRunning.set(true);
        streamThread = new Thread(() -> runStream(track, seekMs), "CloudJukebox-AudioStream-" + pos.toShortString());
        streamThread.setDaemon(true);
        streamThread.start();
    }

    private void runStream(AudioTrack track, long seekMs) {
        try {
            // Initialize OpenAL Source and Buffers
            sourceId = alGenSources();
            for (int i = 0; i < NUM_BUFFERS; i++) {
                bufferIds[i] = alGenBuffers();
            }

            // Set 3D Positional Audio attributes
            alSource3f(sourceId, AL_POSITION, pos.getX() + 0.5f, pos.getY() + 0.5f, pos.getZ() + 0.5f);
            alSource3f(sourceId, AL_VELOCITY, 0.0f, 0.0f, 0.0f);
            alSourcef(sourceId, AL_ROLLOFF_FACTOR, 1.2f);
            alSourcef(sourceId, AL_REFERENCE_DISTANCE, 4.0f);
            alSourcef(sourceId, AL_MAX_DISTANCE, 48.0f);
            alSourcef(sourceId, AL_GAIN, volume);

            // Configure track & player
            if (seekMs > 0 && seekMs < track.getDuration()) {
                track.setPosition(seekMs);
            }
            player.playTrack(track);

            // Pre-fill initial buffers
            ByteBuffer pcmBuffer = BufferUtils.createByteBuffer(BUFFER_DATA_SIZE);
            for (int i = 0; i < NUM_BUFFERS; i++) {
                if (!isRunning.get()) break;
                if (fillBuffer(pcmBuffer)) {
                    alBufferData(bufferIds[i], AL_FORMAT_MONO16, pcmBuffer, SAMPLE_RATE);
                    alSourceQueueBuffers(sourceId, bufferIds[i]);
                } else {
                    break;
                }
            }

            alSourcePlay(sourceId);

            // Streaming loop
            while (isRunning.get() && player.getPlayingTrack() != null) {
                int processed = alGetSourcei(sourceId, AL_BUFFERS_PROCESSED);

                while (processed > 0 && isRunning.get()) {
                    int unqueuedBuffer = alSourceUnqueueBuffers(sourceId);

                    if (fillBuffer(pcmBuffer)) {
                        alBufferData(unqueuedBuffer, AL_FORMAT_MONO16, pcmBuffer, SAMPLE_RATE);
                        alSourceQueueBuffers(sourceId, unqueuedBuffer);
                    }
                    processed--;
                }

                // Restart if stalled due to buffer underrun
                int state = alGetSourcei(sourceId, AL_SOURCE_STATE);
                if (state != AL_PLAYING && isRunning.get()) {
                    int queued = alGetSourcei(sourceId, AL_BUFFERS_QUEUED);
                    if (queued > 0) {
                        alSourcePlay(sourceId);
                    }
                }

                Thread.sleep(15);
            }
        } catch (InterruptedException ignored) {
        } catch (Exception e) {
            CloudJukebox.LOGGER.error("Error in OpenAL streaming thread for jukebox at {}: {}", pos, e.getMessage());
        } finally {
            cleanupOpenAL();
        }
    }

    /**
     * Reads frames from LavaPlayer and downmixes stereo 16-bit LE to mono 16-bit LE.
     */
    private boolean fillBuffer(ByteBuffer monoBuffer) {
        monoBuffer.clear();
        int framesRead = 0;

        while (framesRead < FRAMES_PER_BUFFER && isRunning.get()) {
            AudioFrame frame = player.provide();
            if (frame == null) {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    return false;
                }
                continue;
            }

            byte[] stereoData = frame.getData();
            // Downmix stereo to mono (4 bytes in stereo -> 2 bytes in mono)
            for (int i = 0; i < stereoData.length; i += 4) {
                short left = (short) ((stereoData[i] & 0xFF) | (stereoData[i + 1] << 8));
                short right = (short) ((stereoData[i + 2] & 0xFF) | (stereoData[i + 3] << 8));
                short mono = (short) ((left + right) / 2);

                monoBuffer.put((byte) (mono & 0xFF));
                monoBuffer.put((byte) ((mono >> 8) & 0xFF));
            }
            framesRead++;
        }

        monoBuffer.flip();
        return monoBuffer.hasRemaining();
    }

    public void stop() {
        isRunning.set(false);
        if (streamThread != null) {
            streamThread.interrupt();
        }
        player.stopTrack();
    }

    private void cleanupOpenAL() {
        if (sourceId != -1) {
            alSourceStop(sourceId);
            alDeleteSources(sourceId);
            sourceId = -1;
        }

        for (int i = 0; i < NUM_BUFFERS; i++) {
            if (bufferIds[i] != 0) {
                alDeleteBuffers(bufferIds[i]);
                bufferIds[i] = 0;
            }
        }

        player.destroy();
    }
}
