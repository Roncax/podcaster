package org.roncax.podcaster.tts;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.roncax.podcaster.config.PodcasterConfig;

/** Joins WAV chunks with silence, computes exact duration, encodes MP3 with ffmpeg. */
@ApplicationScoped
public class AudioAssembler {
    private final String ffmpeg;
    private final String bitrate;

    @Inject
    public AudioAssembler(PodcasterConfig config) {
        this(config.tts().ffmpeg(), config.tts().bitrate());
    }

    public AudioAssembler(String ffmpeg, String bitrate) {
        this.ffmpeg = ffmpeg;
        this.bitrate = bitrate;
    }

    public AssembledAudio assemble(List<Path> wavChunks, List<Duration> pausesAfter, Path outMp3, Mp3Tags tags)
            throws IOException, InterruptedException {
        if (wavChunks.isEmpty()) throw new IllegalArgumentException("No audio chunks to assemble");
        Wav first = null;
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        for (int i = 0; i < wavChunks.size(); i++) {
            Wav wav = Wav.parse(Files.readAllBytes(wavChunks.get(i)));
            if (first == null) {
                first = wav;
            } else if (!first.sameFormat(wav)) {
                throw new IllegalStateException("Chunk " + wavChunks.get(i).getFileName() + " has a different audio format");
            }
            pcm.writeBytes(wav.pcm());
            long silence = Math.round(pausesAfter.get(i).toMillis() / 1000.0 * first.bytesPerSecond());
            silence -= silence % first.blockAlign();
            pcm.writeBytes(new byte[(int) silence]);
        }
        byte[] data = pcm.toByteArray();
        Path wavFile = outMp3.resolveSibling(outMp3.getFileName() + ".wav");
        try (OutputStream out = Files.newOutputStream(wavFile)) {
            out.write(Wav.header(first.sampleRate(), first.channels(), first.bitsPerSample(), data.length));
            out.write(data);
        }
        double duration = (double) data.length / first.bytesPerSecond();
        try {
            encode(wavFile, outMp3, tags);
        } finally {
            Files.deleteIfExists(wavFile);
        }
        return new AssembledAudio(outMp3, duration, Files.size(outMp3));
    }

    private void encode(Path wav, Path mp3, Mp3Tags tags) throws IOException, InterruptedException {
        List<String> command = List.of(ffmpeg, "-y", "-hide_banner", "-loglevel", "error",
                "-i", wav.toString(), "-ac", "1", "-codec:a", "libmp3lame", "-b:a", bitrate,
                "-id3v2_version", "3",
                "-metadata", "title=" + tags.title(),
                "-metadata", "artist=" + tags.artist(),
                "-metadata", "album=" + tags.album(),
                "-metadata", "date=" + tags.date(),
                mp3.toString());
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(10, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IOException("ffmpeg timed out");
        }
        if (process.exitValue() != 0) throw new IOException("ffmpeg failed (exit " + process.exitValue() + "): " + output);
    }
}
