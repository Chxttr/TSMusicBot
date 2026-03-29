package com.example.tsbot.ts3;

import com.example.tsbot.config.Ts3Properties;
import com.example.tsbot.player.PlayerCoordinatorService;
import com.example.tsbot.player.Ts3OpusMicrophone;
import com.github.manevolent.ts3j.api.Channel;
import com.github.manevolent.ts3j.identity.LocalIdentity;
import com.github.manevolent.ts3j.protocol.socket.client.LocalTeamspeakClientSocket;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.net.InetAddress;
import java.net.InetSocketAddress;

@Service
public class Ts3ClientService {

    private static final Logger log = LoggerFactory.getLogger(Ts3ClientService.class);
    private static final String IDENTITY_FILE_PATH = "/app/data/bot_identity.dat";

    private final Ts3Properties properties;
    private final PlayerCoordinatorService playerCoordinatorService;
    private final Ts3OpusMicrophone microphone;

    private LocalTeamspeakClientSocket client;

    public Ts3ClientService(
            Ts3Properties properties,
            PlayerCoordinatorService playerCoordinatorService,
            Ts3OpusMicrophone microphone
    ) {
        this.properties = properties;
        this.playerCoordinatorService = playerCoordinatorService;
        this.microphone = microphone;
    }

    @PostConstruct
    public void connect() {
        try {
            log.info("TS3 connect test starting: {}:{}", properties.getHost(), properties.getPort());
            log.info("Nickname: {}", properties.getNickname());
            log.info("Target channel name: {}", properties.getChannelName());

            client = new LocalTeamspeakClientSocket();
            client.setIdentity(loadOrCreateIdentity());
            client.setNickname(properties.getNickname());
            client.addListener(new Ts3EventListener(client, playerCoordinatorService));
            client.setMicrophone(microphone);

            client.connect(
                    new InetSocketAddress(
                            InetAddress.getByName(properties.getHost()),
                            properties.getPort()
                    ),
                    properties.getServerPassword(),
                    10000L
            );

            client.subscribeAll();

            log.info("TS3 connect test completed");

            Channel targetChannel = null;
            for (Channel channel : client.getChannels().get()) {
                if (channel.getName().equals(properties.getChannelName())) {
                    targetChannel = channel;
                    break;
                }
            }

            if (targetChannel == null) {
                log.warn("Target channel not found: {}", properties.getChannelName());
                return;
            }

            log.info("Found channel: {} (id={})", targetChannel.getName(), targetChannel.getId());

            client.joinChannel(targetChannel.getId(), "");
            log.info("Joined channel: {}", targetChannel.getName());

        } catch (Exception e) {
            log.error("TS3 connect test failed", e);
        }
    }

    private LocalIdentity loadOrCreateIdentity() throws Exception {
        File identityFile = new File(IDENTITY_FILE_PATH);

        if (identityFile.exists()) {
            log.info("Loading existing TS identity from {}", IDENTITY_FILE_PATH);
            return LocalIdentity.read(identityFile);
        }

        log.info("No TS identity found, generating new identity");
        LocalIdentity identity = LocalIdentity.generateNew(10);

        File parent = identityFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        identity.save(identityFile);
        log.info("Saved new TS identity to {}", IDENTITY_FILE_PATH);

        return identity;
    }

    public LocalTeamspeakClientSocket getClient() {
        return client;
    }
}