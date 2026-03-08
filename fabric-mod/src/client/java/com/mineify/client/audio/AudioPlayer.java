package com.mineify.client.audio;

import com.mineify.MineifyClient;
import com.mineify.MineifyConfig;
import com.mineify.network.packets.AudioStreamChunkPacket;
import com.mineify.network.packets.AudioStreamEndPacket;
import com.mineify.network.packets.AudioStreamStartPacket;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.sound.SoundCategory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineEvent;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Properties;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

@Environment(EnvType.CLIENT)
public class AudioPlayer {
    private static final int MAX_LOAD_ATTEMPTS = 1;

    private static AudioPlayer instance;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Mineify-Audio");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService prefetchExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "Mineify-Audio-Prefetch");
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentHashMap<String, Boolean> prefetchInFlight = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> preloadedVideoIds = new ConcurrentHashMap<>();
    private final Object streamLock = new Object();
    private volatile StreamState currentStream;

    private volatile Clip currentClip;
    private volatile String currentDownloadUrl = null;
    private volatile String currentTitle = "";
    private volatile boolean playing = false;
    private volatile boolean paused = false;
    private volatile boolean pendingPause = false;
    private volatile boolean pendingResume = false;
    private volatile long pendingScheduledStartNanos = 0L;
    private volatile boolean suppressStopCallback = false;
    private volatile float volume = 0.15f;
    private volatile boolean customOutputEnabled = false;
    private volatile String customOutputMixerName = "";
    private volatile boolean duckingEnabled = false;
    private volatile boolean duckingActive = false;
    private volatile float duckingStrength = 0.35f;
    private volatile boolean minecraftMusicSuppressed = false;
    private volatile boolean hadPreviousMinecraftMusicVolume = false;
    private volatile float previousMinecraftMusicVolume = 1.0f;
    private static final Path CLIENT_VOLUME_FILE = Path.of("config", "mineify-client.properties");
    private static final Path CLIENT_CACHE_DIR = Path.of("config", "mineify-cache");

    private AudioPlayer() {
        ClientSettings settings = loadClientSettings();
        this.volume = settings.volume;
        this.customOutputEnabled = settings.customOutputEnabled;
        this.customOutputMixerName = settings.customOutputMixerName;
        this.duckingEnabled = settings.duckingEnabled;
        this.duckingStrength = settings.duckingStrength;
    }

    public static AudioPlayer getInstance() {
        if (instance == null) {
            instance = new AudioPlayer();
        }
        return instance;
    }

