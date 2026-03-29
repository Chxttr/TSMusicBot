# TSMusicBot — Project Context

> **Private project** — personal use on a private TeamSpeak server. Not intended for public distribution.

## What This Is

A **TeamSpeak 3 music bot** written in Java 21 / Spring Boot 3.3.5. It connects to a TS3 server as a regular client, joins a configured channel, listens for text message commands, resolves audio via `yt-dlp`, decodes it via `ffmpeg`, encodes it to Opus via `Concentus`, and streams it through the `ts3j` microphone API.

---

## Technology Stack

| Layer | Technology |
|---|---|
| Language / Runtime | Java 21 |
| Framework | Spring Boot 3.3.5 |
| TS3 client library | [ts3j](https://github.com/manevolent/ts3j) (via JitPack, master-SNAPSHOT) |
| Opus encoder | [Concentus](https://github.com/jaredmdobson/concentus) 1.0.2 — pure-Java |
| Media resolver | `yt-dlp` CLI + `yt-dlp-ejs` plugin (subprocess) |
| Audio decoder | `ffmpeg` CLI (subprocess) |
| JS runtime | Deno (installed in Docker, used by `yt-dlp-ejs`) |
| Packaging | Docker (multi-stage, Maven build → JRE runtime) |

---

## Package Structure

```
com.example.tsbot
├── TsBotApplication.java          — Spring Boot entry point
├── HealthController.java          — GET /api/health
├── config/
│   └── Ts3Properties.java         — @ConfigurationProperties(prefix="ts3")
├── ts3/
│   ├── Ts3ClientService.java      — TS3 connection lifecycle (@PostConstruct)
│   └── Ts3EventListener.java      — TS3Listener: switch-based command dispatch
├── player/
│   ├── PlayerCoordinatorService.java — Orchestrates all commands; null-safe TS3 client
│   ├── QueueService.java             — Persistent queue (JSON); add/addNext/removeAt/removeByTitle
│   ├── HistoryService.java           — Persistent history, max 100 deduped by webpageUrl
│   ├── PlaybackService.java          — ffmpeg subprocess + Opus pump; pause/resume support
│   ├── MediaResolverService.java     — yt-dlp subprocess, URL normalisation
│   ├── Ts3OpusMicrophone.java        — Implements ts3j Microphone interface
│   ├── PlaybackSession.java          — Holds ffmpeg Process + stream reference
│   ├── Track.java                    — Queued/playing track (query, title, urls, requestedBy)
│   ├── ResolvedTrack.java            — yt-dlp resolution result (title, streamUrl, webpageUrl)
│   ├── PersistedTrack.java           — JSON-serialisable snapshot (no streamUrl)
│   └── TrackResolutionException.java — Unchecked; thrown when yt-dlp fails to resolve
└── rest/
    └── MusicController.java          — REST API mirroring all chat commands
```

---

## Data / Audio Flow

```
User types in TS3 channel
        ↓
Ts3EventListener.onTextMessage()
        ↓
PlayerCoordinatorService.handle*()
        ↓
MediaResolverService.resolve(query)      ← yt-dlp subprocess
  → ResolvedTrack (title, streamUrl, webpageUrl)
        ↓
QueueService.add(track)
        ↓
PlaybackService.play(client, track, onFinished)
  → ffmpeg subprocess (stream URL → raw PCM s16le 48kHz stereo → pipe:1)
  → pumpThread: reads PCM frames (960 samples / 20ms), encodes to Opus (Concentus, 48kbps CBR), pushes to Ts3OpusMicrophone
  → watcherThread: fires onFinished callback when ffmpeg exits naturally
        ↓
Ts3OpusMicrophone.provide()              ← polled by ts3j per audio tick
  → returns next Opus packet from LinkedBlockingQueue
        ↓
ts3j streams Opus audio to TS3 server
```

---

## Chat Commands

| Command | Alias | Behaviour |
|---|---|---|
| `!play <query or URL>` | `!p` | Resolves and plays/queues a track |
| `!next <query or URL>` | `!n` | Resolves and inserts track at the front of the queue |
| `!skip` | `!s` | Skips the current track (or auto-plays from history) |
| `!pause` | — | Pauses playback (pump thread sleeps, ffmpeg blocks naturally) |
| `!resume` | — | Resumes paused playback |
| `!queue` | `!q` | Shows now-playing + upcoming queue |
| `!nowplaying` | `!np` | Shows current track + requester |
| `!history [1-100]` | — | Shows last N played tracks (default 10) |
| `!remove <pos or title>` | `!rm` | Removes track at 1-based position, or first title match |
| `!clear` | `!c` | Clears upcoming queue (current track keeps playing) |
| `!stop` | — | Stops playback and clears the entire queue |
| `!help` | `!h` | Shows command reference |
| `!ping` | — | Replies "pong" |

## REST API (`/api/music`)

| Method | Path | Description |
|---|---|---|
| `GET` | `/status` | Playing/paused state + now-playing |
| `GET` | `/queue` | Full queue state |
| `GET` | `/nowplaying` | Current track (204 if idle) |
| `GET` | `/history?limit=10` | Last N played tracks |
| `POST` | `/play` | Body: `{ "query": "...", "requestedBy": "..." }` |
| `POST` | `/next` | Same body — inserts at front of queue |
| `POST` | `/skip` | Skip current track |
| `POST` | `/pause` | Pause playback |
| `POST` | `/resume` | Resume playback |
| `POST` | `/stop` | Stop + clear queue |
| `DELETE` | `/queue` | Clear upcoming queue |
| `DELETE` | `/queue/{index}` | Remove by 1-based position |
| `DELETE` | `/queue/search?title=...` | Remove first title match |

---

## Configuration (`application.yml` / `config/application.yml`)

```yaml
ts3:
  host: ""            # TS3 server hostname/IP
  port: 9987          # TS3 server UDP port
  nickname: "Music"   # Bot nickname in TS3
  server-password: "" # TS3 server password (optional)
  channel-name: ""    # Channel to join on startup
```

Config override at runtime via `/app/config/application.yml` (Docker volume mount).

---

## Deployment

Multi-stage Dockerfile:
1. **Build stage** — Maven 3.9.9 + Eclipse Temurin 21, runs `mvn clean package`
2. **Runtime stage** — Eclipse Temurin 21 JRE, installs: `python3`, `pip`, `ffmpeg`, `yt-dlp`, `yt-dlp-ejs`, `Deno`

Volume/path conventions inside the container:
- `/app/config/` — external config override
- `/app/data/` — persistent bot identity (`bot_identity.dat`)
- `/app/logs/` — log output

Port: `8080` (HTTP, Spring Boot)

---

## Key Implementation Details

### `Ts3ClientService`
- Runs `connect()` at startup via `@PostConstruct` — **no reconnect logic**, failure is logged and swallowed.
- Loads TS3 identity from `/app/data/bot_identity.dat`; generates and saves a new one (security level 10) if missing.
- Registers `Ts3EventListener` and sets `Ts3OpusMicrophone` on the client socket.

### `PlaybackService` (thread model)
Three daemon threads per active playback session:
- **`ffmpeg-pcm-opus-pump`** — reads PCM from ffmpeg stdout, encodes with Concentus, enqueues Opus packets. When `paused`, sleeps in 50ms ticks; ffmpeg blocks on the pipe naturally (back-pressure).
- **`ffmpeg-stderr-reader`** — drains ffmpeg stderr to SLF4J info log.
- **`ffmpeg-watcher`** — waits for ffmpeg exit; triggers `onFinished` callback for natural completion.

Audio parameters: 48kHz, stereo (2ch), 960-sample frames (20ms), CBR 48kbps, `OPUS_APPLICATION_AUDIO`, VBR-constrained.

### `MediaResolverService`
- Plain text search queries are prefixed with `ytsearch1:` for yt-dlp.
- YouTube URLs are sanitized: strips all query params except `v=`; `youtu.be/<id>` is normalized to `youtube.com/watch?v=<id>`.
- Uses `yt-dlp --remote-components ejs:github` to enable the `yt-dlp-ejs` extended source plugin.
- Parses `--print title`, `--print url`, `--print webpage_url` output; filters `[youtube]`, `[info]`, `WARNING:` prefixed lines.

### `QueueService`
- Fully synchronized. `nowPlaying` is set directly when queue is empty; otherwise tracks are appended to a `LinkedList`.
- `addNext(track)` inserts at the head of the queue so it plays next.
- `clearQueue()` removes upcoming tracks only; `stop()` does a full reset including `nowPlaying`.
- **Persistence**: the upcoming queue (not `nowPlaying`) is serialised to `/app/data/queue.json` via Jackson on every mutation. Restored on startup — stream URLs are intentionally excluded (they expire); tracks are re-resolved by `ensureResolved()` before playback.

### `HistoryService`
- Keeps an ordered list (most-recently-played first) of up to 100 unique tracks.
- Uniqueness key is `webpageUrl`. Adding a duplicate moves it to the front.
- `getRandom()` returns a uniformly random entry for auto-play.
- Persisted to `/app/data/history.json` via Jackson on every write.

### `PlayerCoordinatorService`
- `advanceQueue(client)` is the single path for "play the next thing": dequeues next track → if queue is empty, calls `resolveFromHistory()` for auto-play → records to `HistoryService` → calls `PlaybackService.play()`.
- `ensureResolved(track)` re-resolves tracks whose `streamUrl` is null (restored from queue persistence).
- `resolveFromHistory()` picks a random history entry and re-resolves it; retries up to 3 times on failure.
- Stale `onTrackFinished` callbacks are detected by comparing `webpageUrl` (stable) rather than `streamUrl` (ephemeral).

### `Ts3OpusMicrophone`
- Implements ts3j's `Microphone` interface — `provide()` is polled by ts3j on each audio frame tick.
- Uses a `LinkedBlockingQueue` (non-blocking `poll()`) — returns an empty byte array when no packet is ready (silence).
- `signalEndOfStream()` inserts an empty packet as an end-of-stream sentinel.
- Codec reported to ts3j: `OPUS_MUSIC`.

---

## Known Gaps & Limitations

1. **No reconnect / fault tolerance** — if the TS3 connection drops, the bot stays disconnected until restart.
2. **Single server** — hardwired to one TS3 server; no multi-server support.
3. **No REST authentication** — the `/api/music` endpoints are unauthenticated. Acceptable for a local/private deployment; add a token or IP restriction if exposed externally.
4. **No seek** — no position tracking or seeking within a track.
5. **Identity path is hardcoded** to `/app/data/bot_identity.dat` — local dev without Docker requires that path to exist.
6. **`yt-dlp-ejs` dependency** — the `--remote-components ejs:github` flag requires Deno + the plugin to be on `PATH`.
7. **Queue not auto-resumed on restart** — restored queue tracks sit pending until a user triggers playback.
