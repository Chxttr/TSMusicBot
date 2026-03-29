package com.example.tsbot.player;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Service
public class MediaResolverService {

    private static final Logger log = LoggerFactory.getLogger(MediaResolverService.class);

    public ResolvedTrack resolve(String query) throws Exception {
        String input = normalizeQuery(query);
        log.info("NEW RESOLVER ACTIVE - normalized input: {}", input);

        List<String> command = new ArrayList<>();
        command.add("yt-dlp");
        command.add("--remote-components");
        command.add("ejs:github");
        command.add("-f");
        command.add("bestaudio");
        command.add("--no-playlist");
        command.add("--print");
        command.add("title");
        command.add("--print");
        command.add("url");
        command.add("--print");
        command.add("webpage_url");
        command.add("--print");
        command.add("duration");
        command.add(input);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        Process process = pb.start();

        List<String> rawLines = new ArrayList<>();
        List<String> parsedLines = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String cleaned = line.trim();
                if (cleaned.isBlank()) {
                    continue;
                }

                rawLines.add(cleaned);

                if (cleaned.startsWith("WARNING:")) continue;
                if (cleaned.startsWith("[youtube]")) continue;
                if (cleaned.startsWith("[info]")) continue;

                parsedLines.add(cleaned);
            }
        }

        int exitCode = process.waitFor();

        if (exitCode != 0) {
            throw new IllegalStateException(
                    "yt-dlp exited with code " + exitCode + ", output: " + String.join(" || ", rawLines)
            );
        }

        if (parsedLines.size() < 3) {
            throw new IllegalStateException("yt-dlp returned unexpected output: " + parsedLines);
        }

        String title = parsedLines.get(0);
        String streamUrl = parsedLines.get(1);
        String webpageUrl = parsedLines.get(2);
        int durationSeconds = parsedLines.size() >= 4 ? parseDuration(parsedLines.get(3)) : -1;

        log.info("Resolved track: title='{}', webpageUrl='{}', duration={}s", title, webpageUrl, durationSeconds);

        return new ResolvedTrack(query, title, webpageUrl, streamUrl, durationSeconds);
    }

    private static int parseDuration(String raw) {
        try {
            double d = Double.parseDouble(raw);
            return d > 0 ? (int) d : -1;
        } catch (NumberFormatException e) {
            return -1; // "NA" for live streams
        }
    }

    private String normalizeQuery(String query) {
        String trimmed = query.trim();

        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return sanitizeYoutubeUrl(trimmed);
        }

        return "ytsearch1:" + trimmed;
    }

    private String sanitizeYoutubeUrl(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();

            if (host == null) return url;

            if (host.contains("youtu.be")) {
                String path = uri.getPath();
                if (path != null && path.length() > 1) {
                    String videoId = path.substring(1);
                    return "https://www.youtube.com/watch?v=" + videoId;
                }
                return url;
            }

            if (!host.contains("youtube.com")) return url;

            String rawQuery = uri.getRawQuery();
            if (rawQuery == null || rawQuery.isBlank()) return url;

            String videoId = null;
            for (String part : rawQuery.split("&")) {
                String[] kv = part.split("=", 2);
                String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
                String value = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";

                if ("v".equals(key)) {
                    videoId = value;
                    break;
                }
            }

            if (videoId != null && !videoId.isBlank()) {
                return "https://www.youtube.com/watch?v=" + videoId;
            }

            return url;
        } catch (Exception e) {
            log.warn("Failed to sanitize YouTube URL: {}", url, e);
            return url;
        }
    }
}