    public void play(String downloadUrl, String title, String videoId, long serverElapsedMs, long packetReceivedAtNanos, long scheduledDelayMs, Runnable onLoaded) {
        executor.submit(() -> {
            stopStreamInternal();
            long scheduledStartNanos = packetReceivedAtNanos + Math.max(0L, scheduledDelayMs) * 1_000_000L;
            pendingScheduledStartNanos = scheduledStartNanos;
            // Preload-only packet: cache and acknowledge, but don't open audio yet.
            if (scheduledDelayMs <= 0L && serverElapsedMs <= 0L) {
                try {
                    String resolvedVideoId = resolveVideoId(videoId, downloadUrl);
                    Path cached = ensureCachedDownload(resolvedVideoId, downloadUrl);
                    if (cached != null && Files.exists(cached)) {
                        try {
                            long size = Files.size(cached);
                            if (size > 0 && resolvedVideoId != null && !resolvedVideoId.isBlank()) {
                                preloadedVideoIds.put(resolvedVideoId, Boolean.TRUE);
                            } else {
                                MineifyClient.LOGGER.warn("Preload cache empty for {} (size={})", resolvedVideoId, size);
                            }
                        } catch (IOException ignored) {
                        }
                    } else {
                        MineifyClient.LOGGER.warn("Preload cache missing for {}", resolvedVideoId);
                    }
                } catch (Exception e) {
                    MineifyClient.LOGGER.warn("Preload cache failed for {}: {}", videoId, e.toString());
                }
                if (onLoaded != null) {
                    try {
                        onLoaded.run();
                        MineifyClient.LOGGER.info("Sent ready for {}", videoId);
                    } catch (Exception callbackError) {
                        MineifyClient.LOGGER.warn("onLoaded callback failed: {}", callbackError.toString());
                    }
                }
                return;
            }
            // Seek updates reuse the loaded clip to avoid re-downloading and audio gaps.
            if (currentClip != null && currentClip.isOpen() && downloadUrl != null && downloadUrl.equals(currentDownloadUrl)) {
                // A follow-up PlayAudio packet for the same track (start after ready/seek).
                // If the server provides a scheduled delay, treat it as a start command.
                String resolvedId = resolveVideoId(videoId, downloadUrl);
                if (scheduledDelayMs > 0L || (resolvedId != null && preloadedVideoIds.containsKey(resolvedId))) {
                    pendingPause = false;
                    pendingResume = false;
                    if (resolvedId != null) {
                        preloadedVideoIds.remove(resolvedId);
                    }
                }
                applySeekToLoadedClip(title, serverElapsedMs, scheduledStartNanos);
                return;
            }
            // New track: always start in paused state until server resumes playback.
            if (serverElapsedMs <= 0L && scheduledDelayMs <= 0L) {
                pendingPause = true;
            }
            fadeOutAndStopCurrent();
            Exception lastError = null;
            for (int attempt = 1; attempt <= MAX_LOAD_ATTEMPTS; attempt++) {
                try {
                    String requestUrl = downloadUrl;
                    if (attempt > 1) {
                        String sep = downloadUrl.contains("?") ? "&" : "?";
                        requestUrl = downloadUrl + sep + "retry=" + attempt + "&ts=" + System.nanoTime();
                    }
                    String resolvedVideoId = resolveVideoId(videoId, requestUrl);
                    Path cachedPath = ensureCachedDownload(resolvedVideoId, requestUrl);
                    if (resolvedVideoId != null && !resolvedVideoId.isBlank()) {
                        preloadedVideoIds.remove(resolvedVideoId);
                    }
                    boolean readySent = false;
                    if (onLoaded != null) {
                        try {
                            onLoaded.run();
                            readySent = true;
                            MineifyClient.LOGGER.info("Sent ready for {}", resolvedVideoId);
                        } catch (Exception callbackError) {
                            MineifyClient.LOGGER.warn("onLoaded callback failed: {}", callbackError.toString());
                        }
                    }
                    String sourceLabel = cachedPath != null ? cachedPath.toAbsolutePath().toString() : requestUrl;
                    MineifyClient.LOGGER.info("Loading audio from: {} (attempt {}/{})", sourceLabel, attempt, MAX_LOAD_ATTEMPTS);
                    Clip clip = createClip();
                    if (cachedPath != null && Files.exists(cachedPath)) {
                        try (AudioInputStream sourceAis = AudioSystem.getAudioInputStream(cachedPath.toFile())) {
                            AudioFormat baseFormat = sourceAis.getFormat();
                            AudioFormat playFormat = new AudioFormat(
                                    AudioFormat.Encoding.PCM_SIGNED,
                                    baseFormat.getSampleRate(),
                                    16,
                                    baseFormat.getChannels(),
                                    baseFormat.getChannels() * 2,
                                    baseFormat.getSampleRate(),
                                    false
                            );

                            if (!baseFormat.matches(playFormat)) {
                                try (AudioInputStream convertedAis = AudioSystem.getAudioInputStream(playFormat, sourceAis)) {
                                    clip.open(convertedAis);
                                }
                            } else {
                                clip.open(sourceAis);
                            }
                        }
                    } else {
                        try (InputStream raw = new BufferedInputStream(new URL(requestUrl).openStream());
                             AudioInputStream sourceAis = AudioSystem.getAudioInputStream(raw)) {

                            AudioFormat baseFormat = sourceAis.getFormat();
                            AudioFormat playFormat = new AudioFormat(
                                    AudioFormat.Encoding.PCM_SIGNED,
                                    baseFormat.getSampleRate(),
                                    16,
                                    baseFormat.getChannels(),
                                    baseFormat.getChannels() * 2,
                                    baseFormat.getSampleRate(),
                                    false
                            );

                            if (!baseFormat.matches(playFormat)) {
                                try (AudioInputStream convertedAis = AudioSystem.getAudioInputStream(playFormat, sourceAis)) {
                                    clip.open(convertedAis);
                                }
                            } else {
                                clip.open(sourceAis);
                            }
                        }
                    }
                    MineifyClient.LOGGER.info("Opened clip for '{}' length={}ms", title, clip.getMicrosecondLength() / 1000);
                    clip.addLineListener(event -> {
                        if (event.getType() == LineEvent.Type.STOP && playing) {
                            if (suppressStopCallback) {
                                suppressStopCallback = false;
                                return;
                            }
                            playing = false;
                            paused = false;
                            currentTitle = "";
                            MineifyClient.LOGGER.info("Audio playback finished");
                        }
                    });

                    currentClip = clip;
                    currentDownloadUrl = downloadUrl;
                    currentTitle = title;
                    playing = false;
                    paused = false;
                    setClipVolume(clip, 0.0f);
                    if (onLoaded != null && !readySent) {
                        try {
                            onLoaded.run();
                        } catch (Exception callbackError) {
                            MineifyClient.LOGGER.warn("onLoaded callback failed: {}", callbackError.toString());
                        }
                    }

                    long startOffsetMs = calculateStartOffsetMs(serverElapsedMs, scheduledStartNanos);
                    if (startOffsetMs > 0) {
                        long clipLengthUs = clip.getMicrosecondLength();
                        long targetPositionUs = Math.max(0, Math.min(startOffsetMs * 1000, clipLengthUs));

                        if (targetPositionUs >= clipLengthUs) {
                            MineifyClient.LOGGER.info("Skipping playback for '{}' because track already finished", title);
                            stopInternal();
                            return;
                        }

                        clip.setMicrosecondPosition(targetPositionUs);
                        MineifyClient.LOGGER.info("Seeking '{}' to {} ms based on server real-time sync", title, targetPositionUs / 1000);
                    }

                    if (pendingPause && !pendingResume) {
                        paused = true;
                        MineifyClient.LOGGER.info("Loaded '{}' in paused state", title);
                    } else {
                        pendingPause = false;
                        pendingResume = false;
                        pendingScheduledStartNanos = 0L;
                        waitForScheduledStart(scheduledStartNanos);
                        long lateAdjustedOffsetMs = calculateStartOffsetMs(serverElapsedMs, scheduledStartNanos);
                        if (lateAdjustedOffsetMs != startOffsetMs) {
                            long clipLengthUs = clip.getMicrosecondLength();
                            long lateTargetUs = Math.max(0, Math.min(lateAdjustedOffsetMs * 1000, clipLengthUs));
                            clip.setMicrosecondPosition(lateTargetUs);
                        }
                        suppressMinecraftMusic();
                        clip.start();
                        playing = true;
                        fadeToVolume(clip, volume, MineifyConfig.getPlaybackCrossfadeMs());
                        MineifyClient.LOGGER.info("Playing: {}", title);
                    }
                    return;
                } catch (Exception e) {
                    lastError = e;
                    MineifyClient.LOGGER.warn("Audio load failed for '{}' (attempt {}/{}): {}", title, attempt, MAX_LOAD_ATTEMPTS, e.toString());
                    stopInternal();
                }
            }

            MineifyClient.LOGGER.error("Audio playback failed for: {}", title, lastError);
            playing = false;
            paused = false;
            currentTitle = "";
            currentDownloadUrl = null;
            restoreMinecraftMusic();
        });
    }

