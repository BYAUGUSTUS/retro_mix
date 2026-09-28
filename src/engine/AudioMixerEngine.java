package engine;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sound.sampled.*;

public class AudioMixerEngine implements Runnable {
    private static final int BUFFER_FRAMES = 512;
    private static final float SAMPLE_RATE = 44100.0f;
    private static final int FADE_FRAMES = (int) (SAMPLE_RATE * 5.0f);

    private SourceDataLine outputLine;
    private Thread mixerThread;
    private volatile boolean running = false;

    private final AudioTrackStream streamPrimary;
    private final AudioTrackStream streamSecondary;
    private boolean primaryActive = true;

    private final List<File> playlist = new ArrayList<>();
    private int playlistIndex = -1;
    private File watchedFolder = null;

    private volatile boolean isTransitioning = false;
    private float autoFader = 0.0f;
    private volatile float masterVolume = 0.85f;

    // Asynchronous loader executor
    private final ExecutorService asyncLoader = Executors.newSingleThreadExecutor();

    private final float[] pLeft = new float[BUFFER_FRAMES];
    private final float[] pRight = new float[BUFFER_FRAMES];
    private final float[] sLeft = new float[BUFFER_FRAMES];
    private final float[] sRight = new float[BUFFER_FRAMES];
    private final byte[] outputPcmBytes = new byte[BUFFER_FRAMES * 4];

    public AudioMixerEngine() {
        this.streamPrimary = new AudioTrackStream(BUFFER_FRAMES, 2);
        this.streamSecondary = new AudioTrackStream(BUFFER_FRAMES, 2);
    }

    public synchronized void start() throws LineUnavailableException {
        if (running) return;

        AudioFormat format = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, SAMPLE_RATE, 16, 2, 4, SAMPLE_RATE, false);
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        outputLine = (SourceDataLine) AudioSystem.getLine(info);
        outputLine.open(format, BUFFER_FRAMES * 4 * 4);
        outputLine.start();

