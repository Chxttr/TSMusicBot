package com.example.tsbot.ts3;

import com.example.tsbot.player.PlayerCoordinatorService;
import com.example.tsbot.player.TrackResolutionException;
import com.github.manevolent.ts3j.event.TS3Listener;
import com.github.manevolent.ts3j.event.TextMessageEvent;
import com.github.manevolent.ts3j.protocol.socket.client.LocalTeamspeakClientSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

public class Ts3EventListener implements TS3Listener {

    private static final Logger log = LoggerFactory.getLogger(Ts3EventListener.class);

    private static final String HELP_MESSAGE =
            "**Music Bot Commands**\n" +
            "▶ **!play** (!p) *<song or URL>* — play or queue a track\n" +
            "⏭ **!next** (!n) *<song or URL>* — queue a track to play next\n" +
            "⏩ **!skip** (!s) — skip the current track\n" +
            "⏸ **!pause** — pause playback\n" +
            "▶ **!resume** — resume playback\n" +
            "📋 **!queue** (!q) — show the queue\n" +
            "🎵 **!nowplaying** (!np) — show the current track\n" +
            "🕐 **!history** *[1-100]* — show recently played (default: 10)\n" +
            "🗑 **!remove** (!rm) *<position or title>* — remove a track from the queue\n" +
            "🗑 **!clear** (!c) — clear the upcoming queue\n" +
            "⏹ **!stop** — stop playback and clear the queue";

    private final LocalTeamspeakClientSocket client;
    private final PlayerCoordinatorService playerCoordinatorService;

    public Ts3EventListener(
            LocalTeamspeakClientSocket client,
            PlayerCoordinatorService playerCoordinatorService
    ) {
        this.client = client;
        this.playerCoordinatorService = playerCoordinatorService;
    }

    @Override
    public void onTextMessage(TextMessageEvent e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) return;
        if (e.getInvokerId() == client.getClientId()) return;

        msg = msg.trim();
        log.info("Message from {}: {}", e.getInvokerName(), msg);

        String[] parts = msg.split("\\s+", 2);
        String cmd = parts[0].toLowerCase(Locale.ROOT);
        String args = parts.length > 1 ? parts[1].trim() : "";

        try {
            int channelId = client.getClientInfo(client.getClientId()).getChannelId();

            switch (cmd) {
                case "!ping" ->
                        client.sendChannelMessage(channelId, "pong");

                case "!play", "!p" -> {
                    if (args.isEmpty()) {
                        client.sendChannelMessage(channelId, "Usage: **!play** *<song or URL>*");
                    } else {
                        playerCoordinatorService.handlePlay(client, e.getInvokerName(), args);
                    }
                }

                case "!next", "!n" -> {
                    if (args.isEmpty()) {
                        client.sendChannelMessage(channelId, "Usage: **!next** *<song or URL>*");
                    } else {
                        playerCoordinatorService.handleNext(client, e.getInvokerName(), args);
                    }
                }

                case "!skip", "!s" ->
                        playerCoordinatorService.handleSkip(client);

                case "!queue", "!q" ->
                        playerCoordinatorService.handleQueue(client);

                case "!nowplaying", "!np" ->
                        playerCoordinatorService.handleNowPlaying(client);

                case "!clear", "!c" ->
                        playerCoordinatorService.handleClear(client);

                case "!stop" ->
                        playerCoordinatorService.handleStop(client);

                case "!pause" ->
                        playerCoordinatorService.handlePause(client);

                case "!resume" ->
                        playerCoordinatorService.handleResume(client);

                case "!history" -> {
                    int count = 10;
                    if (!args.isEmpty()) {
                        try {
                            count = Integer.parseInt(args);
                        } catch (NumberFormatException ignored) {
                            client.sendChannelMessage(channelId, "Usage: **!history** *[1-100]*");
                            return;
                        }
                    }
                    playerCoordinatorService.handleHistory(client, count);
                }

                case "!remove", "!rm" -> {
                    if (args.isEmpty()) {
                        client.sendChannelMessage(channelId, "Usage: **!remove** *<position or title>*");
                    } else {
                        playerCoordinatorService.handleRemove(client, args);
                    }
                }

                case "!help", "!h" ->
                        client.sendChannelMessage(channelId, HELP_MESSAGE);
            }
        } catch (TrackResolutionException ex) {
            log.warn("Track resolution failed for {}: {}", e.getInvokerName(), ex.getMessage());
            // Informative error already sent to channel by coordinator
        } catch (Exception ex) {
            log.warn("Failed handling message from {}", e.getInvokerName(), ex);
            try {
                int channelId = client.getClientInfo(client.getClientId()).getChannelId();
                client.sendChannelMessage(channelId, "⚠ Error handling command.");
            } catch (Exception ignored) {
            }
        }
    }
}