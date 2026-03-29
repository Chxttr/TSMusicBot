package com.example.tsbot.player;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Serialisable snapshot of a track used for queue and history persistence.
 * The {@code streamUrl} is intentionally excluded — yt-dlp CDN URLs are
 * time-limited and useless after a restart.
 */
public class PersistedTrack {

    private final String query;
    private final String requestedBy;
    private final String title;
    private final String webpageUrl;
    /** Duration in seconds, or -1 if unknown. Nullable to handle JSON written before this field existed. */
    private final Integer durationSeconds;

    @JsonCreator
    public PersistedTrack(
            @JsonProperty("query") String query,
            @JsonProperty("requestedBy") String requestedBy,
            @JsonProperty("title") String title,
            @JsonProperty("webpageUrl") String webpageUrl,
            @JsonProperty("durationSeconds") Integer durationSeconds
    ) {
        this.query = query;
        this.requestedBy = requestedBy;
        this.title = title;
        this.webpageUrl = webpageUrl;
        this.durationSeconds = durationSeconds;
    }

    public static PersistedTrack from(Track track) {
        return new PersistedTrack(
                track.getQuery(),
                track.getRequestedBy(),
                track.getTitle(),
                track.getWebpageUrl(),
                track.getDurationSeconds()
        );
    }

    /** Restores a {@link Track} with a {@code null} streamUrl — re-resolve before playing. */
    public Track toTrack() {
        return new Track(query, requestedBy, title, webpageUrl, null, durationSeconds != null ? durationSeconds : -1);
    }

    public String getQuery()        { return query; }
    public String getRequestedBy()  { return requestedBy; }
    public String getTitle()        { return title; }
    public String getWebpageUrl()   { return webpageUrl; }
    public Integer getDurationSeconds() { return durationSeconds; }
}
