package com.example.tsbot.player;

/**
 * Thrown when yt-dlp fails to resolve a query. Extends {@link RuntimeException}
 * so it does not need to be declared on every method in the call chain. The
 * coordinator sends an informative chat message before throwing, so callers
 * should NOT send a generic error message on top of this.
 */
public class TrackResolutionException extends RuntimeException {

    private final String query;

    public TrackResolutionException(String query, Throwable cause) {
        super("Could not resolve track: " + query, cause);
        this.query = query;
    }

    public String getQuery() {
        return query;
    }
}
