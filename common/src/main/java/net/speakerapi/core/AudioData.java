package net.speakerapi.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import javax.sound.sampled.AudioFormat;

/**
 * Mono PCM result from synthesis. Samples are normalized floats in {@code [-1, 1]}.
 * Conversion helpers produce 16-bit LE PCM for {@link javax.sound.sampled.SourceDataLine}.
 */
public final class AudioData {

    private final float[] samples;   // mono, [-1, 1]
    private final int sampleRate;    // native rate of the model
    private final String voiceId;

    public AudioData(float[] samples, int sampleRate, String voiceId) {
        this.samples = samples;
        this.sampleRate = sampleRate;
        this.voiceId = voiceId;
    }

    public float[] samples() {
        return samples;
    }

    public int sampleRate() {
        return sampleRate;
    }

    public String voiceId() {
        return voiceId;
    }

    public int getSampleCount() {
        return samples.length;
    }

    /** Encode as 16-bit signed little-endian PCM. */
    public byte[] toPcm16() {
        ByteBuffer buf = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (float s : samples) {
            float clamped = Math.max(-1f, Math.min(1f, s));
            buf.putShort((short) (clamped * 32767f));
        }
        return buf.array();
    }

    /** javax.sound format matching {@link #toPcm16()}. */
    public AudioFormat toFormat() {
        return new AudioFormat((float) sampleRate, 16, 1, true, false);
    }
}
