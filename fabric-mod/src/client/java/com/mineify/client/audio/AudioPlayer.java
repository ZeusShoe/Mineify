package com.mineify.client.audio;

import com.mineify.MineifyClient;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import javax.sound.sampled.*;
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
    private volatile float volume = 1.0f;

    private AudioPlayer() {}

    public static AudioPlayer getInstance() {
        if (instance == null) {
            instance = new AudioPlayer();
        }
        return instance;
    }

    public void play(String downloadUrl, String title, long startEpochMs) {
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
    
                // ---- SYNC: wait/seek to server-defined start time ----
                long now = System.currentTimeMillis();
                if (now < startEpochMs) {
                    Thread.sleep(startEpochMs - now);
                    now = System.currentTimeMillis();
                }
                long offsetUs = Math.max(0, (now - startEpochMs) * 1000L);
                long maxUs = Math.max(0, clip.getMicrosecondLength() - 1);
                clip.setMicrosecondPosition(Math.min(offsetUs, maxUs));
                // ------------------------------------------------------
    
                clip.addLineListener(event -> {
                    if (event.getType() == LineEvent.Type.STOP && playing) {
                        playing = false;
                        currentTitle = "";
                        MineifyClient.LOGGER.info("Audio playback finished");
                    }
                });
    
                currentClip = clip;
                currentTitle = title;
                playing = true;
                applyVolume(clip);
    
                clip.start();
                MineifyClient.LOGGER.info("Playing (synced): {}", title);
            } catch (Exception e) {
                MineifyClient.LOGGER.error("Audio playback failed for: {}", title, e);
                playing = false;
                currentTitle = "";
            }
        });
    }


    public void stop() {
        executor.submit(this::stopInternal);
    }

    private void stopInternal() {
        playing = false;
        currentTitle = "";
        Clip clip = currentClip;
        if (clip != null) {
            clip.stop();
            clip.close();
            currentClip = null;
        }
    }

    public boolean isPlaying() {
        return playing;
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