        running = true;
        mixerThread = new Thread(this, "RetroMix-Audio-Thread");
        mixerThread.setPriority(Thread.MAX_PRIORITY);
        mixerThread.start();
    }

    public void setWatchedFolder(File folder, Runnable onComplete) {
        this.watchedFolder = folder;
        asyncLoader.submit(() -> {
            refreshPlaylistFromFolder();
            if (onComplete != null) onComplete.run();
        });
    }

    public void refreshPlaylistFromFolder() {
        if (watchedFolder == null || !watchedFolder.isDirectory()) return;

        File[] files = watchedFolder.listFiles((dir, name) -> {
            String lower = name.toLowerCase();
            return lower.endsWith(".flac") || lower.endsWith(".wav");
        });

        if (files == null) return;

        synchronized (playlist) {
            boolean wasEmpty = playlist.isEmpty();
            playlist.clear();
            for (File f : files) playlist.add(f);

            if (wasEmpty && !playlist.isEmpty()) {
                playTrackAtIndex(0);
            }
        }
    }

    public synchronized void playTrackAtIndex(int index) {
        synchronized (playlist) {
            if (index < 0 || index >= playlist.size()) return;
            playlistIndex = index;
            loadTrackIntoActiveStream(playlist.get(playlistIndex));
        }
    }

    public synchronized void playNextTrack() {
        synchronized (playlist) {
            if (playlist.isEmpty()) return;
            playlistIndex = (playlistIndex + 1) % playlist.size();
            loadTrackIntoActiveStream(playlist.get(playlistIndex));
        }
    }

    public synchronized void playPreviousTrack() {
        synchronized (playlist) {
            if (playlist.isEmpty()) return;
            playlistIndex = (playlistIndex - 1 + playlist.size()) % playlist.size();
            loadTrackIntoActiveStream(playlist.get(playlistIndex));
        }
    }

    private void loadTrackIntoActiveStream(File file) {
        asyncLoader.submit(() -> {
            try {
                if (primaryActive) {
                    streamSecondary.unload(); // Free inactive stream
                    streamPrimary.load(file);
                    streamPrimary.play();
                    autoFader = 0.0f;
                } else {
                    streamPrimary.unload();   // Free inactive stream
                    streamSecondary.load(file);
                    streamSecondary.play();
                    autoFader = 1.0f;
                }
                isTransitioning = false;
                System.gc(); // Suggest immediate reclamation of dead PCM arrays
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    public synchronized void play() {
        AudioTrackStream active = primaryActive ? streamPrimary : streamSecondary;
        active.play();
    }

    public synchronized void pause() {
        streamPrimary.pause();
        streamSecondary.pause();
    }

    public synchronized void stop() {
        streamPrimary.stop();
        streamSecondary.stop();
        isTransitioning = false;
    }

    public void setManualScratchRate(float rate) {
        AudioTrackStream active = primaryActive ? streamPrimary : streamSecondary;
        if (!active.isPlaying()) active.play();
        active.setPlaybackRate(rate);
    }

    public void startRewindScratch() { setManualScratchRate(-2.8f); }
    public void startForwardScratch() { setManualScratchRate(3.0f); }
    public void releaseScratch() { setManualScratchRate(1.0f); }

    public float getActivePlaybackRate() {
        AudioTrackStream active = primaryActive ? streamPrimary : streamSecondary;
        return active.getPlaybackRate();
    }

    public void setMasterVolume(float vol) {
        this.masterVolume = Math.max(0.0f, Math.min(1.0f, vol));
    }

    public float getMasterVolume() { return masterVolume; }
    public boolean isPlaying() { return streamPrimary.isPlaying() || streamSecondary.isPlaying(); }
    public String getCurrentTrackName() { return primaryActive ? streamPrimary.getTrackName() : streamSecondary.getTrackName(); }
    public BufferedImage getCurrentAlbumArt() { return primaryActive ? streamPrimary.getAlbumArt() : streamSecondary.getAlbumArt(); }
    
    public List<File> getPlaylist() {
        synchronized (playlist) {
            return new ArrayList<>(playlist);
        }
    }

    public int getPlaylistIndex() { return playlistIndex; }

    @Override
    public void run() {
        while (running) {
            Arrays.fill(pLeft, 0.0f);
            Arrays.fill(pRight, 0.0f);
            Arrays.fill(sLeft, 0.0f);
            Arrays.fill(sRight, 0.0f);

            AudioTrackStream current = primaryActive ? streamPrimary : streamSecondary;
            AudioTrackStream upcoming = primaryActive ? streamSecondary : streamPrimary;

            // Auto-crossfade trigger
            if (!isTransitioning && current.isPlaying() && current.isNearEnd(FADE_FRAMES) && playlist.size() > 1 && current.getPlaybackRate() > 0) {
                isTransitioning = true;
                int nextIndex = (playlistIndex + 1) % playlist.size();
                File nextFile = playlist.get(nextIndex);
                asyncLoader.submit(() -> {
                    try {
                        upcoming.unload(); // Clear previous data before loading
                        upcoming.load(nextFile);
                        upcoming.play();
                    } catch (Exception ignored) {}
                });
            }

            if (isTransitioning) {
                float step = 0.0008f;
                if (primaryActive) {
                    autoFader += step;
                    if (autoFader >= 1.0f) {
                        autoFader = 1.0f;
                        current.stop();
                        current.unload(); // Release completed song from memory
                        primaryActive = false;
                        isTransitioning = false;
                        playlistIndex = (playlistIndex + 1) % playlist.size();
                        System.gc();
                    }
                } else {
                    autoFader -= step;
                    if (autoFader <= 0.0f) {
                        autoFader = 0.0f;
                        current.stop();
                        current.unload(); // Release completed song from memory
                        primaryActive = true;
                        isTransitioning = false;
                        playlistIndex = (playlistIndex + 1) % playlist.size();
                        System.gc();
                    }
                }
            }

            int framesP = streamPrimary.readNextChunk(pLeft, pRight, BUFFER_FRAMES);
            int framesS = streamSecondary.readNextChunk(sLeft, sRight, BUFFER_FRAMES);

            float gainP = DSPMath.getEqualPowerGainA(autoFader) * masterVolume;
            float gainS = DSPMath.getEqualPowerGainB(autoFader) * masterVolume;

            int activeFrames = Math.max(framesP, framesS);
            if (activeFrames == 0) {
                Arrays.fill(outputPcmBytes, (byte) 0);
                outputLine.write(outputPcmBytes, 0, outputPcmBytes.length);
                continue;
            }

            for (int i = 0; i < activeFrames; i++) {
                float mixedL = (pLeft[i] * gainP) + (sLeft[i] * gainS);
                float mixedR = (pRight[i] * gainP) + (sRight[i] * gainS);

                int offset = i * 4;
                DSPMath.floatToPcmBytes(mixedL, outputPcmBytes, offset);
                DSPMath.floatToPcmBytes(mixedR, outputPcmBytes, offset + 2);
            }

            outputLine.write(outputPcmBytes, 0, activeFrames * 4);
        }
    }
}