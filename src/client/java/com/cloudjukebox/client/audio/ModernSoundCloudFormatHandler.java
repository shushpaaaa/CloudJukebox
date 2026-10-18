package com.cloudjukebox.client.audio;

import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudFormatHandler;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudM3uInfo;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudMp3SegmentDecoder;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudOpusSegmentDecoder;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudTrackFormat;

import java.util.List;

public class ModernSoundCloudFormatHandler implements SoundCloudFormatHandler {
    @Override
    public SoundCloudTrackFormat chooseBestFormat(List<SoundCloudTrackFormat> formats) {
        // 1. Prefer Opus HLS
        for (SoundCloudTrackFormat format : formats) {
            String proto = format.getProtocol();
            String mime = format.getMimeType();
            if ("hls".equals(proto) && (mime.contains("ogg") || mime.contains("opus"))) {
                return format;
            }
        }

        // 2. Match MP3 HLS (SoundCloud modern mime_type is "audio/mpegurl" or "audio/mpeg")
        for (SoundCloudTrackFormat format : formats) {
            String proto = format.getProtocol();
            String mime = format.getMimeType();
            if ("hls".equals(proto) && (mime.contains("mpeg") || mime.contains("mp3") || mime.contains("mpegurl"))) {
                return format;
            }
        }

        // 3. Progressive direct MP3 stream
        for (SoundCloudTrackFormat format : formats) {
            String proto = format.getProtocol();
            String mime = format.getMimeType();
            if ("progressive".equals(proto) && (mime.contains("mpeg") || mime.contains("mp3"))) {
                return format;
            }
        }

        // 4. Any HLS stream as fallback
        for (SoundCloudTrackFormat format : formats) {
            if ("hls".equals(format.getProtocol())) {
                return format;
            }
        }

        // 5. Final fallback
        if (!formats.isEmpty()) {
            return formats.get(0);
        }

        throw new RuntimeException("No audio formats available for this track on SoundCloud");
    }

    @Override
    public String buildFormatIdentifier(SoundCloudTrackFormat format) {
        String proto = format.getProtocol();
        String mime = format.getMimeType();
        if ("hls".equals(proto)) {
            if (mime.contains("ogg") || mime.contains("opus")) {
                return "O:" + format.getLookupUrl();
            } else {
                return "U:" + format.getLookupUrl();
            }
        } else if ("progressive".equals(proto)) {
            return "M:" + format.getLookupUrl();
        }
        return "U:" + format.getLookupUrl();
    }

    @Override
    public SoundCloudM3uInfo getM3uInfo(String identifier) {
        if (identifier.startsWith("O:")) {
            return new SoundCloudM3uInfo(identifier.substring(2), SoundCloudOpusSegmentDecoder::new);
        } else if (identifier.startsWith("U:")) {
            return new SoundCloudM3uInfo(identifier.substring(2), SoundCloudMp3SegmentDecoder::new);
        }
        return null;
    }

    @Override
    public String getMp3LookupUrl(String identifier) {
        if (identifier.startsWith("M:")) {
            return identifier.substring(2);
        }
        return null;
    }
}
