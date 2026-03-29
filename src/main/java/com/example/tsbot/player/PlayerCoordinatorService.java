package com.example.tsbot.player;

import com.github.manevolent.ts3j.protocol.socket.client.LocalTeamspeakClientSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PlayerCoordinatorService {

    private static final Logger log = LoggerFactory.getLogger(PlayerCoordinatorService.class);

    private final QueueService queueService;
    private final PlaybackService playbackService;
    private final MediaResolverService mediaResolverService;

    public PlayerCoordinatorService(
            QueueService queueService,
            PlaybackService playbackService,
            MediaResolverService mediaResolverService
    ) {
        this.queueService = queueService;
        this.playbackService = playbackService;
        this.mediaResolverService = mediaResolverService;
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
            client.sendChannelMessage(channelId, "queue ended");
        } else {
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
                client.sendChannelMessage(channelId, "queue ended");
                return;
            }

            client.sendChannelMessage(channelId, "now playing: " + next.getTitle());
            playbackService.play(client, next, () -> onTrackFinished(client, next));

        } catch (Exception e) {
            log.error("Failed handling automatic next-track transition", e);
        }
    }
}