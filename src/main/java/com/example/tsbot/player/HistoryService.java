package com.example.tsbot.player;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Keeps a capped, deduplicated history of played tracks and persists it to
 * {@value #HISTORY_FILE}. Tracks are ordered most-recently-played first.
 * Uniqueness is determined by {@code webpageUrl}.
 */
@Service
public class HistoryService {

    private static final Logger log = LoggerFactory.getLogger(HistoryService.class);
    private static final String HISTORY_FILE = "/app/data/history.json";
    private static final int MAX_HISTORY = 100;

    private final List<PersistedTrack> history;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public HistoryService() {
        this.history = loadFromDisk();
        log.info("Loaded {} tracks from play history", history.size());
    }

    /**
     * Records that {@code track} was played. If the track is already present
     * (same webpageUrl) it is moved to the front. The list is trimmed to
     * {@value #MAX_HISTORY} entries.
     */
    public synchronized void add(Track track) {
        if (track.getWebpageUrl() == null || track.getWebpageUrl().isBlank()) {
            return;
        }
        history.removeIf(t -> track.getWebpageUrl().equals(t.getWebpageUrl()));
        history.add(0, PersistedTrack.from(track));
        while (history.size() > MAX_HISTORY) {
            history.remove(history.size() - 1);
        }
        saveToDisk();
    }

    /** Returns a uniformly random entry, or {@code null} if history is empty. */
    public synchronized PersistedTrack getRandom() {
        if (history.isEmpty()) return null;
        return history.get(ThreadLocalRandom.current().nextInt(history.size()));
    }

    public synchronized int size() {
        return history.size();
    }

    // -------------------------------------------------------------------------

    private List<PersistedTrack> loadFromDisk() {
        File file = new File(HISTORY_FILE);
        if (!file.exists()) return new ArrayList<>();
        try {
            return objectMapper.readValue(file, new TypeReference<List<PersistedTrack>>() {});
        } catch (Exception e) {
            log.warn("Failed to load history from {}", HISTORY_FILE, e);
            return new ArrayList<>();
        }
    }

    private void saveToDisk() {
        try {
            File file = new File(HISTORY_FILE);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, history);
        } catch (Exception e) {
            log.warn("Failed to save history to {}", HISTORY_FILE, e);
        }
    }
}