    public void handleStreamStart(AudioStreamStartPacket payload, Runnable onReady) {
        executor.submit(() -> {
            if (payload == null) {
                return;
            }
            stopClipInternal();
            if (currentStream != null && currentStream.streamId != payload.streamId()) {
                stopStreamInternal();
            }
            StreamState state;
            synchronized (streamLock) {
                if (currentStream != null && currentStream.streamId == payload.streamId()) {
                    state = currentStream;
                } else {
                    stopStreamInternal();
                    state = createStreamState(payload);
                    currentStream = state;
                }
            }
            if (state == null) {
                return;
            }
            state.dataSize = payload.dataSize();
            state.bytesReceived = 0L;
            if (payload.preloadOnly()) {
                if (onReady != null) {
                    try {
                        onReady.run();
                        MineifyClient.LOGGER.info("Sent ready for {}", payload.videoId());
                    } catch (Exception ignored) {
                    }
                }
                return;
            }
            state.scheduledStartNanos = System.nanoTime() + Math.max(0L, payload.scheduledDelayMs()) * 1_000_000L;
            state.startOffsetMs = Math.max(0L, payload.startOffsetMs());
            state.serverSkipped = payload.serverSkipped();
            state.progressBaseMs = state.startOffsetMs;
            if (!state.serverSkipped) {
                state.bytesToSkip = state.startOffsetMs * state.bytesPerMs;
            } else {
                state.bytesToSkip = 0L;
            }
            state.title = payload.title();
            state.videoId = payload.videoId();
            startStreamWriter(state);
        });
    }

    public void handleStreamChunk(AudioStreamChunkPacket payload) {
        if (payload == null) {
            return;
        }
        StreamState state = currentStream;
        if (state == null || state.streamId != payload.streamId()) {
            return;
        }
        state.bytesReceived += payload.data().length;
        state.queue.offer(payload.data());
        if (payload.last()) {
            state.queue.offer(StreamState.END_SENTINEL);
        }
    }

    public void handleStreamEnd(AudioStreamEndPacket payload) {
        if (payload == null) {
            return;
        }
        StreamState state = currentStream;
        if (state == null || state.streamId != payload.streamId()) {
            return;
        }
        state.queue.offer(StreamState.END_SENTINEL);
    }

    public void stop() {
        executor.submit(this::stopInternal);
    }

    public void pause() {
        executor.submit(this::pauseInternal);
    }

    public void resume() {
        executor.submit(this::resumeInternal);
    }

