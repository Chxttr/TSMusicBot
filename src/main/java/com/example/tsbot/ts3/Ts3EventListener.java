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
        try {
            String msg = e.getMessage();
            if (msg == null) return;

            msg = msg.trim();
            if (msg.isEmpty()) return;

            if (e.getInvokerId() == client.getClientId()) {
                return;
            }

            log.info("Message from {}: {}", e.getInvokerName(), msg);

            int myChannelId = client.getClientInfo(client.getClientId()).getChannelId();

            if ("!ping".equalsIgnoreCase(msg)) {
                client.sendChannelMessage(myChannelId, "pong");
                return;
            }

            if ("!play".equalsIgnoreCase(msg)) {
                client.sendChannelMessage(myChannelId, "usage: !play <song or url>");
                return;
            }

            if (msg.toLowerCase(Locale.ROOT).startsWith("!play ")) {
                String query = msg.substring(6).trim();

                if (query.isEmpty()) {
                    client.sendChannelMessage(myChannelId, "usage: !play <song or url>");
                    return;
                }

                playerCoordinatorService.handlePlay(client, e.getInvokerName(), query);
                return;
            }

            if ("!queue".equalsIgnoreCase(msg)) {
                playerCoordinatorService.handleQueue(client);
                return;
            }

            if ("!nowplaying".equalsIgnoreCase(msg)) {
                playerCoordinatorService.handleNowPlaying(client);
                return;
            }

            if ("!skip".equalsIgnoreCase(msg)) {
                playerCoordinatorService.handleSkip(client);
                return;
            }

            if ("!clear".equalsIgnoreCase(msg)) {
                playerCoordinatorService.handleClear(client);
                return;
            }

            if ("!stop".equalsIgnoreCase(msg)) {
                playerCoordinatorService.handleStop(client);
                return;
            }

        } catch (Exception ex) {
            log.warn("Failed handling text message", ex);

            try {
                int myChannelId = client.getClientInfo(client.getClientId()).getChannelId();
                client.sendChannelMessage(myChannelId, "failed to handle command");
            } catch (Exception ignored) {
            }
        }
    }
}