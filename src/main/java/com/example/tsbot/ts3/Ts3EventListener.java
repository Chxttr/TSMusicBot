package com.example.tsbot.ts3;

import com.example.tsbot.player.PlayerCoordinatorService;
import com.github.manevolent.ts3j.event.TS3Listener;
import com.github.manevolent.ts3j.event.TextMessageEvent;
import com.github.manevolent.ts3j.protocol.socket.client.LocalTeamspeakClientSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

public class Ts3EventListener implements TS3Listener {

    private static final Logger log = LoggerFactory.getLogger(Ts3EventListener.class);

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
            }
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
}