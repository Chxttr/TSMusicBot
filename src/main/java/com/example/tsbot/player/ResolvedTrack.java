package com.example.tsbot.player;

public class ResolvedTrack {

    private final String originalQuery;
    private final String title;
    private final String webpageUrl;
    private final String streamUrl;
    /** Duration in seconds, or -1 if unknown (live stream, etc.). */
    private final int durationSeconds;

    public ResolvedTrack(
            String originalQuery,
            String title,
            String webpageUrl,
            String streamUrl,
            int durationSeconds
    ) {
        this.originalQuery = originalQuery;
        this.title = title;
        this.webpageUrl = webpageUrl;
        this.streamUrl = streamUrl;
        this.durationSeconds = durationSeconds;
    }

    public String getOriginalQuery() {
        return originalQuery;
    }

    public String getTitle() {
        return title;
    }

    public String getWebpageUrl() {
        return webpageUrl;
    }

    public String getStreamUrl() {
        return streamUrl;
    }

    public int getDurationSeconds() {
        return durationSeconds;
    }
}