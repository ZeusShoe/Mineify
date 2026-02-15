package com.mineify.client.audio;

import com.mineify.MineifyClient;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

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
    private volatile float volume = 1.0f;

    private AudioPlayer() {}

    public static AudioPlayer getInstance() {
        if (instance == null) {
            instance = new AudioPlayer();
        }
        return instance;
    }

    public void play(String downloadUrl, String title, long serverElapsedMs, long packetReceivedAtNanos) {
        executor.submit(() -> {
            stopInternal();
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
                applyVolume(clip);

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
                    clip.start();
                    playing = true;
                    MineifyClient.LOGGER.info("Playing: {}", title);
                }
            } catch (Exception e) {
                MineifyClient.LOGGER.error("Audio playback failed for: {}", title, e);
                playing = false;
                paused = false;
                currentTitle = "";
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
            suppressStopCallback = true;
            clip.stop();
            playing = false;
            paused = true;
        }
    }

    private void resumeInternal() {
        pendingPause = false;
        Clip clip = currentClip;
        if (clip != null && clip.isOpen() && paused) {
            clip.start();
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
        try {
            FloatControl control = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
            float dB = (float) (20.0 * Math.log10(Math.max(volume, 0.0001)));
            dB = Math.max(dB, control.getMinimum());
            dB = Math.min(dB, control.getMaximum());
            control.setValue(dB);
        } catch (IllegalArgumentException e) {
            // Volume control not available
        }
    }

    public void shutdown() {
        stopInternal();
        executor.shutdownNow();
    }
}
