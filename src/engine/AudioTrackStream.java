package engine;

import org.jflac.FLACDecoder;
import org.jflac.PCMProcessor;
import org.jflac.metadata.Metadata;
import org.jflac.metadata.Picture;
import org.jflac.metadata.StreamInfo;
import org.jflac.util.ByteData;

import javax.imageio.ImageIO;
import javax.sound.sampled.*;
import java.awt.image.BufferedImage;
import java.io.*;

public class AudioTrackStream implements PCMProcessor {
    private volatile boolean isPlaying = false;
    private volatile float channelGain = 1.0f;
    private String trackName = "UNTITLED VINYL";
    private BufferedImage albumArt = null;

    // Full in-memory audio buffers: decoded once on load, never blocking playback
    private float[] pcmLeft = new float[0];
    private float[] pcmRight = new float[0];
    private int totalFrames = 0;
    private double currentFramePos = 0.0;
    private volatile float playbackRate = 1.0f;

    // Anti-aliasing filter for scratching
    private final DSPMath.ScratchFilter scratchFilter = new DSPMath.ScratchFilter();

    public AudioTrackStream(int framesPerBuffer, int channels) {}

    public synchronized void load(File audioFile) throws Exception {
        stop();
        this.albumArt = null;
        this.currentFramePos = 0.0;
        this.playbackRate = 1.0f;
        this.trackName = audioFile.getName().replaceFirst("[.][^.]+$", "").toUpperCase();
        scratchFilter.reset();

        byte[] rawPcmBytes;
        String fileName = audioFile.getName().toLowerCase();
        if (fileName.endsWith(".flac")) {
            rawPcmBytes = loadFlacData(audioFile);
        } else {
            rawPcmBytes = loadStandardData(audioFile);
        }

        convertBytesToFloatBuffers(rawPcmBytes);
    }

    private byte[] loadStandardData(File audioFile) throws Exception {
        AudioInputStream source = AudioSystem.getAudioInputStream(audioFile);
        AudioFormat targetFormat = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED, 44100.0f, 16, 2, 4, 44100.0f, false
        );

        AudioInputStream converted = source;
        if (!source.getFormat().matches(targetFormat)) {
            converted = AudioSystem.getAudioInputStream(targetFormat, source);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int read;
        while ((read = converted.read(buf)) != -1) {
            out.write(buf, 0, read);
        }
        converted.close();
        return out.toByteArray();
    }

    private byte[] loadFlacData(File audioFile) throws IOException {
        // 1. Extract embedded artwork
        try (FileInputStream metaFis = new FileInputStream(audioFile)) {
            FLACDecoder metaDecoder = new FLACDecoder(metaFis);
            Metadata[] meta = metaDecoder.readMetadata();
            if (meta != null) {
                for (Metadata m : meta) {
                    if (m instanceof Picture) {
                        Picture pic = (Picture) m;
                        byte[] imgBytes = null;
                        try {
                            java.lang.reflect.Field field = Picture.class.getDeclaredField("image");
                            field.setAccessible(true);
                            imgBytes = (byte[]) field.get(pic);
                        } catch (Exception ignored) {}

                        if (imgBytes != null && imgBytes.length > 0) {
                            this.albumArt = ImageIO.read(new ByteArrayInputStream(imgBytes));
                        }
                        break;
                    }
                }
            }
        } catch (Exception ignored) {}

        // 2. Decode FLAC PCM samples
        ByteArrayOutputStream pcmOut = new ByteArrayOutputStream();
        try (FileInputStream fis = new FileInputStream(audioFile)) {
            FLACDecoder decoder = new FLACDecoder(fis);
            decoder.addPCMProcessor(new PCMProcessor() {
                @Override
                public void processStreamInfo(StreamInfo info) {}

                @Override
                public void processPCM(ByteData pcmData) {
                    pcmOut.write(pcmData.getData(), 0, pcmData.getLen());
                }
            });
            decoder.decode();
        }
        return pcmOut.toByteArray();
    }

    private void convertBytesToFloatBuffers(byte[] raw) {
        this.totalFrames = raw.length / 4;
        this.pcmLeft = new float[totalFrames];
        this.pcmRight = new float[totalFrames];

        for (int i = 0; i < totalFrames; i++) {
            int off = i * 4;
            this.pcmLeft[i] = DSPMath.pcmBytesToFloat(raw[off], raw[off + 1]);
            this.pcmRight[i] = DSPMath.pcmBytesToFloat(raw[off + 2], raw[off + 3]);
        }
    }

    public synchronized void play() {
        this.playbackRate = 1.0f;
        this.isPlaying = true;
    }

    public synchronized void pause() { this.isPlaying = false; }

    public synchronized void stop() {
        this.isPlaying = false;
        this.currentFramePos = 0;
        scratchFilter.reset();
    }

    public void setPlaybackRate(float rate) { this.playbackRate = rate; }
    public float getPlaybackRate() { return playbackRate; }
    public void setGain(float gain) { this.channelGain = Math.max(0.0f, Math.min(1.0f, gain)); }
    public float getGain() { return channelGain; }
    public boolean isPlaying() { return isPlaying; }
    public String getTrackName() { return trackName; }
    public BufferedImage getAlbumArt() { return albumArt; }

    public synchronized int readNextChunk(float[] leftChannel, float[] rightChannel, int framesToRead) {
        if (!isPlaying || totalFrames == 0) return 0;

        int framesGenerated = 0;
        for (int i = 0; i < framesToRead; i++) {
            int idx0 = (int) currentFramePos;
            int idx1 = idx0 + 1;

            if (idx0 < 0 || idx0 >= totalFrames - 1) {
                if (playbackRate < 0) {
                    currentFramePos = 0;
                }
                isPlaying = false;
                break;
            }

            float fraction = (float) (currentFramePos - idx0);
            leftChannel[i] = (pcmLeft[idx0] + fraction * (pcmLeft[idx1] - pcmLeft[idx0])) * channelGain;
            rightChannel[i] = (pcmRight[idx0] + fraction * (pcmRight[idx1] - pcmRight[idx0])) * channelGain;

            currentFramePos += playbackRate;
            framesGenerated++;
        }

        // Apply anti-aliasing low-pass filter on scrub
        scratchFilter.process(leftChannel, rightChannel, framesGenerated, playbackRate);

        return framesGenerated;
    }

    public boolean isNearEnd(int thresholdFrames) {
        return isPlaying && (totalFrames > 0) && (totalFrames - currentFramePos <= thresholdFrames);
    }

    @Override
    public void processStreamInfo(StreamInfo info) {}

    @Override
    public void processPCM(ByteData pcmData) {}
}