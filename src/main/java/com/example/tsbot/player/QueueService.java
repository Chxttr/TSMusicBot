package com.example.tsbot.player;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class QueueService {

    private static final Logger log = LoggerFactory.getLogger(QueueService.class);
    private static final String QUEUE_FILE = "/app/data/queue.json";

    private final LinkedList<Track> queue = new LinkedList<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private Track nowPlaying;

    public QueueService() {
        loadFromDisk();
    }

    /** Adds {@code track} to the end of the queue, or sets it as now-playing if idle. */
    public synchronized void add(Track track) {
        if (nowPlaying == null) {
            nowPlaying = track;
        } else {
            queue.addLast(track);
            persist();
        }
    }

    /** Inserts {@code track} at the front of the queue so it plays next, or plays immediately if idle. */
    public synchronized void addNext(Track track) {
        if (nowPlaying == null) {
            nowPlaying = track;
        } else {
            queue.addFirst(track);
            persist();
        }
    }

    /**
     * Replaces the now-playing reference in-place. Used after a restored track
     * (null streamUrl) has been re-resolved to avoid identity mismatches.
     */
    public synchronized void updateNowPlaying(Track track) {
        this.nowPlaying = track;
    }

    public synchronized Track getNowPlaying() {
        return nowPlaying;
    }

    public synchronized List<Track> getQueue() {
        return new ArrayList<>(queue);
    }

    public synchronized int getQueueSize() {
        return queue.size();
    }

    public synchronized boolean isIdle() {
        return nowPlaying == null;
    }

    /** Moves the queue head to now-playing and returns it, or returns {@code null} if the queue is empty. */
    public synchronized Track skip() {
        if (queue.isEmpty()) {
            nowPlaying = null;
            return null;
        }
        nowPlaying = queue.removeFirst();
        persist();
        return nowPlaying;
    }

    /** Clears upcoming tracks while leaving the current track playing. */
    public synchronized void clearQueue() {
        queue.clear();
        persist();
    }

    /** Removes the track at the given 1-based position in the upcoming queue. */
    public synchronized Optional<Track> removeAt(int oneBasedIndex) {
        if (oneBasedIndex < 1 || oneBasedIndex > queue.size()) return Optional.empty();
        Track removed = queue.remove(oneBasedIndex - 1);
        persist();
        return Optional.of(removed);
    }

    /** Removes the first upcoming track whose title contains {@code fragment} (case-insensitive). */
    public synchronized Optional<Track> removeByTitle(String fragment) {
        String lower = fragment.toLowerCase(Locale.ROOT);
        Iterator<Track> it = queue.iterator();
        while (it.hasNext()) {
            Track t = it.next();
            if (t.getTitle().toLowerCase(Locale.ROOT).contains(lower)) {
                it.remove();
                persist();
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }

    /** Stops all playback — clears both the queue and now-playing state. */
    public synchronized void stop() {
        queue.clear();
        nowPlaying = null;
        persist();
    }

    // -------------------------------------------------------------------------

    private void persist() {
        try {
            List<PersistedTrack> snapshot = new ArrayList<>(queue.size());
            for (Track t : queue) {
                snapshot.add(PersistedTrack.from(t));
            }
            File file = new File(QUEUE_FILE);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, snapshot);
        } catch (Exception e) {
            log.warn("Failed to persist queue to {}", QUEUE_FILE, e);
        }
    }

    private void loadFromDisk() {
        File file = new File(QUEUE_FILE);
        if (!file.exists()) return;
        try {
            List<PersistedTrack> persisted = objectMapper.readValue(file, new TypeReference<List<PersistedTrack>>() {});
            for (PersistedTrack pt : persisted) {
                queue.addLast(pt.toTrack());
            }
            log.info("Restored {} queued tracks from {}", queue.size(), QUEUE_FILE);
        } catch (Exception e) {
            log.warn("Failed to load queue from {}", QUEUE_FILE, e);
        }
    }
}