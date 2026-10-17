package org.roncax.podcaster.tts;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Joins PCM WAV chunks with silence between them. */
public final class WavConcat {
    public record Joined(Wav format, byte[] pcm, List<Double> partStarts) {
        public double durationSeconds() { return (double) pcm.length / format.bytesPerSecond(); }

        public byte[] toWavBytes() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.writeBytes(Wav.header(format.sampleRate(), format.channels(), format.bitsPerSample(), pcm.length));
            out.writeBytes(pcm);
            return out.toByteArray();
        }
    }

    private WavConcat() {}

    public static Joined concat(List<byte[]> wavs, List<Duration> pausesAfter, List<Integer> partOfChunk) {
        if (wavs.isEmpty()) throw new IllegalArgumentException("No audio chunks to assemble");
        Wav first = null;
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        List<Double> partStarts = new ArrayList<>();
        for (int i = 0; i < wavs.size(); i++) {
            Wav wav = Wav.parse(wavs.get(i));
            if (first == null) {
                first = wav;
            } else if (!first.sameFormat(wav)) {
                throw new IllegalStateException("Chunk " + i + " has a different audio format");
            }
            if (i == 0 || !partOfChunk.get(i).equals(partOfChunk.get(i - 1))) {
                partStarts.add((double) pcm.size() / first.bytesPerSecond());
            }
            pcm.writeBytes(wav.pcm());
            long silence = Math.round(pausesAfter.get(i).toMillis() / 1000.0 * first.bytesPerSecond());
            silence -= silence % first.blockAlign();
            pcm.writeBytes(new byte[(int) silence]);
        }
        return new Joined(first, pcm.toByteArray(), List.copyOf(partStarts));
    }
}
