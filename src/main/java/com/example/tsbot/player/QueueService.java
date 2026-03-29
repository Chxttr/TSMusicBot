package com.example.tsbot.player;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

@Service
public class QueueService {

    private final List<Track> queue = new LinkedList<>();
    private Track nowPlaying;

    public synchronized void add(Track track) {
        if (nowPlaying == null) {
            nowPlaying = track;
        } else {
            queue.add(track);
        }
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

    public synchronized Track skip() {
        if (queue.isEmpty()) {
            nowPlaying = null;
            return null;
        }

        nowPlaying = queue.remove(0);
        return nowPlaying;
    }

    // 🔹 NEW: only clears upcoming tracks
    public synchronized void clearQueue() {
        queue.clear();
    }

    // 🔹 NEW: full stop (used by !stop)
    public synchronized void stop() {
        queue.clear();
        nowPlaying = null;
    }
}