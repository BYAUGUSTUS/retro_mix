package engine;

public final class DSPMath {
    private DSPMath() {}

    public static float getEqualPowerGainA(float fader) {
        float clamped = Math.max(0.0f, Math.min(1.0f, fader));
        return (float) Math.cos(clamped * (Math.PI / 2.0));
    }

    public static float getEqualPowerGainB(float fader) {
        float clamped = Math.max(0.0f, Math.min(1.0f, fader));
        return (float) Math.sin(clamped * (Math.PI / 2.0));
    }

    public static float pcmBytesToFloat(byte low, byte high) {
        short sample = (short) (((high & 0xFF) << 8) | (low & 0xFF));
        return sample / 32768.0f;
    }

    public static void floatToPcmBytes(float sample, byte[] buffer, int offset) {
        // Soft-knee analog tape saturation limiter
        if (sample > 0.8f) {
            sample = 0.8f + 0.2f * (float) Math.tanh((sample - 0.8f) / 0.2f);
        } else if (sample < -0.8f) {
            sample = -0.8f + 0.2f * (float) Math.tanh((sample + 0.8f) / 0.2f);
        }

        short pcm = (short) (sample * 32767.0f);
        buffer[offset] = (byte) (pcm & 0xFF);
        buffer[offset + 1] = (byte) ((pcm >> 8) & 0xFF);
    }

    /**
     * 1-Pole Low-Pass Filter for Anti-Aliasing & Warm Cartridge Drag
     */
    public static class ScratchFilter {
        private float prevLeft = 0.0f;
        private float prevRight = 0.0f;

        public void process(float[] left, float[] right, int frames, float playbackRate) {
            float absRate = Math.abs(playbackRate);
            if (absRate <= 1.05f && playbackRate > 0.0f) {
                // Bypass filter during standard 1.0x playback for clean hi-fi passthrough
                prevLeft = (frames > 0) ? left[frames - 1] : 0.0f;
                prevRight = (frames > 0) ? right[frames - 1] : 0.0f;
                return;
            }

            // Dynamically scale alpha cutoff based on scrubbing speed:
            // Faster scrub = more aggressive roll-off to kill digital aliasing
            float alpha = (absRate > 2.0f) ? 0.35f : 0.65f;

            for (int i = 0; i < frames; i++) {
                prevLeft = prevLeft + alpha * (left[i] - prevLeft);
                prevRight = prevRight + alpha * (right[i] - prevRight);
                left[i] = prevLeft;
                right[i] = prevRight;
            }
        }

        public void reset() {
            prevLeft = 0.0f;
            prevRight = 0.0f;
        }
    }
}