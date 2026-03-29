package com.example.tsbot.player;

import java.io.InputStream;

public class PlaybackSession {

    private final Track track;
    private final Process ffmpegProcess;
    private final InputStream pcmStream;

    public PlaybackSession(Track track, Process ffmpegProcess, InputStream pcmStream) {
        this.track = track;
        this.ffmpegProcess = ffmpegProcess;
        this.pcmStream = pcmStream;
    }

    public Track getTrack() {
        return track;
    }

    public Process getFfmpegProcess() {
        return ffmpegProcess;
    }

    public InputStream getPcmStream() {
        return pcmStream;
    }

    public boolean isAlive() {
        return ffmpegProcess != null && ffmpegProcess.isAlive();
    }

    public void destroy() {
        if (ffmpegProcess != null && ffmpegProcess.isAlive()) {
            ffmpegProcess.destroy();
        }
    }

    public void destroyForcibly() {
        if (ffmpegProcess != null && ffmpegProcess.isAlive()) {
            ffmpegProcess.destroyForcibly();
        }
    }
}