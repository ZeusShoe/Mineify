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
import javax.sound.sampled.Mixer;
import javax.sound.sampled.LineUnavailableException;
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
import java.util.concurrent.ConcurrentHashMap;
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
    private final ExecutorService prefetchExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "Mineify-Audio-Prefetch");
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentHashMap<String, Boolean> prefetchInFlight = new ConcurrentHashMap<>();

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
                    String resolvedVideoId = resolveVideoId(videoId, requestUrl);
                    Path cachedPath = ensureCachedDownload(resolvedVideoId, requestUrl);
                    String sourceLabel = cachedPath != null ? cachedPath.toAbsolutePath().toString() : requestUrl;
                    MineifyClient.LOGGER.info("Loading audio from: {} (attempt {}/{})", sourceLabel, attempt, MAX_LOAD_ATTEMPTS);
                    Clip clip = createClip();
                    try (InputStream raw = openCachedOrRemoteStream(cachedPath, requestUrl);
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
        saveClientSettings();
        Clip clip = currentClip;
        if (clip != null && clip.isOpen()) {
            applyVolume(clip);
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
                if (mixer.isLineSupported(new javax.sound.sampled.DataLine.Info(Clip.class, null))) {
                    names.add(info.getName());
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
        Path target = CLIENT_CACHE_DIR.resolve(videoId + ".mp3");
        if (Files.exists(target)) {
            return target;
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
