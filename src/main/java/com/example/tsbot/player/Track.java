package com.example.tsbot.player;

public class Track {

    private final String query;
    private final String requestedBy;
    private final String title;
    private final String webpageUrl;
    private final String streamUrl;

    public Track(
            String query,
            String requestedBy,
            String title,
            String webpageUrl,
            String streamUrl
    ) {
        this.query = query;
        this.requestedBy = requestedBy;
        this.title = title;
        this.webpageUrl = webpageUrl;
        this.streamUrl = streamUrl;
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
}