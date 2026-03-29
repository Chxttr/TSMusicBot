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
            "\u25B6 **!play** (!p) *<song or URL>* \u2014 play or queue a track\n" +
            "\u23ED **!next** (!n) *<song or URL>* \u2014 queue a track to play next\n" +
            "\u23E9 **!skip** (!s) \u2014 skip the current track\n" +
            "\u23F8 **!pause** \u2014 pause playback\n" +
            "\u25B6 **!resume** \u2014 resume playback\n" +
            "\uD83D\uDCCB **!queue** (!q) \u2014 show the queue\n" +
            "\uD83C\uDFB5 **!nowplaying** (!np) \u2014 show the current track\n" +
            "\uD83D\uDD04 **!history** *[1-100]* \u2014 show recently played (default: 10)\n" +
            "\uD83D\uDDD1 **!remove** (!rm) *<position or title>* \u2014 remove a track from the queue\n" +
            "\uD83D\uDDD1 **!clear** (!c) \u2014 clear the upcoming queue\n" +
            "\u23F9 **!stop** \u2014 stop playback and clear the queue\n" +
            "\uD83E\uDD16 **!autodj** *[on|off]* \u2014 toggle AI-powered AutoDJ";

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

                case "!autodj" ->
                        playerCoordinatorService.handleAutoDjToggle(client, args.isEmpty() ? null : args);

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
                client.sendChannelMessage(channelId, "\u26A0 Error handling command.");
            } catch (Exception ignored) {
            }
        }
    }
}
