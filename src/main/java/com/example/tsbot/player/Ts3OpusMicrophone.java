package com.example.tsbot.player;

import com.github.manevolent.ts3j.audio.Microphone;
import com.github.manevolent.ts3j.enums.CodecType;
import org.springframework.stereotype.Component;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

@Component
public class Ts3OpusMicrophone implements Microphone {

    private final BlockingQueue<byte[]> packetQueue = new LinkedBlockingQueue<>();

    public void offerPacket(byte[] packet) {
        if (packet != null) {
            packetQueue.offer(packet);
        }
    }

    public void signalEndOfStream() {
        packetQueue.offer(new byte[0]);
    }

    public void clear() {
        packetQueue.clear();
    }

    @Override
    public boolean isReady() {
        return true;
    }

    @Override
    public CodecType getCodec() {
        return CodecType.OPUS_MUSIC;
    }

    @Override
    public byte[] provide() {
        byte[] packet = packetQueue.poll();
        return packet != null ? packet : new byte[0];
    }
}