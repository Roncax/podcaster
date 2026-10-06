package org.roncax.podcaster.tts;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public record Wav(int sampleRate, int channels, int bitsPerSample, byte[] pcm) {

    public static boolean looksLikeWav(byte[] b) {
        return b != null && b.length >= 12
                && new String(b, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                && new String(b, 8, 4, StandardCharsets.US_ASCII).equals("WAVE");
    }

    public static Wav parse(byte[] bytes) {
        if (!looksLikeWav(bytes)) throw new IllegalArgumentException("Not a WAV file");
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        Integer rate = null;
        int channels = 0;
        int bits = 0;
        byte[] pcm = null;
        int pos = 12;
        while (pos + 8 <= bytes.length) {
            String id = new String(bytes, pos, 4, StandardCharsets.US_ASCII);
            long size = Integer.toUnsignedLong(buf.getInt(pos + 4));
            int start = pos + 8;
            if (id.equals("fmt ")) {
                int format = buf.getShort(start) & 0xFFFF;
                if (format != 1) throw new IllegalArgumentException("Only PCM WAV is supported (format " + format + ")");
                channels = buf.getShort(start + 2);
                rate = buf.getInt(start + 4);
                bits = buf.getShort(start + 14);
            } else if (id.equals("data")) {
                long available = bytes.length - start;
                int len = (int) (size == 0 || size > available ? available : size);
                pcm = Arrays.copyOfRange(bytes, start, start + len);
                break;
            }
            pos = (int) (start + size + (size & 1));
        }
        if (rate == null || pcm == null) throw new IllegalArgumentException("WAV is missing fmt or data chunk");
        return new Wav(rate, channels, bits, pcm);
    }

    public static byte[] header(int sampleRate, int channels, int bits, long pcmLength) {
        int blockAlign = channels * bits / 8;
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt((int) (36 + pcmLength))
                .put("WAVE".getBytes(StandardCharsets.US_ASCII))
                .put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) channels)
                .putInt(sampleRate).putInt(sampleRate * blockAlign).putShort((short) blockAlign).putShort((short) bits)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt((int) pcmLength)
                .array();
    }

    public int blockAlign() { return channels * bitsPerSample / 8; }

    public int bytesPerSecond() { return sampleRate * blockAlign(); }

    public boolean sameFormat(Wav other) {
        return sampleRate == other.sampleRate && channels == other.channels && bitsPerSample == other.bitsPerSample;
    }
}
