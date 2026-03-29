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
    private final HistoryService historyService;

    public PlayerCoordinatorService(
            QueueService queueService,
            PlaybackService playbackService,
            MediaResolverService mediaResolverService,
            HistoryService historyService
    ) {
        this.queueService = queueService;
        this.playbackService = playbackService;
        this.mediaResolverService = mediaResolverService;
        this.historyService = historyService;
    }

    public void handlePlay(LocalTeamspeakClientSocket client, String requestedBy, String query) {
        ResolvedTrack resolved = resolve(client, query);
        Track track = toTrack(resolved, requestedBy);
        boolean wasIdle = queueService.isIdle();

        queueService.add(track);

        if (wasIdle) {
            historyService.add(track);
            chat(client, "▶ " + bold(track) + " — *requested by " + requestedBy + "*");
            playbackService.play(client, track, () -> onTrackFinished(client, track));
        } else {
            chat(client, "Added to queue at **#" + queueService.getQueueSize() + "** — " + bold(track));
        }
    }

    /** Adds a song to the front of the queue so it plays next (or immediately if idle). */
    public void handleNext(LocalTeamspeakClientSocket client, String requestedBy, String query) {
        ResolvedTrack resolved = resolve(client, query);
        Track track = toTrack(resolved, requestedBy);
        boolean wasIdle = queueService.isIdle();

        queueService.addNext(track);

        if (wasIdle) {
            historyService.add(track);
            chat(client, "▶ " + bold(track) + " — *requested by " + requestedBy + "*");
            playbackService.play(client, track, () -> onTrackFinished(client, track));
        } else {
            chat(client, "Playing next — " + bold(track));
        }
    }

    public void handleQueue(LocalTeamspeakClientSocket client) {
        Track nowPlaying = queueService.getNowPlaying();

        if (nowPlaying == null) {
            chat(client, "*The queue is empty.*");
            return;
        }

        StringBuilder response = new StringBuilder();
        response.append("▶ ").append(bold(nowPlaying))
                .append(" ").append(formatProgress(playbackService.getPositionSeconds(), nowPlaying.getDurationSeconds()))
                .append(" — *requested by ").append(nowPlaying.getRequestedBy()).append("*");

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
            chat(client, "▶ " + bold(nowPlaying) + " "
                    + formatProgress(playbackService.getPositionSeconds(), nowPlaying.getDurationSeconds())
                    + " — *requested by " + nowPlaying.getRequestedBy() + "*");
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
            chat(client, "⏸ Paused.");
        }
    }

    public void handleResume(LocalTeamspeakClientSocket client) {
        if (!playbackService.isPaused()) {
            chat(client, "*Playback is not paused.*");
        } else {
            playbackService.resume();
            chat(client, "▶ Resumed.");
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
                chat(client, "⚠ No track at position **#" + index + "** in the queue.");
            }
            return;
        } catch (NumberFormatException ignored) {}

        Optional<Track> removed = queueService.removeByTitle(arg.trim());
        if (removed.isPresent()) {
            chat(client, "Removed **" + removed.get().getTitle() + "** from the queue.");
        } else {
            chat(client, "⚠ No track matching **" + arg + "** found in the queue.");
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
     * Dequeues the next track and starts playing it. If the queue is empty,
     * picks a random track from history (auto-play). Sends "queue ended" only
     * when history is also empty.
     */
    private void advanceQueue(LocalTeamspeakClientSocket client) {
        Track next = queueService.skip();

        if (next == null) {
            next = resolveFromHistory();
            if (next == null) {
                chat(client, "*Queue ended — no history to auto-play from.*");
                return;
            }
            queueService.add(next);
            chat(client, "🔀 " + bold(next) + " *(auto-play)*");
        } else {
            try {
                next = ensureResolved(next);
            } catch (Exception e) {
                log.error("Failed to re-resolve restored track '{}'", next.getTitle(), e);
                chat(client, "⚠ Could not load **" + next.getTitle() + "** — use !skip to try the next track.");
                queueService.updateNowPlaying(next);
                return;
            }
            queueService.updateNowPlaying(next);
            chat(client, "▶ " + bold(next));
        }

        historyService.add(next);
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
            chat(client, "⚠ Could not find: **" + query + "**");
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

    /**
     * Picks a random history entry and resolves it to a playable track.
     * Retries up to 3 times with different random entries on failure.
     * Returns {@code null} if history is empty or all attempts fail.
     */
    private Track resolveFromHistory() {
        for (int attempt = 1; attempt <= 3; attempt++) {
            PersistedTrack candidate = historyService.getRandom();
            if (candidate == null) return null;
            try {
                String input = (candidate.getWebpageUrl() != null && !candidate.getWebpageUrl().isBlank())
                        ? candidate.getWebpageUrl()
                        : candidate.getQuery();
                ResolvedTrack resolved = mediaResolverService.resolve(input);
                return toTrack(resolved, "autoplay");
            } catch (Exception e) {
                log.warn("Auto-play: failed to resolve '{}' (attempt {})", candidate.getTitle(), attempt, e);
            }
        }
        return null;
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
     * {@code client} is {@code null} (e.g. REST API callers).
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

//     private final QueueService queueService;
//     private final PlaybackService playbackService;
//     private final MediaResolverService mediaResolverService;
//     private final HistoryService historyService;

//     public PlayerCoordinatorService(
//             QueueService queueService,
//             PlaybackService playbackService,
//             MediaResolverService mediaResolverService,
//             HistoryService historyService
//     ) {
//         this.queueService = queueService;
//         this.playbackService = playbackService;
//         this.mediaResolverService = mediaResolverService;
//         this.historyService = historyService;
//     }

//     public void handlePlay(LocalTeamspeakClientSocket client, String requestedBy, String query) throws Exception {
//         int channelId = channelId(client);
//         client.sendChannelMessage(channelId, "Resolving **" + query + "**…");

//         ResolvedTrack resolved = mediaResolverService.resolve(query);
//         Track track = toTrack(resolved, requestedBy);
//         boolean wasIdle = queueService.isIdle();

//         queueService.add(track);

//         if (wasIdle) {
//             historyService.add(track);
//             client.sendChannelMessage(channelId, "▶ " + trackLink(track) + " — *requested by " + requestedBy + "*");
//             playbackService.play(client, track, () -> onTrackFinished(client, track));
//         } else {
//             client.sendChannelMessage(
//                     channelId,
//                     "Added to queue at **#" + queueService.getQueueSize() + "** — " + trackLink(track)
//             );
//         }
//     }

//     /** Adds a song to the front of the queue so it plays next (or immediately if idle). */
//     public void handleNext(LocalTeamspeakClientSocket client, String requestedBy, String query) throws Exception {
//         int channelId = channelId(client);
//         client.sendChannelMessage(channelId, "Resolving **" + query + "**…");

//         ResolvedTrack resolved = mediaResolverService.resolve(query);
//         Track track = toTrack(resolved, requestedBy);
//         boolean wasIdle = queueService.isIdle();

//         queueService.addNext(track);

//         if (wasIdle) {
//             historyService.add(track);
//             client.sendChannelMessage(channelId, "▶ " + trackLink(track) + " — *requested by " + requestedBy + "*");
//             playbackService.play(client, track, () -> onTrackFinished(client, track));
//         } else {
//             client.sendChannelMessage(channelId, "Playing next — " + trackLink(track));
//         }
//     }

//     public void handleQueue(LocalTeamspeakClientSocket client) throws Exception {
//         int channelId = channelId(client);
//         Track nowPlaying = queueService.getNowPlaying();

//         if (nowPlaying == null) {
//             client.sendChannelMessage(channelId, "*The queue is empty.*");
//             return;
//         }

//         StringBuilder response = new StringBuilder();
//         response.append("▶ ").append(trackLink(nowPlaying))
//                 .append(" — *requested by ").append(nowPlaying.getRequestedBy()).append("*");

//         List<Track> items = queueService.getQueue();
//         if (!items.isEmpty()) {
//             response.append("\n**Up next:**");
//             for (int i = 0; i < items.size(); i++) {
//                 response.append("\n").append(i + 1).append(". ").append(trackLink(items.get(i)));
//             }
//         }

//         client.sendChannelMessage(channelId, response.toString());
//     }

//     public void handleNowPlaying(LocalTeamspeakClientSocket client) throws Exception {
//         int channelId = channelId(client);
//         Track nowPlaying = queueService.getNowPlaying();

//         if (nowPlaying == null) {
//             client.sendChannelMessage(channelId, "*Nothing is currently playing.*");
//         } else {
//             client.sendChannelMessage(
//                     channelId,
//                     "▶ " + trackLink(nowPlaying) + " — *requested by " + nowPlaying.getRequestedBy() + "*"
//             );
//         }
//     }

//     public void handleSkip(LocalTeamspeakClientSocket client) throws Exception {
//         playbackService.skip();
//         advanceQueue(client);
//     }

//     public void handleClear(LocalTeamspeakClientSocket client) throws Exception {
//         int channelId = channelId(client);
//         queueService.clearQueue();
//         client.sendChannelMessage(channelId, "Queue cleared.");
//     }

//     public void handleStop(LocalTeamspeakClientSocket client) throws Exception {
//         int channelId = channelId(client);
//         playbackService.stop();
//         queueService.stop();
//         client.sendChannelMessage(channelId, "Playback stopped.");
//     }

//     // -------------------------------------------------------------------------

//     private synchronized void onTrackFinished(LocalTeamspeakClientSocket client, Track finishedTrack) {
//         try {
//             Track current = queueService.getNowPlaying();
//             if (current == null) return;

//             // Guard against stale callbacks from a previously skipped track
//             if (!Objects.equals(current.getWebpageUrl(), finishedTrack.getWebpageUrl())) {
//                 log.info("Ignoring stale finished callback for '{}'", finishedTrack.getTitle());
//                 return;
//             }

//             advanceQueue(client);
//         } catch (Exception e) {
//             log.error("Failed handling automatic next-track transition", e);
//         }
//     }

//     /**
//      * Dequeues the next track and starts playing it. If the queue is empty,
//      * picks a random track from history (auto-play). Sends "queue ended" only
//      * when history is also empty.
//      */
//     private void advanceQueue(LocalTeamspeakClientSocket client) throws Exception {
//         int channelId = channelId(client);
//         Track next = queueService.skip();

//         if (next == null) {
//             next = resolveFromHistory();
//             if (next == null) {
//                 client.sendChannelMessage(channelId, "*Queue ended — no history to auto-play from.*");
//                 return;
//             }
//             queueService.add(next);
//             client.sendChannelMessage(channelId, "🔀 " + trackLink(next) + " *(auto-play)*");
//         } else {
//             next = ensureResolved(next);
//             queueService.updateNowPlaying(next);
//             client.sendChannelMessage(channelId, "▶ " + trackLink(next));
//         }

//         historyService.add(next);
//         final Track trackToPlay = next;
//         playbackService.play(client, trackToPlay, () -> onTrackFinished(client, trackToPlay));
//     }

//     /**
//      * If {@code track} was restored from persistent storage its streamUrl will
//      * be {@code null}. Re-resolve using the stable webpageUrl (or query as
//      * fallback) to obtain a fresh CDN stream URL before playback.
//      */
//     private Track ensureResolved(Track track) throws Exception {
//         if (track.getStreamUrl() != null && !track.getStreamUrl().isBlank()) {
//             return track;
//         }
//         String input = (track.getWebpageUrl() != null && !track.getWebpageUrl().isBlank())
//                 ? track.getWebpageUrl()
//                 : track.getQuery();
//         log.info("Re-resolving restored track '{}'", track.getTitle());
//         ResolvedTrack resolved = mediaResolverService.resolve(input);
//         return toTrack(resolved, track.getRequestedBy());
//     }

//     /**
//      * Picks a random history entry and resolves it to a playable track.
//      * Retries up to 3 times with different random entries on failure.
//      * Returns {@code null} if history is empty or all attempts fail.
//      */
//     private Track resolveFromHistory() {
//         for (int attempt = 1; attempt <= 3; attempt++) {
//             PersistedTrack candidate = historyService.getRandom();
//             if (candidate == null) return null;
//             try {
//                 String input = (candidate.getWebpageUrl() != null && !candidate.getWebpageUrl().isBlank())
//                         ? candidate.getWebpageUrl()
//                         : candidate.getQuery();
//                 ResolvedTrack resolved = mediaResolverService.resolve(input);
//                 return toTrack(resolved, "autoplay");
//             } catch (Exception e) {
//                 log.warn("Auto-play: failed to resolve '{}' (attempt {})", candidate.getTitle(), attempt, e);
//             }
//         }
//         return null;
//     }

//     private static Track toTrack(ResolvedTrack resolved, String requestedBy) {
//         return new Track(
//                 resolved.getOriginalQuery(),
//                 requestedBy,
//                 resolved.getTitle(),
//                 resolved.getWebpageUrl(),
//                 resolved.getStreamUrl()
//         );
//     }

//     private static int channelId(LocalTeamspeakClientSocket client) throws Exception {
//         return client.getClientInfo(client.getClientId()).getChannelId();
//     }

//     private static String trackLink(Track track) {
//         return "**" + track.getTitle() + "**";
//     }
// }