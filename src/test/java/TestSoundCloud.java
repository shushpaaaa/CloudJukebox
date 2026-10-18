import com.cloudjukebox.client.audio.ModernSoundCloudFormatHandler;
import com.cloudjukebox.client.audio.SoundCloudAudioService;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class TestSoundCloud {
    public static void main(String[] args) throws Exception {
        AudioPlayerManager playerManager = new DefaultAudioPlayerManager();
        playerManager.getConfiguration().setOutputFormat(com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats.COMMON_PCM_S16_LE);
        SoundCloudAudioSourceManager sc = SoundCloudAudioSourceManager.builder()
                .withAllowSearch(true)
                .withFilterOutPreviewTracks(true)
                .withFormatHandler(new ModernSoundCloudFormatHandler())
                .build();
        playerManager.registerSourceManager(sc);

        CountDownLatch latch = new CountDownLatch(1);
        String testUrl = "https://soundcloud.com/yeat-557610939/dracula-tame-impala-jennie";
        System.out.println("Testing universal track loader for: " + testUrl);

        SoundCloudAudioService service = new SoundCloudAudioService();
        service.loadTrack(testUrl, track -> {
            System.out.println("Track loaded successfully: " + track.getInfo().title + " (class=" + track.getClass().getName() + ")");
            testPlay(service.getPlayerManager(), track, latch);
        }, error -> {
            System.out.println("Track load FAILED: " + error.getMessage());
            error.printStackTrace();
            latch.countDown();
        });

        latch.await(30, TimeUnit.SECONDS);
    }

    private static void testPlay(AudioPlayerManager manager, AudioTrack track, CountDownLatch latch) {
        AudioPlayer player = manager.createPlayer();
        player.addListener(new com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter() {
            @Override
            public void onTrackException(AudioPlayer player, AudioTrack track, FriendlyException exception) {
                System.out.println("Track play exception: " + exception.getMessage());
                exception.printStackTrace();
                latch.countDown();
            }
        });
        player.playTrack(track);

        new Thread(() -> {
            try {
                for (int i = 0; i < 25; i++) {
                    com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame frame = player.provide();
                    if (frame != null) {
                        System.out.println("Frame: format=" + frame.getFormat() + ", dataLen=" + frame.getData().length + ", volume=" + frame.getVolume());
                        if (frame.getData().length > 10) {
                            System.out.println("SUCCESS! Produced actual audio PCM frame of size: " + frame.getData().length);
                            player.destroy();
                            latch.countDown();
                            return;
                        }
                    }
                    Thread.sleep(100);
                }
                System.out.println("Timed out waiting for frame");
                player.destroy();
                latch.countDown();
            } catch (Exception e) {
                e.printStackTrace();
                player.destroy();
                latch.countDown();
            }
        }).start();
    }

    private static void playSequentially(AudioPlayerManager manager, java.util.List<AudioTrack> tracks, int index, CountDownLatch latch) {
        if (index >= tracks.size()) {
            latch.countDown();
            return;
        }
        AudioTrack track = tracks.get(index);
        System.out.println("\nTesting track [" + index + "]: " + track.getInfo().title);
        AudioPlayer player = manager.createPlayer();
        player.addListener(new com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter() {
            @Override
            public void onTrackException(AudioPlayer player, AudioTrack track, FriendlyException exception) {
                System.out.println(" Track [" + index + "] failed: " + exception.getMessage());
                player.destroy();
                playSequentially(manager, tracks, index + 1, latch);
            }
        });
        player.playTrack(track);

        new Thread(() -> {
            try {
                for (int i = 0; i < 25; i++) {
                    com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame frame = player.provide();
                    if (frame != null) {
                        System.out.println(" SUCCESS! Track [" + index + "] produced audio frame!");
                        player.destroy();
                        latch.countDown();
                        return;
                    }
                    Thread.sleep(100);
                }
                System.out.println(" Track [" + index + "] timed out");
                player.destroy();
                playSequentially(manager, tracks, index + 1, latch);
            } catch (Exception e) {
                e.printStackTrace();
                player.destroy();
                playSequentially(manager, tracks, index + 1, latch);
            }
        }).start();
    }
}