    public void prefetch(String downloadUrl, String videoId) {
        if (downloadUrl == null || downloadUrl.isBlank()) {
            return;
        }
        String resolvedVideoId = resolveVideoId(videoId, downloadUrl);
        if (resolvedVideoId == null || resolvedVideoId.isBlank()) {
            return;
        }
        if (prefetchInFlight.putIfAbsent(resolvedVideoId, Boolean.TRUE) != null) {
            return;
        }
        prefetchExecutor.submit(() -> {
            try {
                ensureCachedDownload(resolvedVideoId, downloadUrl);
                if (resolvedVideoId != null && !resolvedVideoId.isBlank()) {
                    preloadedVideoIds.put(resolvedVideoId, Boolean.TRUE);
                }
            } catch (IOException e) {
                MineifyClient.LOGGER.warn("Prefetch failed for {}: {}", resolvedVideoId, e.toString());
            } finally {
                prefetchInFlight.remove(resolvedVideoId);
            }
        });
    }
    private void applySeekToLoadedClip(String title, long serverElapsedMs, long scheduledStartNanos) {
        Clip clip = currentClip;
        if (clip == null || !clip.isOpen()) {
            return;
        }
        long startOffsetMs = calculateStartOffsetMs(serverElapsedMs, scheduledStartNanos);
        long clipLengthUs = clip.getMicrosecondLength();
        long targetPositionUs = Math.max(0, Math.min(startOffsetMs * 1000, clipLengthUs));
        if (targetPositionUs >= clipLengthUs) {
            stopInternal();
            return;
        }
        if (clip.isRunning()) {
            suppressStopCallback = true;
            clip.stop();
        }
        clip.setMicrosecondPosition(targetPositionUs);
        currentTitle = title;
        if (!pendingPause) {
            waitForScheduledStart(scheduledStartNanos);
            long lateAdjustedOffsetMs = calculateStartOffsetMs(serverElapsedMs, scheduledStartNanos);
            long lateTargetUs = Math.max(0, Math.min(lateAdjustedOffsetMs * 1000, clipLengthUs));
            clip.setMicrosecondPosition(lateTargetUs);
            suppressMinecraftMusic();
            clip.start();
            playing = true;
            paused = false;
            applyVolume(clip);
            pendingScheduledStartNanos = 0L;
        } else {
            paused = true;
            playing = false;
        }
    }

    private long calculateStartOffsetMs(long serverElapsedMs, long scheduledStartNanos) {
        // Never convert local load/decode delay into playback offset.
        // Keep only tiny jitter compensation for packet scheduling variance.
        long elapsedSinceScheduledMs = Math.max(0L, (System.nanoTime() - scheduledStartNanos) / 1_000_000L);
        long adjustmentMs = Math.min(200L, elapsedSinceScheduledMs);
        return Math.max(0, serverElapsedMs) + adjustmentMs;
    }

