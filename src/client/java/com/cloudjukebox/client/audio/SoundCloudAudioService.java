package com.cloudjukebox.client.audio;

import com.cloudjukebox.CloudJukebox;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class SoundCloudAudioService {
    public static final SoundCloudAudioService INSTANCE = new SoundCloudAudioService();

    private final AudioPlayerManager playerManager;

    public record TrackInfo(
            String title,
            String artist,
            long durationMs,
            String uri
    ) {
        public String getFormattedDuration() {
            long totalSeconds = durationMs / 1000;
            long minutes = totalSeconds / 60;
            long seconds = totalSeconds % 60;
            return String.format("%d:%02d", minutes, seconds);
        }
    }

    public SoundCloudAudioService() {
        this.playerManager = new DefaultAudioPlayerManager();
        // Register remote audio sources (SoundCloud, HTTP, etc.)
        AudioSourceManagers.registerRemoteSources(this.playerManager);
    }

    public AudioPlayerManager getPlayerManager() {
        return playerManager;
    }

    public AudioPlayer createPlayer() {
        return playerManager.createPlayer();
    }

    /**
     * Asynchronously searches SoundCloud for the specified query.
     */
    public CompletableFuture<List<TrackInfo>> search(String query) {
        CompletableFuture<List<TrackInfo>> future = new CompletableFuture<>();
        String identifier = "scsearch:" + query;

        playerManager.loadItem(identifier, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                List<TrackInfo> results = new ArrayList<>();
                results.add(fromTrack(track));
                future.complete(results);
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                List<TrackInfo> results = new ArrayList<>();
                for (AudioTrack track : playlist.getTracks()) {
                    results.add(fromTrack(track));
                }
                future.complete(results);
            }

            @Override
            public void noMatches() {
                future.complete(Collections.emptyList());
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                CloudJukebox.LOGGER.error("Failed to load SoundCloud track for query {}: {}", query, exception.getMessage());
                future.completeExceptionally(exception);
            }
        });

        return future;
    }

    /**
     * Loads a specific audio track by direct URL.
     */
    public void loadTrack(String url, Consumer<AudioTrack> onSuccess, Consumer<Throwable> onError) {
        playerManager.loadItem(url, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                onSuccess.accept(track);
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                if (!playlist.getTracks().isEmpty()) {
                    onSuccess.accept(playlist.getTracks().get(0));
                } else {
                    onError.accept(new RuntimeException("Empty playlist"));
                }
            }

            @Override
            public void noMatches() {
                onError.accept(new RuntimeException("No match found for URL: " + url));
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                onError.accept(exception);
            }
        });
    }

    private TrackInfo fromTrack(AudioTrack track) {
        return new TrackInfo(
                track.getInfo().title,
                track.getInfo().author,
                track.getInfo().length,
                track.getInfo().uri
        );
    }
}
