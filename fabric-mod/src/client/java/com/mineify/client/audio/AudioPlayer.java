package com.mineify.client.audio;

import com.mineify.MineifyClient;
import com.mineify.MineifyConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.sound.SoundCategory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineEvent;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Environment(EnvType.CLIENT)
public class AudioPlayer {
    private static final int MAX_LOAD_ATTEMPTS = 1;

    private static AudioPlayer instance;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Mineify-Audio");
        t.setDaemon(true);
        return t;
    });

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
    private volatile boolean minecraftMusicSuppressed = false;
    private volatile boolean hadPreviousMinecraftMusicVolume = false;
    private volatile float previousMinecraftMusicVolume = 1.0f;
    private static final Path CLIENT_VOLUME_FILE = Path.of("config", "mineify-client.properties");

    private AudioPlayer() {
        this.volume = loadStoredVolume();
    }

    public static AudioPlayer getInstance() {
        if (instance == null) {
            instance = new AudioPlayer();
        }
        return instance;
    }

    public void play(String downloadUrl, String title, long serverElapsedMs, long packetReceivedAtNanos, long scheduledDelayMs, Runnable onLoaded) {
        executor.submit(() -> {
            long scheduledStartNanos = packetReceivedAtNanos + Math.max(0L, scheduledDelayMs) * 1_000_000L;
            pendingScheduledStartNanos = scheduledStartNanos;
            // Seek updates reuse the loaded clip to avoid re-downloading and audio gaps.
            if (currentClip != null && currentClip.isOpen() && downloadUrl != null && downloadUrl.equals(currentDownloadUrl)) {
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
                    MineifyClient.LOGGER.info("Downloading audio from: {} (attempt {}/{})", requestUrl, attempt, MAX_LOAD_ATTEMPTS);
                    URL url = new URL(requestUrl);
                    Clip clip = AudioSystem.getClip();
                    try (InputStream raw = url.openStream();
                         BufferedInputStream buffered = new BufferedInputStream(raw);
                         AudioInputStream sourceAis = AudioSystem.getAudioInputStream(buffered)) {

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
                    if (onLoaded != null) {
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

    public void stop() {
        executor.submit(this::stopInternal);
    }

    public void pause() {
        executor.submit(this::pauseInternal);
    }

    public void resume() {
        executor.submit(this::resumeInternal);
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
        Clip clip = currentClip;
        if (clip != null) {
            suppressStopCallback = true;
            clip.stop();
            clip.close();
            currentClip = null;
        }
        restoreMinecraftMusic();
    }

    private void fadeOutAndStopCurrent() {
        Clip clip = currentClip;
        if (clip != null && clip.isOpen()) {
            fadeToVolume(clip, 0.0f, MineifyConfig.getPlaybackCrossfadeMs());
        }
        stopInternal();
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
        Clip clip = currentClip;
        if (clip != null && clip.getMicrosecondLength() > 0) {
            return (float) clip.getMicrosecondPosition() / clip.getMicrosecondLength();
        }
        return 0f;
    }

    public float getVolume() {
        return volume;
    }

    public void setVolume(float volume) {
        this.volume = Math.max(0.0f, Math.min(1.0f, volume));
        saveStoredVolume(this.volume);
        Clip clip = currentClip;
        if (clip != null && clip.isOpen()) {
            applyVolume(clip);
        }
    }

    private void applyVolume(Clip clip) {
        setClipVolume(clip, volume);
    }

    private void setClipVolume(Clip clip, float linearVolume) {
        try {
            FloatControl control = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
            float dB = (float) (20.0 * Math.log10(Math.max(linearVolume, 0.0001)));
            dB = Math.max(dB, control.getMinimum());
            dB = Math.min(dB, control.getMaximum());
            control.setValue(dB);
        } catch (IllegalArgumentException e) {
            // Volume control not available
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
        float start = this.volume;
        for (int i = 1; i <= steps; i++) {
            float t = i / (float) steps;
            float v = start + (targetVolume - start) * t;
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

    public void shutdown() {
        stopInternal();
        executor.shutdownNow();
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

    private float loadStoredVolume() {
        if (!Files.exists(CLIENT_VOLUME_FILE)) {
            return 0.15f;
        }
        Properties props = new Properties();
        try (var in = Files.newInputStream(CLIENT_VOLUME_FILE)) {
            props.load(in);
            String raw = props.getProperty("volume", "0.15");
            float parsed = Float.parseFloat(raw);
            return Math.max(0.0f, Math.min(1.0f, parsed));
        } catch (IOException | NumberFormatException ignored) {
            return 0.15f;
        }
    }

    private void saveStoredVolume(float value) {
        Properties props = new Properties();
        props.setProperty("volume", Float.toString(value));
        try {
            Files.createDirectories(CLIENT_VOLUME_FILE.getParent());
            try (var out = Files.newOutputStream(CLIENT_VOLUME_FILE)) {
                props.store(out, "Mineify client settings");
            }
        } catch (IOException ignored) {
        }
    }
}
