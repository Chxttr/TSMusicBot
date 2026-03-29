package com.example.tsbot.player;

public class Track {

    private final String query;
    private final String requestedBy;
    private final String title;
    private final String webpageUrl;
    private final String streamUrl;
    /** Duration in seconds, or -1 if unknown (live stream, etc.). */
    private final int durationSeconds;

    public Track(
            String query,
            String requestedBy,
            String title,
            String webpageUrl,
            String streamUrl,
            int durationSeconds
    ) {
        this.query = query;
        this.requestedBy = requestedBy;
        this.title = title;
        this.webpageUrl = webpageUrl;
        this.streamUrl = streamUrl;
        this.durationSeconds = durationSeconds;
    }

    public String getQuery() {
        return query;
    }

    public String getRequestedBy() {
        return requestedBy;
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