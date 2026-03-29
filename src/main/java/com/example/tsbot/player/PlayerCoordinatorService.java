package com.example.tsbot.player;

import com.github.manevolent.ts3j.protocol.socket.client.LocalTeamspeakClientSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class PlayerCoordinatorService {

    private static final Logger log = LoggerFactory.getLogger(PlayerCoordinatorService.class);

    private final QueueService queueService;
    private final PlaybackService playbackService;
    private final MediaResolverService mediaResolverService;
    private final AutoDjService autoDjService;
    private final HistoryService historyService;

    public PlayerCoordinatorService(
            QueueService queueService,
            PlaybackService playbackService,
            MediaResolverService mediaResolverService,
            AutoDjService autoDjService,
            HistoryService historyService
    ) {
        this.queueService = queueService;
        this.playbackService = playbackService;
        this.mediaResolverService = mediaResolverService;
        this.autoDjService = autoDjService;
        this.historyService = historyService;
    }

    public void handlePlay(LocalTeamspeakClientSocket client, String requestedBy, String query) {
        ResolvedTrack resolved = resolve(client, query);
        Track track = toTrack(resolved, requestedBy);
        boolean wasIdle = queueService.isIdle();

        queueService.add(track);

        if (wasIdle) {
            autoDjService.recordPlay(track.getTitle());
            historyService.add(track);
            chat(client, "\u25B6 " + bold(track) + " \u2014 *requested by " + requestedBy + "*");
            playbackService.play(client, track, () -> onTrackFinished(client, track));
        } else {
            chat(client, "Added to queue at **#" + queueService.getQueueSize() + "** \u2014 " + bold(track));
        }
    }

    /** Adds a song to the front of the queue so it plays next (or immediately if idle). */
    public void handleNext(LocalTeamspeakClientSocket client, String requestedBy, String query) {
        ResolvedTrack resolved = resolve(client, query);
        Track track = toTrack(resolved, requestedBy);
        boolean wasIdle = queueService.isIdle();

        queueService.addNext(track);

        if (wasIdle) {
            autoDjService.recordPlay(track.getTitle());
            historyService.add(track);
            chat(client, "\u25B6 " + bold(track) + " \u2014 *requested by " + requestedBy + "*");
            playbackService.play(client, track, () -> onTrackFinished(client, track));
        } else {
            chat(client, "Playing next \u2014 " + bold(track));
        }
    }

    public void handleQueue(LocalTeamspeakClientSocket client) {
        Track nowPlaying = queueService.getNowPlaying();

        if (nowPlaying == null) {
            chat(client, "*The queue is empty.*");
            return;
        }

        StringBuilder response = new StringBuilder();
        response.append("\u25B6 ").append(bold(nowPlaying))
                .append(" ").append(formatProgress(playbackService.getPositionSeconds(), nowPlaying.getDurationSeconds()))
                .append(" \u2014 *requested by ").append(nowPlaying.getRequestedBy()).append("*");

        List<Track> items = queueService.getQueue();
        if (!items.isEmpty()) {
            response.append("\n**Up next:**");
            for (int i = 0; i < items.size(); i++) {
                Track t = items.get(i);
                response.append("\n").append(i + 1).append(". ").append(bold(t));
                if (t.getDurationSeconds() > 0) {
                    response.append(" (").append(formatDuration(t.getDurationSeconds())).append(")");
                }
            }
        }

        chat(client, response.toString());
    }

    public void handleNowPlaying(LocalTeamspeakClientSocket client) {
        Track nowPlaying = queueService.getNowPlaying();

        if (nowPlaying == null) {
            chat(client, "*Nothing is currently playing.*");
        } else {
            chat(client, "\u25B6 " + bold(nowPlaying) + " "
                    + formatProgress(playbackService.getPositionSeconds(), nowPlaying.getDurationSeconds())
                    + " \u2014 *requested by " + nowPlaying.getRequestedBy() + "*");
        }
    }

    public void handleSkip(LocalTeamspeakClientSocket client) {
        playbackService.skip();
        advanceQueue(client);
    }

    public void handlePause(LocalTeamspeakClientSocket client) {
        if (playbackService.isPaused()) {
            chat(client, "*Already paused.*");
        } else if (!playbackService.isPlaying()) {
            chat(client, "*Nothing is playing.*");
        } else {
            playbackService.pause();
            chat(client, "\u23F8 Paused.");
        }
    }

    public void handleResume(LocalTeamspeakClientSocket client) {
        if (!playbackService.isPaused()) {
            chat(client, "*Playback is not paused.*");
        } else {
            playbackService.resume();
            chat(client, "\u25B6 Resumed.");
        }
    }

    public void handleHistory(LocalTeamspeakClientSocket client, int count) {
        int clamped = Math.max(1, Math.min(count, 100));
        List<PersistedTrack> entries = historyService.getHistory(clamped);

        if (entries.isEmpty()) {
            chat(client, "*No play history yet.*");
            return;
        }

        StringBuilder sb = new StringBuilder("**Last " + entries.size() + " played:**");
        for (int i = 0; i < entries.size(); i++) {
            sb.append("\n").append(i + 1).append(". **").append(entries.get(i).getTitle()).append("**");
        }
        chat(client, sb.toString());
    }

    public void handleRemove(LocalTeamspeakClientSocket client, String arg) {
        try {
            int index = Integer.parseInt(arg.trim());
            Optional<Track> removed = queueService.removeAt(index);
            if (removed.isPresent()) {
                chat(client, "Removed **" + removed.get().getTitle() + "** from the queue.");
            } else {
                chat(client, "\u26A0 No track at position **#" + index + "** in the queue.");
            }
            return;
        } catch (NumberFormatException ignored) {}

        Optional<Track> removed = queueService.removeByTitle(arg.trim());
        if (removed.isPresent()) {
            chat(client, "Removed **" + removed.get().getTitle() + "** from the queue.");
        } else {
            chat(client, "\u26A0 No track matching **" + arg + "** found in the queue.");
        }
    }

    public void handleClear(LocalTeamspeakClientSocket client) {
        queueService.clearQueue();
        chat(client, "Queue cleared.");
    }

    public void handleStop(LocalTeamspeakClientSocket client) {
        playbackService.stop();
        queueService.stop();
        chat(client, "Playback stopped.");
    }

    public void handleAutoDjToggle(LocalTeamspeakClientSocket client, String args) {
        if (args == null || args.isBlank()) {
            String status = autoDjService.isEnabled() ? "ON" : "OFF";
            chat(client, "AutoDJ is " + status + " (model: " + autoDjService.getModel() + ")");
            return;
        }

        String arg = args.trim().toLowerCase();
        if ("on".equals(arg)) {
            autoDjService.setEnabled(true);
            chat(client, "AutoDJ enabled");
        } else if ("off".equals(arg)) {
            autoDjService.setEnabled(false);
            chat(client, "AutoDJ disabled");
        } else {
            chat(client, "usage: !autodj [on|off]");
        }
    }

    // -------------------------------------------------------------------------

    private synchronized void onTrackFinished(LocalTeamspeakClientSocket client, Track finishedTrack) {
        try {
            Track current = queueService.getNowPlaying();
            if (current == null) return;

            // Guard against stale callbacks from a previously skipped track
            if (!Objects.equals(current.getWebpageUrl(), finishedTrack.getWebpageUrl())) {
                log.info("Ignoring stale finished callback for '{}'", finishedTrack.getTitle());
                return;
            }

            advanceQueue(client);
        } catch (Exception e) {
            log.error("Failed handling automatic next-track transition", e);
        }
    }

    /**
     * Dequeues the next track and starts playing it. If the queue is empty
     * and AutoDJ is enabled, triggers the Ollama-powered AutoDJ to fill
     * the queue. Otherwise sends "queue ended".
     */
    private void advanceQueue(LocalTeamspeakClientSocket client) {
        Track next = queueService.skip();

        if (next == null) {
            if (autoDjService.isEnabled()) {
                triggerAutoDj(client);
            } else {
                chat(client, "*Queue ended.*");
            }
            return;
        }

        try {
            next = ensureResolved(next);
        } catch (Exception e) {
            log.error("Failed to re-resolve restored track '{}'", next.getTitle(), e);
            chat(client, "\u26A0 Could not load **" + next.getTitle() + "** \u2014 use !skip to try the next track.");
            queueService.updateNowPlaying(next);
            return;
        }

        queueService.updateNowPlaying(next);
        autoDjService.recordPlay(next.getTitle());
        historyService.add(next);
        chat(client, "\u25B6 " + bold(next));

        final Track trackToPlay = next;
        playbackService.play(client, trackToPlay, () -> onTrackFinished(client, trackToPlay));
    }

    /**
     * Resolves a query via yt-dlp. Sends an informative error to the channel
     * on failure and throws {@link TrackResolutionException}.
     */
    private ResolvedTrack resolve(LocalTeamspeakClientSocket client, String query) {
        try {
            return mediaResolverService.resolve(query);
        } catch (Exception e) {
            chat(client, "\u26A0 Could not find: **" + query + "**");
            throw new TrackResolutionException(query, e);
        }
    }

    /**
     * If {@code track} was restored from persistent storage its streamUrl will
     * be {@code null}. Re-resolve using the stable webpageUrl (or query as
     * fallback) to obtain a fresh CDN stream URL before playback.
     */
    private Track ensureResolved(Track track) throws Exception {
        if (track.getStreamUrl() != null && !track.getStreamUrl().isBlank()) {
            return track;
        }
        String input = (track.getWebpageUrl() != null && !track.getWebpageUrl().isBlank())
                ? track.getWebpageUrl()
                : track.getQuery();
        log.info("Re-resolving restored track '{}'", track.getTitle());
        ResolvedTrack resolved = mediaResolverService.resolve(input);
        return toTrack(resolved, track.getRequestedBy());
    }

    private void triggerAutoDj(LocalTeamspeakClientSocket client) {
        Thread autoDjThread = new Thread(() -> {
            try {
                chat(client, "AutoDJ: finding new songs...");

                List<String> suggestions = autoDjService.suggestSongs();

                if (suggestions.isEmpty()) {
                    chat(client, "AutoDJ: no suggestions available, queue ended");
                    return;
                }

                boolean startedPlayback = false;
                int added = 0;

                for (String suggestion : suggestions) {
                    if (!autoDjService.isEnabled()) {
                        log.info("AutoDJ: disabled during fill, stopping");
                        break;
                    }

                    try {
                        ResolvedTrack resolved = mediaResolverService.resolve(suggestion);

                        // Double-check resolved title against play history
                        List<String> history = autoDjService.getPlayHistory();
                        boolean recentlyPlayed = history.stream()
                                .anyMatch(h -> AutoDjService.titlesMatch(h, resolved.getTitle()));

                        if (recentlyPlayed) {
                            log.info("AutoDJ: skipping '{}' (matches recently played)", resolved.getTitle());
                            continue;
                        }

                        Track track = toTrack(resolved, "AutoDJ");

                        boolean wasIdle = queueService.isIdle();
                        queueService.add(track);
                        added++;

                        if (wasIdle && !startedPlayback) {
                            startedPlayback = true;
                            autoDjService.recordPlay(track.getTitle());
                            historyService.add(track);
                            chat(client, "AutoDJ now playing: " + bold(track));
                            playbackService.play(client, track, () -> onTrackFinished(client, track));
                        }

                    } catch (Exception e) {
                        log.warn("AutoDJ: failed to resolve '{}'", suggestion, e);
                    }
                }

                if (added > 1) {
                    chat(client, "AutoDJ: queued " + (added - 1) + " more songs");
                } else if (added == 0) {
                    chat(client, "AutoDJ: could not resolve any songs, queue ended");
                }

            } catch (Exception e) {
                log.error("AutoDJ fill failed", e);
                chat(client, "AutoDJ: error - " + e.getMessage());
            }
        }, "autodj-fill");

        autoDjThread.setDaemon(true);
        autoDjThread.start();
    }

    private static Track toTrack(ResolvedTrack resolved, String requestedBy) {
        return new Track(
                resolved.getOriginalQuery(),
                requestedBy,
                resolved.getTitle(),
                resolved.getWebpageUrl(),
                resolved.getStreamUrl(),
                resolved.getDurationSeconds()
        );
    }

    private static String bold(Track track) {
        return "**" + track.getTitle() + "**";
    }

    /** Formats {@code [pos / total]} e.g. {@code [2:34 / 5:12]}. Omits total if unknown. */
    private static String formatProgress(long positionSeconds, int durationSeconds) {
        if (durationSeconds > 0) {
            return "[" + formatDuration(positionSeconds) + " / " + formatDuration(durationSeconds) + "]";
        }
        return "[" + formatDuration(positionSeconds) + "]";
    }

    private static String formatDuration(long totalSeconds) {
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        return h > 0
                ? String.format("%d:%02d:%02d", h, m, s)
                : String.format("%d:%02d", m, s);
    }

    /**
     * Sends {@code message} to the bot's current channel. No-op when
     * {@code client} is {@code null}.
     */
    private static void chat(LocalTeamspeakClientSocket client, String message) {
        if (client == null) return;
        try {
            int channelId = client.getClientInfo(client.getClientId()).getChannelId();
            client.sendChannelMessage(channelId, message);
        } catch (Exception e) {
            log.warn("Failed to send channel message", e);
        }
    }
}
