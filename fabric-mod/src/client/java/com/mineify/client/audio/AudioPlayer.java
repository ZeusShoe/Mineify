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
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Environment(EnvType.CLIENT)
public class AudioPlayer {
    private static AudioPlayer instance;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Mineify-Audio");
        t.setDaemon(true);
        return t;
    });

    private volatile Clip currentClip;
    private volatile String currentTitle = "";
    private volatile boolean playing = false;
    private volatile boolean paused = false;
    private volatile boolean pendingPause = false;
    private volatile boolean suppressStopCallback = false;
    private volatile float volume = 0.15f;
    private volatile boolean minecraftMusicSuppressed = false;
    private volatile boolean hadPreviousMinecraftMusicVolume = false;
    private volatile float previousMinecraftMusicVolume = 1.0f;

    private AudioPlayer() {}

    public static AudioPlayer getInstance() {
        if (instance == null) {
            instance = new AudioPlayer();
        }
        return instance;
    }

    public void play(String downloadUrl, String title, long serverElapsedMs, long packetReceivedAtNanos) {
        executor.submit(() -> {
            fadeOutAndStopCurrent();
            try {
                MineifyClient.LOGGER.info("Downloading audio from: {}", downloadUrl);
                URL url = new URL(downloadUrl);
                AudioInputStream ais = AudioSystem.getAudioInputStream(url);

                AudioFormat baseFormat = ais.getFormat();
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
                    ais = AudioSystem.getAudioInputStream(playFormat, ais);
                }

                Clip clip = AudioSystem.getClip();
                clip.open(ais);
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
                currentTitle = title;
                playing = false;
                paused = false;
                setClipVolume(clip, 0.0f);

                long startOffsetMs = calculateStartOffsetMs(serverElapsedMs, packetReceivedAtNanos);
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

                if (pendingPause) {
                    paused = true;
                    MineifyClient.LOGGER.info("Loaded '{}' in paused state", title);
                } else {
                    suppressMinecraftMusic();
                    clip.start();
                    playing = true;
                    fadeToVolume(clip, volume, MineifyConfig.getPlaybackCrossfadeMs());
                    MineifyClient.LOGGER.info("Playing: {}", title);
                }
            } catch (Exception e) {
                MineifyClient.LOGGER.error("Audio playback failed for: {}", title, e);
                playing = false;
                paused = false;
                currentTitle = "";
                restoreMinecraftMusic();
            }
        });
    }

    private long calculateStartOffsetMs(long serverElapsedMs, long packetReceivedAtNanos) {
        long elapsedSinceReceiveMs = Math.max(0, (System.nanoTime() - packetReceivedAtNanos) / 1_000_000L);
        long baseOffsetMs = Math.max(0, serverElapsedMs);
        return baseOffsetMs + elapsedSinceReceiveMs;
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

    private void pauseInternal() {
        pendingPause = true;
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
        if (clip != null && clip.isOpen() && paused) {
            suppressMinecraftMusic();
            setClipVolume(clip, 0.0f);
            clip.start();
            fadeToVolume(clip, volume, Math.min(250, MineifyConfig.getPlaybackCrossfadeMs()));
            paused = false;
            playing = true;
        }
    }

    private void stopInternal() {
        pendingPause = false;
        playing = false;
        paused = false;
        currentTitle = "";
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
        this.volume = volume;
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
}
