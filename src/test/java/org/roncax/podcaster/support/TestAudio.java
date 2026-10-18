package org.roncax.podcaster.support;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class TestAudio {
    public static final int SAMPLE_RATE = 22050;

    private TestAudio() {}

    /** 16-bit mono PCM WAV with a 440 Hz tone. */
    public static byte[] sineWav(double seconds) {
        return sineWav(seconds, SAMPLE_RATE);
    }

    /** Same tone at another sample rate (Sibilia returns 24 kHz). */
    public static byte[] sineWav(double seconds, int sampleRate) {
        int samples = (int) Math.round(seconds * sampleRate);
        ByteBuffer pcm = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < samples; i++) {
            pcm.putShort((short) (Math.sin(2 * Math.PI * 440 * i / sampleRate) * 8000));
        }
        return wav(pcm.array(), sampleRate, 1, 16);
    }

    public static byte[] wav(byte[] pcm, int sampleRate, int channels, int bits) {
        ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        int blockAlign = channels * bits / 8;
        h.put("RIFF".getBytes()).putInt(36 + pcm.length).put("WAVE".getBytes())
         .put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) channels)
         .putInt(sampleRate).putInt(sampleRate * blockAlign).putShort((short) blockAlign).putShort((short) bits)
         .put("data".getBytes()).putInt(pcm.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(h.array());
        out.writeBytes(pcm);
        return out.toByteArray();
    }
}
