package com.example.tsbot.rest;

import com.example.tsbot.player.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API for managing the music bot. Mirrors all chat commands.
 * Intended for use with Postman or a dedicated front-end.
 * Action endpoints call {@link PlayerCoordinatorService} with a {@code null}
 * TS3 client — coordinator methods are null-safe and simply skip chat messages.
 */
@RestController
@RequestMapping("/api/music")
public class MusicController {

    // --- DTOs ---

    record TrackDto(String title, String requestedBy, String webpageUrl) {
        static TrackDto of(Track t) {
            return t == null ? null : new TrackDto(t.getTitle(), t.getRequestedBy(), t.getWebpageUrl());
        }
    }

    record HistoryEntryDto(String title, String requestedBy, String webpageUrl) {
        static HistoryEntryDto of(PersistedTrack t) {
            return new HistoryEntryDto(t.getTitle(), t.getRequestedBy(), t.getWebpageUrl());
        }
    }

    record QueueStateDto(TrackDto nowPlaying, List<TrackDto> upcoming) {}

    record StatusDto(boolean playing, boolean paused, TrackDto nowPlaying) {}

    record PlayRequest(String query, String requestedBy) {}

    // --- Dependencies ---

    private final PlayerCoordinatorService coordinator;
    private final QueueService queueService;
    private final HistoryService historyService;
    private final PlaybackService playbackService;

    public MusicController(
            PlayerCoordinatorService coordinator,
            QueueService queueService,
            HistoryService historyService,
            PlaybackService playbackService
    ) {
        this.coordinator = coordinator;
        this.queueService = queueService;
        this.historyService = historyService;
        this.playbackService = playbackService;
    }

    // --- Read endpoints ---

    /** Returns current playback status and now-playing track. */
    @GetMapping("/status")
    public StatusDto status() {
        return new StatusDto(
                playbackService.isPlaying(),
                playbackService.isPaused(),
                TrackDto.of(queueService.getNowPlaying())
        );
    }

    /** Returns the full queue (now-playing + upcoming). */
    @GetMapping("/queue")
    public QueueStateDto queue() {
        List<TrackDto> upcoming = queueService.getQueue().stream().map(TrackDto::of).toList();
        return new QueueStateDto(TrackDto.of(queueService.getNowPlaying()), upcoming);
    }

    /** Returns the current track, or 204 if nothing is playing. */
    @GetMapping("/nowplaying")
    public ResponseEntity<TrackDto> nowPlaying() {
        Track t = queueService.getNowPlaying();
        return t != null ? ResponseEntity.ok(TrackDto.of(t)) : ResponseEntity.noContent().build();
    }

    /**
     * Returns recently played tracks.
     * @param limit how many to return (1–100, default 10)
     */
    @GetMapping("/history")
    public List<HistoryEntryDto> history(@RequestParam(defaultValue = "10") int limit) {
        int clamped = Math.max(1, Math.min(limit, 100));
        return historyService.getHistory(clamped).stream().map(HistoryEntryDto::of).toList();
    }

    // --- Action endpoints ---

    /**
     * Resolves and plays or queues a track.
     * Body: {@code { "query": "song or URL", "requestedBy": "optional name" }}
     */
    @PostMapping("/play")
    public ResponseEntity<?> play(@RequestBody PlayRequest req) {
        try {
            coordinator.handlePlay(null, requestedBy(req), req.query());
            return ResponseEntity.ok(TrackDto.of(queueService.getNowPlaying()));
        } catch (TrackResolutionException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Resolves and inserts a track at the front of the queue.
     * Body: {@code { "query": "song or URL", "requestedBy": "optional name" }}
     */
    @PostMapping("/next")
    public ResponseEntity<?> next(@RequestBody PlayRequest req) {
        try {
            coordinator.handleNext(null, requestedBy(req), req.query());
            return ResponseEntity.ok(TrackDto.of(queueService.getNowPlaying()));
        } catch (TrackResolutionException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Skips the current track. */
    @PostMapping("/skip")
    public ResponseEntity<Void> skip() {
        coordinator.handleSkip(null);
        return ResponseEntity.noContent().build();
    }

    /** Pauses playback. */
    @PostMapping("/pause")
    public ResponseEntity<Void> pause() {
        coordinator.handlePause(null);
        return ResponseEntity.noContent().build();
    }

    /** Resumes paused playback. */
    @PostMapping("/resume")
    public ResponseEntity<Void> resume() {
        coordinator.handleResume(null);
        return ResponseEntity.noContent().build();
    }

    /** Stops playback and clears the queue. */
    @PostMapping("/stop")
    public ResponseEntity<Void> stop() {
        coordinator.handleStop(null);
        return ResponseEntity.noContent().build();
    }

    /** Clears the upcoming queue without stopping the current track. */
    @DeleteMapping("/queue")
    public ResponseEntity<Void> clearQueue() {
        coordinator.handleClear(null);
        return ResponseEntity.noContent().build();
    }

    /**
     * Removes the track at the given 1-based position from the upcoming queue.
     * @param index 1-based position in the upcoming queue
     */
    @DeleteMapping("/queue/{index}")
    public ResponseEntity<Void> removeByIndex(@PathVariable int index) {
        coordinator.handleRemove(null, String.valueOf(index));
        return ResponseEntity.noContent().build();
    }

    /**
     * Removes the first upcoming track whose title contains the given string (case-insensitive).
     * @param title partial or full track title to match
     */
    @DeleteMapping("/queue/search")
    public ResponseEntity<Void> removeByTitle(@RequestParam String title) {
        coordinator.handleRemove(null, title);
        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------------------

    private static String requestedBy(PlayRequest req) {
        return (req.requestedBy() != null && !req.requestedBy().isBlank()) ? req.requestedBy() : "api";
    }
}
