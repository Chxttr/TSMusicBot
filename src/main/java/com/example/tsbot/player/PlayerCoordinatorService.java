package com.example.tsbot.player;

import com.github.manevolent.ts3j.protocol.socket.client.LocalTeamspeakClientSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PlayerCoordinatorService {

    private static final Logger log = LoggerFactory.getLogger(PlayerCoordinatorService.class);

    private final QueueService queueService;
    private final PlaybackService playbackService;
    private final MediaResolverService mediaResolverService;
    private final AutoDjService autoDjService;

    public PlayerCoordinatorService(
            QueueService queueService,
            PlaybackService playbackService,
            MediaResolverService mediaResolverService,
            AutoDjService autoDjService
    ) {
        this.queueService = queueService;
        this.playbackService = playbackService;
        this.mediaResolverService = mediaResolverService;
        this.autoDjService = autoDjService;
    }

    public void handlePlay(LocalTeamspeakClientSocket client, String requestedBy, String query) throws Exception {
        int channelId = client.getClientInfo(client.getClientId()).getChannelId();

        client.sendChannelMessage(channelId, "resolving: " + query);

        ResolvedTrack resolved = mediaResolverService.resolve(query);
        boolean wasIdle = queueService.isIdle();

        Track track = new Track(
                resolved.getOriginalQuery(),
                requestedBy,
                resolved.getTitle(),
                resolved.getWebpageUrl(),
                resolved.getStreamUrl()
        );

        queueService.add(track);

        if (wasIdle) {
            autoDjService.recordPlay(track.getTitle());
            client.sendChannelMessage(channelId, "now playing: " + track.getTitle());
            playbackService.play(client, track, () -> onTrackFinished(client, track));
        } else {
            client.sendChannelMessage(
                    channelId,
                    "queued: " + track.getTitle() + " (position " + queueService.getQueueSize() + ")"
            );
        }
    }

    public void handleQueue(LocalTeamspeakClientSocket client) throws Exception {
        int channelId = client.getClientInfo(client.getClientId()).getChannelId();

        Track nowPlaying = queueService.getNowPlaying();
        var items = queueService.getQueue();

        if (nowPlaying == null) {
            client.sendChannelMessage(channelId, "queue is empty");
            return;
        }

        StringBuilder response = new StringBuilder();
        response.append("now playing: ").append(nowPlaying.getTitle());

        if (!items.isEmpty()) {
            response.append(" | next: ");
            for (int i = 0; i < items.size(); i++) {
                response.append(i + 1).append(". ").append(items.get(i).getTitle());
                if (i < items.size() - 1) {
                    response.append(" | ");
                }
            }
        }

        client.sendChannelMessage(channelId, response.toString());
    }

    public void handleNowPlaying(LocalTeamspeakClientSocket client) throws Exception {
        int channelId = client.getClientInfo(client.getClientId()).getChannelId();

        Track nowPlaying = queueService.getNowPlaying();
        if (nowPlaying == null) {
            client.sendChannelMessage(channelId, "nothing is playing");
        } else {
            client.sendChannelMessage(
                    channelId,
                    "now playing: " + nowPlaying.getTitle() + " (requested by " + nowPlaying.getRequestedBy() + ")"
            );
        }
    }

    public void handleSkip(LocalTeamspeakClientSocket client) throws Exception {
        int channelId = client.getClientInfo(client.getClientId()).getChannelId();

        playbackService.skip();

        Track next = queueService.skip();
        if (next == null) {
            if (autoDjService.isEnabled()) {
                triggerAutoDj(client, channelId);
            } else {
                client.sendChannelMessage(channelId, "queue ended");
            }
        } else {
            autoDjService.recordPlay(next.getTitle());
            client.sendChannelMessage(channelId, "skipped, now playing: " + next.getTitle());
            playbackService.play(client, next, () -> onTrackFinished(client, next));
        }
    }

    public void handleClear(LocalTeamspeakClientSocket client) throws Exception {
        int channelId = client.getClientInfo(client.getClientId()).getChannelId();

        queueService.clearQueue();
        client.sendChannelMessage(channelId, "queue cleared");
    }

    public void handleStop(LocalTeamspeakClientSocket client) throws Exception {
        int channelId = client.getClientInfo(client.getClientId()).getChannelId();

        playbackService.stop();
        queueService.stop();
        client.sendChannelMessage(channelId, "playback stopped and queue cleared");
    }

    public void handleAutoDjToggle(LocalTeamspeakClientSocket client, String args) throws Exception {
        int channelId = client.getClientInfo(client.getClientId()).getChannelId();

        if (args == null || args.isBlank()) {
            String status = autoDjService.isEnabled() ? "ON" : "OFF";
            client.sendChannelMessage(channelId,
                    "AutoDJ is " + status + " (model: " + autoDjService.getModel() + ")");
            return;
        }

        String arg = args.trim().toLowerCase();
        if ("on".equals(arg)) {
            autoDjService.setEnabled(true);
            client.sendChannelMessage(channelId, "AutoDJ enabled");
        } else if ("off".equals(arg)) {
            autoDjService.setEnabled(false);
            client.sendChannelMessage(channelId, "AutoDJ disabled");
        } else {
            client.sendChannelMessage(channelId, "usage: !autodj [on|off]");
        }
    }

    private synchronized void onTrackFinished(LocalTeamspeakClientSocket client, Track finishedTrack) {
        try {
            Track current = queueService.getNowPlaying();

            if (current == null) {
                return;
            }

            if (!current.getStreamUrl().equals(finishedTrack.getStreamUrl())) {
                log.info("Ignoring finished callback for old track '{}'", finishedTrack.getTitle());
                return;
            }

            Track next = queueService.skip();
            int channelId = client.getClientInfo(client.getClientId()).getChannelId();

            if (next == null) {
                if (autoDjService.isEnabled()) {
                    triggerAutoDj(client, channelId);
                } else {
                    client.sendChannelMessage(channelId, "queue ended");
                }
                return;
            }

            autoDjService.recordPlay(next.getTitle());
            client.sendChannelMessage(channelId, "now playing: " + next.getTitle());
            playbackService.play(client, next, () -> onTrackFinished(client, next));

        } catch (Exception e) {
            log.error("Failed handling automatic next-track transition", e);
        }
    }

    private void triggerAutoDj(LocalTeamspeakClientSocket client, int channelId) {
        Thread autoDjThread = new Thread(() -> {
            try {
                client.sendChannelMessage(channelId, "AutoDJ: finding new songs...");

                List<String> suggestions = autoDjService.suggestSongs();

                if (suggestions.isEmpty()) {
                    client.sendChannelMessage(channelId, "AutoDJ: no suggestions available, queue ended");
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

                        Track track = new Track(
                                suggestion,
                                "AutoDJ",
                                resolved.getTitle(),
                                resolved.getWebpageUrl(),
                                resolved.getStreamUrl()
                        );

                        boolean wasIdle = queueService.isIdle();
                        queueService.add(track);
                        added++;

                        if (wasIdle && !startedPlayback) {
                            startedPlayback = true;
                            autoDjService.recordPlay(track.getTitle());
                            client.sendChannelMessage(channelId, "AutoDJ now playing: " + track.getTitle());
                            playbackService.play(client, track, () -> onTrackFinished(client, track));
                        }

                    } catch (Exception e) {
                        log.warn("AutoDJ: failed to resolve '{}'", suggestion, e);
                    }
                }

                if (added > 1) {
                    client.sendChannelMessage(channelId, "AutoDJ: queued " + (added - 1) + " more songs");
                } else if (added == 0) {
                    client.sendChannelMessage(channelId, "AutoDJ: could not resolve any songs, queue ended");
                }

            } catch (Exception e) {
                log.error("AutoDJ fill failed", e);
                try {
                    client.sendChannelMessage(channelId, "AutoDJ: error - " + e.getMessage());
                } catch (Exception ignored) {
                }
            }
        }, "autodj-fill");

        autoDjThread.setDaemon(true);
        autoDjThread.start();
    }
}