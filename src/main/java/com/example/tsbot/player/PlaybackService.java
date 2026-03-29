package com.example.tsbot.player;

import com.github.manevolent.ts3j.protocol.socket.client.LocalTeamspeakClientSocket;
import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Service
public class PlaybackService {

    private static final Logger log = LoggerFactory.getLogger(PlaybackService.class);

    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNELS = 2;
    private static final int FRAME_SIZE = 960; // 20 ms at 48kHz
    private static final int PCM_BYTES_PER_FRAME = FRAME_SIZE * CHANNELS * 2; // 16-bit stereo
    private static final int MAX_OPUS_PACKET_SIZE = 4000;

    private final Ts3OpusMicrophone microphone;

    private PlaybackSession currentSession;
    private Thread stderrReaderThread;
    private Thread watcherThread;
    private Thread pumpThread;

    private volatile boolean stopRequested = false;
    private volatile boolean paused = false;

    public PlaybackService(Ts3OpusMicrophone microphone) {
        this.microphone = microphone;
    }

    public synchronized void play(LocalTeamspeakClientSocket client, Track track, Runnable onFinished) {
        stop();

        try {
            List<String> command = new ArrayList<>();
            command.add("ffmpeg");
            command.add("-hide_banner");
            command.add("-loglevel");
            command.add("warning");
            command.add("-re");
            command.add("-reconnect");
            command.add("1");
            command.add("-reconnect_streamed");
            command.add("1");
            command.add("-reconnect_delay_max");
            command.add("5");
            command.add("-i");
            command.add(track.getStreamUrl());
            command.add("-f");
            command.add("s16le");
            command.add("-acodec");
            command.add("pcm_s16le");
            command.add("-ac");
            command.add(String.valueOf(CHANNELS));
            command.add("-ar");
            command.add(String.valueOf(SAMPLE_RATE));
            command.add("pipe:1");

            log.info("Starting ffmpeg for track='{}'", track.getTitle());

            ProcessBuilder pb = new ProcessBuilder(command);
            Process process = pb.start();

            stopRequested = false;
            microphone.clear();

            startErrorReader(process);

            InputStream pcmStream = process.getInputStream();
            currentSession = new PlaybackSession(track, process, pcmStream);

            startPump(pcmStream, track);
            startWatcher(process, track, onFinished);

            log.info("Playback session started for '{}'", track.getTitle());

        } catch (Exception e) {
            log.error("Failed to start playback for '{}'", track.getTitle(), e);
            stop();
        }
    }

    public synchronized PlaybackSession getCurrentSession() {
        return currentSession;
    }

    public synchronized boolean isPlaying() {
        return currentSession != null && currentSession.isAlive();
    }

    public synchronized void skip() {
        log.info("Skipping playback");
        stop();
    }

    public synchronized void pause() {
        if (isPlaying() && !paused) {
            paused = true;
            log.info("Playback paused");
        }
    }

    public synchronized void resume() {
        if (paused) {
            paused = false;
            log.info("Playback resumed");
        }
    }

    public boolean isPaused() {
        return paused;
    }

    public synchronized void stop() {
        stopRequested = true;
        paused = false;
        microphone.clear();

        if (currentSession != null) {
            log.info("Stopping playback for '{}'", currentSession.getTrack().getTitle());

            try {
                currentSession.destroy();
                Thread.sleep(300L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            if (currentSession.isAlive()) {
                currentSession.destroyForcibly();
            }

            currentSession = null;
        }

        if (pumpThread != null && pumpThread.isAlive()) {
            pumpThread.interrupt();
            pumpThread = null;
        }

        if (stderrReaderThread != null && stderrReaderThread.isAlive()) {
            stderrReaderThread.interrupt();
            stderrReaderThread = null;
        }

        if (watcherThread != null && watcherThread.isAlive()) {
            watcherThread.interrupt();
            watcherThread = null;
        }
    }

    private void startPump(InputStream pcmStream, Track track) {
        pumpThread = new Thread(() -> {
            try {
                OpusEncoder encoder = new OpusEncoder(SAMPLE_RATE, CHANNELS, OpusApplication.OPUS_APPLICATION_AUDIO);
                encoder.setBitrate(48000);
                encoder.setUseVBR(false);
                encoder.setUseConstrainedVBR(true);

                byte[] pcmBytes = new byte[PCM_BYTES_PER_FRAME];
                short[] pcmShorts = new short[FRAME_SIZE * CHANNELS];
                byte[] opusBuffer = new byte[MAX_OPUS_PACKET_SIZE];

                while (!stopRequested) {
                    while (paused && !stopRequested) {
                        Thread.sleep(50);
                    }
                    if (stopRequested) break;
                    readFully(pcmStream, pcmBytes, 0, pcmBytes.length);
                    littleEndianBytesToShorts(pcmBytes, pcmShorts);

                    int encoded = encoder.encode(
                            pcmShorts,
                            0,
                            FRAME_SIZE,
                            opusBuffer,
                            0,
                            opusBuffer.length
                    );

                    if (encoded > 0) {
                        byte[] packet = new byte[encoded];
                        System.arraycopy(opusBuffer, 0, packet, 0, encoded);
                        microphone.offerPacket(packet);
                    }
                }
            } catch (EOFException eof) {
                log.info("PCM stream ended for '{}'", track.getTitle());
                microphone.signalEndOfStream();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.debug("PCM pump interrupted for '{}'", track.getTitle());
            } catch (Exception e) {
                if (!stopRequested) {
                    log.error("PCM pump failed for '{}'", track.getTitle(), e);
                }
            }
        }, "ffmpeg-pcm-opus-pump");

        pumpThread.setDaemon(true);
        pumpThread.start();
    }

    private void startErrorReader(Process process) {
        stderrReaderThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info("ffmpeg: {}", line);
                }
            } catch (Exception e) {
                log.debug("ffmpeg stderr reader stopped: {}", e.getMessage());
            }
        }, "ffmpeg-stderr-reader");

        stderrReaderThread.setDaemon(true);
        stderrReaderThread.start();
    }

    private void startWatcher(Process process, Track track, Runnable onFinished) {
        watcherThread = new Thread(() -> {
            try {
                int exitCode = process.waitFor();

                if (stopRequested) {
                    log.info("Playback stopped manually for '{}' (ffmpeg exit code {})", track.getTitle(), exitCode);
                } else {
                    log.info("Playback finished naturally for '{}' (ffmpeg exit code {})", track.getTitle(), exitCode);

                    synchronized (PlaybackService.this) {
                        if (currentSession != null &&
                                currentSession.getTrack().getStreamUrl().equals(track.getStreamUrl())) {
                            currentSession = null;
                        }
                    }

                    if (onFinished != null) {
                        onFinished.run();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.debug("Playback watcher interrupted for '{}'", track.getTitle());
            } catch (Exception e) {
                log.error("Playback watcher failed for '{}'", track.getTitle(), e);
            }
        }, "ffmpeg-watcher");

        watcherThread.setDaemon(true);
        watcherThread.start();
    }

    private static void readFully(InputStream in, byte[] buffer, int offset, int length) throws Exception {
        int total = 0;
        while (total < length) {
            int read = in.read(buffer, offset + total, length - total);
            if (read == -1) {
                throw new EOFException("End of PCM stream");
            }
            total += read;
        }
    }

    private static void littleEndianBytesToShorts(byte[] src, short[] dst) {
        for (int i = 0, j = 0; i < dst.length; i++, j += 2) {
            int lo = src[j] & 0xFF;
            int hi = src[j + 1] << 8;
            dst[i] = (short) (hi | lo);
        }
    }
}