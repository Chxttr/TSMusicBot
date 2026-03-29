package com.example.tsbot.player;

public class ResolvedTrack {

    private final String originalQuery;
    private final String title;
    private final String webpageUrl;
    private final String streamUrl;

    public ResolvedTrack(
            String originalQuery,
            String title,
            String webpageUrl,
            String streamUrl
    ) {
        this.originalQuery = originalQuery;
        this.title = title;
        this.webpageUrl = webpageUrl;
        this.streamUrl = streamUrl;
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
}