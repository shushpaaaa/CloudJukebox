package com.cloudjukebox.client.audio;

import com.cloudjukebox.CloudJukebox;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegAudioTrack;
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class SoundCloudAudioService {
    public static final SoundCloudAudioService INSTANCE = new SoundCloudAudioService();

    private final AudioPlayerManager playerManager;
    private final SoundCloudAudioSourceManager soundCloudSource;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ExecutorService downloadPool;

    private volatile String cachedClientId = null;

    public record TrackInfo(
            long id,
            String title,
            String artist,
            long durationMs,
            String uri,
            String artworkUrl
    ) {
        public String getFormattedDuration() {
            long totalSeconds = durationMs / 1000;
            long minutes = totalSeconds / 60;
            long seconds = totalSeconds % 60;
            return String.format("%d:%02d", minutes, seconds);
        }
    }

    public record SearchResult(
            List<TrackInfo> tracks,
            int totalResults,
            int page,
            int totalPages
    ) {}

    public SoundCloudAudioService() {
        this.playerManager = new DefaultAudioPlayerManager();
        // Crucial: OpenAL expects 16-bit PCM little-endian (48kHz stereo), not Opus compressed bytes!
        this.playerManager.getConfiguration().setOutputFormat(StandardAudioDataFormats.COMMON_PCM_S16_LE);

        this.soundCloudSource = SoundCloudAudioSourceManager.builder()
                .withAllowSearch(true)
                .withFilterOutPreviewTracks(true)
                .withFormatHandler(new ModernSoundCloudFormatHandler())
                .build();

        this.playerManager.registerSourceManager(soundCloudSource);
        this.playerManager.registerSourceManager(new HttpAudioSourceManager());

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(6))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.objectMapper = new ObjectMapper();
        this.downloadPool = Executors.newFixedThreadPool(8);
    }

    public AudioPlayerManager getPlayerManager() {
        return playerManager;
    }

    public AudioPlayer createPlayer() {
        return playerManager.createPlayer();
    }

    /**
     * Resolves or refreshes a valid SoundCloud client_id.
     */
    private CompletableFuture<String> getClientId() {
        if (cachedClientId != null && !cachedClientId.isEmpty()) {
            return CompletableFuture.completedFuture(cachedClientId);
        }
        try {
            String id = soundCloudSource.getClientId();
            if (id != null && !id.isEmpty()) {
                cachedClientId = id;
                return CompletableFuture.completedFuture(id);
            }
        } catch (Exception ignored) {}

        // Fallback: Trigger a quick search via sourceManager to force it to discover the client_id
        CompletableFuture<String> future = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {
            try {
                // Fetch soundcloud.com scripts to extract client_id directly if needed
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create("https://soundcloud.com"))
                        .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)")
                        .timeout(Duration.ofSeconds(8))
                        .build();
                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                String html = resp.body();

                java.util.regex.Pattern p = java.util.regex.Pattern.compile("https://[a-zA-Z0-9-.]+/assets/[a-f0-9-]+\\.js");
                java.util.regex.Matcher m = p.matcher(html);
                List<String> scripts = new ArrayList<>();
                while (m.find()) {
                    scripts.add(m.group());
                }

                // Check recent script assets
                for (int i = scripts.size() - 1; i >= Math.max(0, scripts.size() - 6); i--) {
                    HttpRequest sReq = HttpRequest.newBuilder()
                            .uri(URI.create(scripts.get(i)))
                            .header("User-Agent", "Mozilla/5.0")
                            .timeout(Duration.ofSeconds(6))
                            .build();
                    HttpResponse<String> sResp = httpClient.send(sReq, HttpResponse.BodyHandlers.ofString());
                    java.util.regex.Pattern cip = java.util.regex.Pattern.compile("[^_]client_id:\"([a-zA-Z0-9]{32})\"");
                    java.util.regex.Matcher cim = cip.matcher(sResp.body());
                    if (cim.find()) {
                        String found = cim.group(1);
                        cachedClientId = found;
                        future.complete(found);
                        return;
                    }
                }
                // Default fallback if parsing fails
                future.complete("tHVbSbDP4TaoMbPppngv6Tdry83vNbMQ");
            } catch (Exception e) {
                future.complete("tHVbSbDP4TaoMbPppngv6Tdry83vNbMQ");
            }
        });
        return future;
    }

    public CompletableFuture<SearchResult> search(String query) {
        return search(query, 1, 6);
    }

    /**
     * Searches SoundCloud with pagination and filters out preview/DRM locked tracks.
     */
    public CompletableFuture<SearchResult> search(String query, int page, int pageSize) {
        int offset = Math.max(0, (page - 1) * pageSize);

        return getClientId().thenCompose(clientId -> {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String searchUrl = "https://api-v2.soundcloud.com/search/tracks?q=" + encodedQuery
                    + "&client_id=" + clientId
                    + "&limit=" + pageSize
                    + "&offset=" + offset;

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(searchUrl))
                    .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)")
                    .timeout(Duration.ofSeconds(8))
                    .build();

            return httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .thenApply(resp -> {
                        try {
                            if (resp.statusCode() != 200) {
                                throw new RuntimeException("SoundCloud search returned HTTP " + resp.statusCode());
                            }

                            JsonNode root = objectMapper.readTree(resp.body());
                            int totalResults = root.path("total_results").asInt(0);
                            JsonNode collection = root.path("collection");

                            List<TrackInfo> tracks = new ArrayList<>();
                            for (JsonNode item : collection) {
                                long id = item.path("id").asLong();
                                String title = item.path("title").asText("");
                                String artist = item.path("user").path("username").asText("Unknown Artist");
                                long duration = item.path("duration").asLong(0);
                                String permalinkUrl = item.path("permalink_url").asText("");

                                String artwork = item.path("artwork_url").asText(null);
                                if (artwork == null || artwork.isEmpty() || "null".equals(artwork)) {
                                    artwork = item.path("user").path("avatar_url").asText(null);
                                }

                                // Inspect transcodings to ensure the track has a playable stream
                                boolean isPlayable = false;
                                JsonNode transcodings = item.path("media").path("transcodings");
                                for (JsonNode t : transcodings) {
                                    String proto = t.path("format").path("protocol").asText();
                                    String mime = t.path("format").path("mime_type").asText();
                                    String url = t.path("url").asText();

                                    // Skip DRM/Go+ encrypted streams
                                    if (url.contains("encrypted") || proto.contains("encrypted")) {
                                        continue;
                                    }

                                    // Valid playable formats: AAC/MP4 HLS, MP3 progressive, or Opus HLS
                                    if (("hls".equals(proto) && (mime.contains("mp4") || mime.contains("ogg") || mime.contains("opus")))
                                            || ("progressive".equals(proto) && mime.contains("mpeg"))) {
                                        isPlayable = true;
                                        break;
                                    }
                                }

                                String policy = item.path("policy").asText("ALLOW");
                                if ("BLOCK".equalsIgnoreCase(policy) || "SNIPPET".equalsIgnoreCase(policy)) {
                                    isPlayable = false;
                                }
                                // Filter out 30-second preview snippets so players get full tracks
                                if (duration > 0 && duration <= 35000) {
                                    isPlayable = false;
                                }

                                if (isPlayable && !title.isEmpty()) {
                                    tracks.add(new TrackInfo(id, title, artist, duration, permalinkUrl, artwork));
                                }
                            }

                            int totalPages = Math.max(1, (int) Math.ceil((double) totalResults / pageSize));
                            return new SearchResult(tracks, totalResults, page, totalPages);
                        } catch (Exception e) {
                            throw new RuntimeException("Error parsing SoundCloud search results: " + e.getMessage(), e);
                        }
                    });
        });
    }

    /**
     * Loads a specific audio track by direct URL.
     * Supports SoundCloud fMP4/AAC, progressive MP3, Opus HLS, and LavaPlayer generic fallback.
     */
    public void loadTrack(String url, Consumer<AudioTrack> onSuccess, Consumer<Throwable> onError) {
        if (url == null || url.trim().isEmpty()) {
            onError.accept(new IllegalArgumentException("Track URL cannot be empty"));
            return;
        }

        // If it's a SoundCloud URL, resolve via SoundCloud API to handle modern AAC fMP4 properly
        if (url.contains("soundcloud.com/")) {
            getClientId().thenAccept(clientId -> {
                try {
                    String resolveUrl = "https://api-v2.soundcloud.com/resolve?url="
                            + URLEncoder.encode(url, StandardCharsets.UTF_8)
                            + "&client_id=" + clientId;

                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(resolveUrl))
                            .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)")
                            .timeout(Duration.ofSeconds(8))
                            .build();

                    HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() == 200) {
                        JsonNode root = objectMapper.readTree(resp.body());
                        String title = root.path("title").asText("Unknown Track");
                        String artist = root.path("user").path("username").asText("Unknown Artist");
                        long duration = root.path("duration").asLong(0);
                        AudioTrackInfo trackInfo = new AudioTrackInfo(title, artist, duration, url, false, url);

                        JsonNode transcodings = root.path("media").path("transcodings");

                        // 1. Try progressive MP3 first (direct and fastest)
                        for (JsonNode t : transcodings) {
                            String proto = t.path("format").path("protocol").asText();
                            String mime = t.path("format").path("mime_type").asText();
                            String sUrl = t.path("url").asText();
                            if ("progressive".equals(proto) && mime.contains("mpeg")) {
                                String playUrl = fetchPlaybackUrl(sUrl, clientId);
                                if (playUrl != null) {
                                    loadWithPlayerManager(playUrl, onSuccess, onError);
                                    return;
                                }
                            }
                        }

                        // 2. Try AAC fMP4 HLS (modern SoundCloud format)
                        for (JsonNode t : transcodings) {
                            String proto = t.path("format").path("protocol").asText();
                            String mime = t.path("format").path("mime_type").asText();
                            String sUrl = t.path("url").asText();
                            if ("hls".equals(proto) && mime.contains("mp4") && !sUrl.contains("encrypted")) {
                                String m3u8Url = fetchPlaybackUrl(sUrl, clientId);
                                if (m3u8Url != null) {
                                    loadFragmentedMp4Track(trackInfo, m3u8Url, onSuccess, onError);
                                    return;
                                }
                            }
                        }

                        // 3. Try Opus HLS
                        for (JsonNode t : transcodings) {
                            String proto = t.path("format").path("protocol").asText();
                            String mime = t.path("format").path("mime_type").asText();
                            String sUrl = t.path("url").asText();
                            if ("hls".equals(proto) && (mime.contains("ogg") || mime.contains("opus")) && !sUrl.contains("encrypted")) {
                                loadWithPlayerManager(url, onSuccess, onError);
                                return;
                            }
                        }
                    }
                } catch (Exception e) {
                    CloudJukebox.LOGGER.warn("Custom SoundCloud track resolver failed for {}: {}, falling back to LavaPlayer default", url, e.getMessage());
                }

                // Fallback to default LavaPlayer loader
                loadWithPlayerManager(url, onSuccess, onError);
            }).exceptionally(ex -> {
                loadWithPlayerManager(url, onSuccess, onError);
                return null;
            });
            return;
        }

        // Generic HTTP or other tracks
        loadWithPlayerManager(url, onSuccess, onError);
    }

    private String fetchPlaybackUrl(String streamUrl, String clientId) {
        try {
            String sep = streamUrl.contains("?") ? "&" : "?";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(streamUrl + sep + "client_id=" + clientId))
                    .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)")
                    .timeout(Duration.ofSeconds(6))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode node = objectMapper.readTree(resp.body());
                return node.path("url").asText(null);
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Downloads fMP4 segments and loads the track into an MpegAudioTrack.
     */
    private void loadFragmentedMp4Track(AudioTrackInfo trackInfo, String m3u8Url, Consumer<AudioTrack> onSuccess, Consumer<Throwable> onError) {
        CompletableFuture.runAsync(() -> {
            try {
                HttpRequest mReq = HttpRequest.newBuilder()
                        .uri(URI.create(m3u8Url))
                        .header("User-Agent", "Mozilla/5.0")
                        .timeout(Duration.ofSeconds(8))
                        .build();
                HttpResponse<String> mResp = httpClient.send(mReq, HttpResponse.BodyHandlers.ofString());
                String playlist = mResp.body();

                String initUrl = null;
                List<String> segmentUrls = new ArrayList<>();
                for (String line : playlist.split("\\r?\\n")) {
                    if (line.startsWith("#EXT-X-MAP:URI=\"")) {
                        initUrl = line.substring(16, line.length() - 1);
                    } else if (!line.startsWith("#") && !line.trim().isEmpty()) {
                        segmentUrls.add(line.trim());
                    }
                }

                if (initUrl == null || segmentUrls.isEmpty()) {
                    throw new RuntimeException("Invalid HLS fMP4 playlist: missing init or segments");
                }

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] initBytes = httpClient.send(HttpRequest.newBuilder().uri(URI.create(initUrl)).build(), HttpResponse.BodyHandlers.ofByteArray()).body();
                baos.write(initBytes);

                // Download segments concurrently
                List<CompletableFuture<byte[]>> futures = new ArrayList<>();
                for (String segUrl : segmentUrls) {
                    futures.add(CompletableFuture.supplyAsync(() -> {
                        try {
                            return httpClient.send(HttpRequest.newBuilder().uri(URI.create(segUrl)).build(), HttpResponse.BodyHandlers.ofByteArray()).body();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }, downloadPool));
                }

                for (CompletableFuture<byte[]> f : futures) {
                    baos.write(f.join());
                }

                byte[] totalData = baos.toByteArray();
                SeekableInputStream stream = new SeekableInputStream(totalData.length, 0) {
                    private int pos = 0;

                    @Override
                    public void seek(long position) {
                        pos = (int) Math.min(position, totalData.length);
                    }

                    @Override
                    public void seekHard(long position) {
                        seek(position);
                    }

                    @Override
                    public long getPosition() {
                        return pos;
                    }

                    @Override
                    public boolean canSeekHard() {
                        return true;
                    }

                    @Override
                    public int read() {
                        if (pos >= totalData.length) return -1;
                        return totalData[pos++] & 0xFF;
                    }

                    @Override
                    public int read(byte[] b, int off, int len) {
                        if (pos >= totalData.length) return -1;
                        int avail = Math.min(len, totalData.length - pos);
                        System.arraycopy(totalData, pos, b, off, avail);
                        pos += avail;
                        return avail;
                    }

                    @Override
                    public int available() {
                        return totalData.length - pos;
                    }

                    @Override
                    public List<com.sedmelluq.discord.lavaplayer.track.info.AudioTrackInfoProvider> getTrackInfoProviders() {
                        return Collections.emptyList();
                    }
                };

                MpegAudioTrack track = new MpegAudioTrack(trackInfo, stream);
                onSuccess.accept(track);
            } catch (Exception e) {
                CloudJukebox.LOGGER.error("Failed to load fragmented MP4 track: {}", e.getMessage());
                onError.accept(e);
            }
        });
    }

    private void loadWithPlayerManager(String url, Consumer<AudioTrack> onSuccess, Consumer<Throwable> onError) {
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
}