    private void waitForScheduledStart(long scheduledStartNanos) {
        long remainingMs = Math.max(0L, (scheduledStartNanos - System.nanoTime()) / 1_000_000L);
        if (remainingMs <= 0L) {
            return;
        }
        try {
            Thread.sleep(remainingMs);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void pauseInternal() {
        pendingPause = true;
        pendingResume = false;
        pendingScheduledStartNanos = 0L;
        StreamState stream = currentStream;
        if (stream != null) {
            stream.paused = true;
            if (stream.line != null) {
                stream.line.stop();
            }
        }
        Clip clip = currentClip;
        if (clip != null && clip.isOpen() && clip.isRunning()) {
            fadeToVolume(clip, 0.0f, Math.min(250, MineifyConfig.getPlaybackCrossfadeMs()));
            suppressStopCallback = true;
            clip.stop();
            playing = false;
            paused = true;
        }
        restoreMinecraftMusic();
    }

    private void resumeInternal() {
        pendingPause = false;
        StreamState stream = currentStream;
        if (stream != null) {
            stream.paused = false;
            if (stream.line != null && stream.started) {
                stream.line.start();
            }
        }
        Clip clip = currentClip;
        if (clip == null || !clip.isOpen()) {
            pendingResume = true;
            return;
        }
        if (paused) {
            pendingResume = false;
            suppressMinecraftMusic();
            setClipVolume(clip, 0.0f);
            long scheduledStartNanos = pendingScheduledStartNanos;
            if (scheduledStartNanos > System.nanoTime()) {
                waitForScheduledStart(scheduledStartNanos);
            }
            pendingScheduledStartNanos = 0L;
            clip.start();
            fadeToVolume(clip, volume, Math.min(250, MineifyConfig.getPlaybackCrossfadeMs()));
            paused = false;
            playing = true;
        }
    }

    private void stopInternal() {
        pendingPause = false;
        pendingResume = false;
        pendingScheduledStartNanos = 0L;
        playing = false;
        paused = false;
        currentTitle = "";
        currentDownloadUrl = null;
        stopStreamInternal();
        stopClipInternal();
        restoreMinecraftMusic();
    }

    private void fadeOutAndStopCurrent() {
        Clip clip = currentClip;
        if (clip != null && clip.isOpen()) {
            fadeToVolume(clip, 0.0f, MineifyConfig.getPlaybackCrossfadeMs());
        }
        stopInternal();
    }

    private void stopClipInternal() {
        Clip clip = currentClip;
        if (clip != null) {
            suppressStopCallback = true;
            clip.stop();
            clip.close();
            currentClip = null;
        }
    }

    public boolean isPlaying() {
        return playing;
    }

    public boolean isPaused() {
        return paused;
    }

    public String getCurrentTitle() {
        return currentTitle;
    }

    public float getProgress() {
        StreamState stream = currentStream;
        if (stream != null && stream.durationMs > 0) {
            long playedMs = Math.max(0L, Math.round(stream.bytesWritten / stream.bytesPerMs));
            long elapsedMs = stream.progressBaseMs + playedMs;
            return Math.min(1f, elapsedMs / (float) stream.durationMs);
        }
        Clip clip = currentClip;
        if (clip != null && clip.getMicrosecondLength() > 0) {
            return (float) clip.getMicrosecondPosition() / clip.getMicrosecondLength();
        }
        return 0f;
    }

    public float getBufferedProgress() {
        StreamState stream = currentStream;
        if (stream != null && stream.dataSize > 0) {
            return Math.min(1f, stream.bytesReceived / (float) stream.dataSize);
        }
        return 1f;
    }

    public float getVolume() {
        return volume;
    }

    public void setVolume(float volume) {
        this.volume = Math.max(0.0f, Math.min(1.0f, volume));
        saveClientSettings();
        Clip clip = currentClip;
        if (clip != null && clip.isOpen()) {
            applyVolume(clip);
        }
        StreamState stream = currentStream;
        if (stream != null && stream.line != null) {
            applyVolume(stream.line);
        }
    }

    public boolean isCustomOutputEnabled() {
        return customOutputEnabled;
    }

    public String getCustomOutputMixerName() {
        return customOutputMixerName == null ? "" : customOutputMixerName;
    }

    public void setCustomOutputEnabled(boolean enabled) {
        this.customOutputEnabled = enabled;
        saveClientSettings();
    }

    public void setCustomOutputMixerName(String mixerName) {
        this.customOutputMixerName = mixerName == null ? "" : mixerName;
        saveClientSettings();
    }

    public List<String> getAvailableOutputMixers() {
        List<String> names = new java.util.ArrayList<>();
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            try {
                Mixer mixer = AudioSystem.getMixer(info);
                boolean supportsClip = mixer.isLineSupported(new javax.sound.sampled.DataLine.Info(Clip.class, null));
                boolean supportsSource = mixer.isLineSupported(new javax.sound.sampled.DataLine.Info(javax.sound.sampled.SourceDataLine.class, null));
                if (supportsClip || supportsSource) {
                    names.add(info.getName());
                    continue;
                }
                try {
                    Clip test = AudioSystem.getClip(info);
                    test.close();
                    names.add(info.getName());
                } catch (LineUnavailableException ignored) {
                }
            } catch (Exception ignored) {
            }
        }
        return names;
    }

    public boolean isDuckingEnabled() {
        return duckingEnabled;
    }

    public float getDuckingStrength() {
        return duckingStrength;
    }

    public void setDuckingEnabled(boolean enabled) {
        this.duckingEnabled = enabled;
        saveClientSettings();
        applyDuckingState();
    }

    public void setDuckingStrength(float strength) {
        this.duckingStrength = Math.max(0.1f, Math.min(0.9f, strength));
        saveClientSettings();
        applyDuckingState();
    }

    public void setDuckingActive(boolean active) {
        if (this.duckingActive == active) {
            return;
        }
        this.duckingActive = active;
        applyDuckingState();
    }

    private void applyVolume(Clip clip) {
        setClipVolume(clip, volume);
    }

    private void applyVolume(SourceDataLine line) {
        setLineVolume(line, volume);
    }

    private void setClipVolume(Clip clip, float linearVolume) {
        try {
            FloatControl control = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
            float effectiveVolume = applyDucking(linearVolume);
            float dB = (float) (20.0 * Math.log10(Math.max(effectiveVolume, 0.0001)));
            dB = Math.max(dB, control.getMinimum());
            dB = Math.min(dB, control.getMaximum());
            control.setValue(dB);
        } catch (IllegalArgumentException e) {
            // Volume control not available
        }
    }

    private void setLineVolume(SourceDataLine line, float linearVolume) {
        try {
            FloatControl control = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
            float effectiveVolume = applyDucking(linearVolume);
            float dB = (float) (20.0 * Math.log10(Math.max(effectiveVolume, 0.0001)));
            dB = Math.max(dB, control.getMinimum());
            dB = Math.min(dB, control.getMaximum());
            control.setValue(dB);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void fadeToVolume(Clip clip, float targetVolume, int durationMs) {
        if (clip == null || !clip.isOpen()) {
            return;
        }
        int safeMs = Math.max(0, durationMs);
        if (safeMs == 0) {
            setClipVolume(clip, targetVolume);
            return;
        }
        int steps = Math.max(1, Math.min(24, safeMs / 15));
        float start = applyDucking(this.volume);
        for (int i = 1; i <= steps; i++) {
            float t = i / (float) steps;
            float v = start + (applyDucking(targetVolume) - start) * t;
            setClipVolume(clip, v);
            try {
                Thread.sleep(Math.max(5L, safeMs / (long) steps));
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        setClipVolume(clip, targetVolume);
    }

    private void fadeToVolume(SourceDataLine line, float targetVolume, int durationMs) {
        if (line == null || !line.isOpen()) {
            return;
        }
        int safeMs = Math.max(0, durationMs);
        if (safeMs == 0) {
            setLineVolume(line, targetVolume);
            return;
        }
        int steps = Math.max(1, Math.min(24, safeMs / 15));
        float start = applyDucking(this.volume);
        for (int i = 1; i <= steps; i++) {
            float t = i / (float) steps;
            float v = start + (applyDucking(targetVolume) - start) * t;
            setLineVolume(line, v);
            try {
                Thread.sleep(Math.max(5L, safeMs / (long) steps));
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        setLineVolume(line, targetVolume);
    }

    public void shutdown() {
        stopInternal();
        executor.shutdownNow();
        prefetchExecutor.shutdownNow();
    }

    private Path ensureCachedDownload(String videoId, String downloadUrl) throws IOException {
        if (videoId == null || videoId.isBlank()) {
            return null;
        }
        Files.createDirectories(CLIENT_CACHE_DIR);
        Path target = CLIENT_CACHE_DIR.resolve(videoId + ".wav");
        if (Files.exists(target)) {
            try {
                long size = Files.size(target);
                if (size > 0) {
                    return target;
                }
                Files.deleteIfExists(target);
                MineifyClient.LOGGER.warn("Cached file was empty, re-downloading: {}", target);
            } catch (IOException ignored) {
                return target;
            }
        }
        Path tmp = CLIENT_CACHE_DIR.resolve(videoId + ".part");
        try (InputStream in = new URL(downloadUrl).openStream()) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return target;
    }

    private InputStream openCachedOrRemoteStream(Path cachedPath, String downloadUrl) throws IOException {
        if (cachedPath != null && Files.exists(cachedPath)) {
            return Files.newInputStream(cachedPath, StandardOpenOption.READ);
        }
        return new URL(downloadUrl).openStream();
    }

    private String resolveVideoId(String explicitVideoId, String downloadUrl) {
        if (explicitVideoId != null && !explicitVideoId.isBlank()) {
            return explicitVideoId;
        }
        if (downloadUrl == null) {
            return "";
        }
        int idx = downloadUrl.lastIndexOf('/');
        if (idx >= 0 && idx + 1 < downloadUrl.length()) {
            String tail = downloadUrl.substring(idx + 1);
            int q = tail.indexOf('?');
            return q >= 0 ? tail.substring(0, q) : tail;
        }
        return "";
    }

    private void suppressMinecraftMusic() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return;
        }
        client.execute(() -> {
            if (client.options == null) {
                return;
            }

            float currentMusicVolume = (float) client.options.getSoundVolume(SoundCategory.MUSIC);
            if (!minecraftMusicSuppressed) {
                previousMinecraftMusicVolume = currentMusicVolume;
                hadPreviousMinecraftMusicVolume = true;
                minecraftMusicSuppressed = true;
            }

            if (currentMusicVolume > 0f) {
                client.options.getSoundVolumeOption(SoundCategory.MUSIC).setValue(0.0);
            }
            client.getSoundManager().stopSounds(null, SoundCategory.MUSIC);
        });
    }

    private void restoreMinecraftMusic() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            minecraftMusicSuppressed = false;
            hadPreviousMinecraftMusicVolume = false;
            return;
        }
        client.execute(() -> {
            if (client.options != null && minecraftMusicSuppressed && hadPreviousMinecraftMusicVolume) {
                client.options.getSoundVolumeOption(SoundCategory.MUSIC).setValue((double) previousMinecraftMusicVolume);
            }
            minecraftMusicSuppressed = false;
            hadPreviousMinecraftMusicVolume = false;
        });
    }

    private ClientSettings loadClientSettings() {
        float loadedVolume = 0.15f;
        boolean loadedCustomOutput = false;
        String loadedMixer = "";
        boolean loadedDucking = false;
        float loadedDuckingStrength = 0.35f;
        if (!Files.exists(CLIENT_VOLUME_FILE)) {
            return new ClientSettings(loadedVolume, loadedCustomOutput, loadedMixer, loadedDucking, loadedDuckingStrength);
        }
        Properties props = new Properties();
        try (var in = Files.newInputStream(CLIENT_VOLUME_FILE)) {
            props.load(in);
            String raw = props.getProperty("volume", "0.15");
            float parsed = Float.parseFloat(raw);
            loadedVolume = Math.max(0.0f, Math.min(1.0f, parsed));
            loadedCustomOutput = Boolean.parseBoolean(props.getProperty("customOutputEnabled", "false"));
            loadedMixer = props.getProperty("customOutputMixer", "");
            loadedDucking = Boolean.parseBoolean(props.getProperty("duckingEnabled", "false"));
            String duckStrength = props.getProperty("duckingStrength", "0.35");
            loadedDuckingStrength = Math.max(0.1f, Math.min(0.9f, Float.parseFloat(duckStrength)));
        } catch (IOException | NumberFormatException ignored) {
        }
        return new ClientSettings(loadedVolume, loadedCustomOutput, loadedMixer, loadedDucking, loadedDuckingStrength);
    }

    private void saveClientSettings() {
        Properties props = new Properties();
        props.setProperty("volume", Float.toString(this.volume));
        props.setProperty("customOutputEnabled", Boolean.toString(this.customOutputEnabled));
        props.setProperty("customOutputMixer", this.customOutputMixerName == null ? "" : this.customOutputMixerName);
        props.setProperty("duckingEnabled", Boolean.toString(this.duckingEnabled));
        props.setProperty("duckingStrength", Float.toString(this.duckingStrength));
        try {
            Files.createDirectories(CLIENT_VOLUME_FILE.getParent());
            try (var out = Files.newOutputStream(CLIENT_VOLUME_FILE)) {
                props.store(out, "Mineify client settings");
            }
        } catch (IOException ignored) {
        }
    }

    private float applyDucking(float linearVolume) {
        if (duckingEnabled && duckingActive) {
            return Math.max(0.0f, linearVolume * (1.0f - duckingStrength));
        }
        return linearVolume;
    }

    private void applyDuckingState() {
        Clip clip = currentClip;
        if (clip != null && clip.isOpen()) {
            applyVolume(clip);
        }
        StreamState stream = currentStream;
        if (stream != null && stream.line != null) {
            applyVolume(stream.line);
        }
    }

    private StreamState createStreamState(AudioStreamStartPacket payload) {
        try {
            AudioFormat format = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    payload.sampleRate(),
                    payload.bitsPerSample(),
                    payload.channels(),
                    payload.channels() * (payload.bitsPerSample() / 8),
                    payload.sampleRate(),
                    false
            );
            SourceDataLine line = createSourceLine(format);
            line.open(format);
            StreamState state = new StreamState(payload.streamId(), payload.videoId(), payload.title(), format, line, payload.durationMs());
            state.bytesPerMs = Math.max(1.0, (format.getSampleRate() * format.getFrameSize()) / 1000.0);
            return state;
        } catch (LineUnavailableException e) {
            MineifyClient.LOGGER.warn("Failed to open audio stream line: {}", e.toString());
            return null;
        }
    }

    private void startStreamWriter(StreamState state) {
        if (state.started || state.line == null) {
            return;
        }
        state.started = true;
        Thread writer = new Thread(() -> runStreamWriter(state), "Mineify-Stream-" + state.streamId);
        writer.setDaemon(true);
        state.writer = writer;
        writer.start();
    }

    private void runStreamWriter(StreamState state) {
        try {
            waitForScheduledStart(state.scheduledStartNanos);
            if (state.line != null) {
                setLineVolume(state.line, 0.0f);
                suppressMinecraftMusic();
                state.line.start();
                fadeToVolume(state.line, volume, MineifyConfig.getPlaybackCrossfadeMs());
            }
            playing = true;
            paused = false;
            currentTitle = state.title;
            int frameSize = Math.max(1, state.format.getFrameSize());
            while (true) {
                byte[] data = state.queue.take();
                if (data == StreamState.END_SENTINEL) {
                    break;
                }
                if (state.bytesToSkip > 0) {
                    int skipNow = (int) Math.min(state.bytesToSkip, data.length);
                    if (frameSize > 1) {
                        skipNow -= (skipNow % frameSize);
                    }
                    state.bytesToSkip -= skipNow;
                    if (state.bytesToSkip > 0 && state.bytesToSkip < frameSize) {
                        // Sub-frame seeking is not possible for PCM writes; drop remainder.
                        state.bytesToSkip = 0;
                    }
                    if (skipNow == data.length) {
                        continue;
                    }
                    byte[] remaining = new byte[data.length - skipNow];
                    System.arraycopy(data, skipNow, remaining, 0, remaining.length);
                    data = remaining;
                }

                if (state.pendingFrameBytes.length > 0) {
                    byte[] merged = new byte[state.pendingFrameBytes.length + data.length];
                    System.arraycopy(state.pendingFrameBytes, 0, merged, 0, state.pendingFrameBytes.length);
                    System.arraycopy(data, 0, merged, state.pendingFrameBytes.length, data.length);
                    data = merged;
                    state.pendingFrameBytes = new byte[0];
                }

                int writableLen = data.length;
                if (frameSize > 1) {
                    writableLen -= (writableLen % frameSize);
                }
                if (writableLen <= 0) {
                    state.pendingFrameBytes = data;
                    continue;
                }
                if (writableLen < data.length) {
                    int pendingLen = data.length - writableLen;
                    byte[] pending = new byte[pendingLen];
                    System.arraycopy(data, writableLen, pending, 0, pendingLen);
                    state.pendingFrameBytes = pending;
                }
                while (state.paused) {
                    Thread.sleep(10L);
                }
                if (state.line != null) {
                    int written = state.line.write(data, 0, writableLen);
                    state.bytesWritten += written;
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            if (state.line != null) {
                state.line.drain();
                state.line.stop();
                state.line.close();
            }
            if (currentStream == state) {
                currentStream = null;
            }
            playing = false;
            paused = false;
            currentTitle = "";
            restoreMinecraftMusic();
        }
    }

    private void stopStreamInternal() {
        StreamState stream = currentStream;
        if (stream == null) {
            return;
        }
        currentStream = null;
        stream.queue.offer(StreamState.END_SENTINEL);
        if (stream.line != null) {
            stream.line.stop();
            stream.line.close();
        }
        playing = false;
        paused = false;
        currentTitle = "";
        currentDownloadUrl = null;
    }

    private SourceDataLine createSourceLine(AudioFormat format) throws LineUnavailableException {
        if (customOutputEnabled && customOutputMixerName != null && !customOutputMixerName.isBlank()) {
            for (Mixer.Info info : AudioSystem.getMixerInfo()) {
                if (info.getName().equals(customOutputMixerName)) {
                    Mixer mixer = AudioSystem.getMixer(info);
                    DataLine.Info lineInfo = new DataLine.Info(SourceDataLine.class, format);
                    if (mixer.isLineSupported(lineInfo)) {
                        return (SourceDataLine) mixer.getLine(lineInfo);
                    }
                }
            }
        }
        DataLine.Info lineInfo = new DataLine.Info(SourceDataLine.class, format);
        return (SourceDataLine) AudioSystem.getLine(lineInfo);
    }

    private Clip createClip() throws LineUnavailableException {
        if (customOutputEnabled && customOutputMixerName != null && !customOutputMixerName.isBlank()) {
            for (Mixer.Info info : AudioSystem.getMixerInfo()) {
                if (info.getName().equals(customOutputMixerName)) {
                    try {
                        return AudioSystem.getClip(info);
                    } catch (LineUnavailableException ignored) {
                    }
                }
            }
        }
        return AudioSystem.getClip();
    }

    private static class StreamState {
        static final byte[] END_SENTINEL = new byte[0];

        final long streamId;
        String videoId;
        String title;
        final AudioFormat format;
        final SourceDataLine line;
        final BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
        final long durationMs;
        volatile boolean started;
        volatile boolean paused;
        volatile long scheduledStartNanos;
        volatile long startOffsetMs;
        volatile boolean serverSkipped;
        volatile double bytesPerMs = 1.0;
        volatile double bytesToSkip = 0.0;
        volatile long bytesWritten = 0L;
        volatile long progressBaseMs = 0L;
        volatile long dataSize = 0L;
        volatile long bytesReceived = 0L;
        volatile byte[] pendingFrameBytes = new byte[0];
        Thread writer;

        StreamState(long streamId, String videoId, String title, AudioFormat format, SourceDataLine line, long durationMs) {
            this.streamId = streamId;
            this.videoId = videoId;
            this.title = title;
            this.format = format;
            this.line = line;
            this.durationMs = durationMs;
        }
    }

    private static class ClientSettings {
        final float volume;
        final boolean customOutputEnabled;
        final String customOutputMixerName;
        final boolean duckingEnabled;
        final float duckingStrength;

        ClientSettings(float volume, boolean customOutputEnabled, String customOutputMixerName, boolean duckingEnabled, float duckingStrength) {
            this.volume = volume;
            this.customOutputEnabled = customOutputEnabled;
            this.customOutputMixerName = customOutputMixerName == null ? "" : customOutputMixerName;
            this.duckingEnabled = duckingEnabled;
            this.duckingStrength = duckingStrength;
        }
    }
}
