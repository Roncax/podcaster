package org.roncax.podcaster.tts;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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

    public AssembledAudio assemble(List<Path> wavChunks, List<Duration> pausesAfter, List<Integer> partOfChunk, Path outMp3, Mp3Tags tags)
            throws IOException, InterruptedException {
        if (wavChunks.isEmpty()) throw new IllegalArgumentException("No audio chunks to assemble");
        List<byte[]> wavs = new java.util.ArrayList<>();
        for (Path chunk : wavChunks) wavs.add(Files.readAllBytes(chunk));
        WavConcat.Joined joined;
        try {
            joined = WavConcat.concat(wavs, pausesAfter, partOfChunk);
        } catch (IllegalStateException e) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^Chunk (\\d+)").matcher(String.valueOf(e.getMessage()));
            if (!m.find()) throw e;
            String name = wavChunks.get(Integer.parseInt(m.group(1))).getFileName().toString();
            throw new IllegalStateException(e.getMessage().replaceFirst("^Chunk \\d+", "Chunk " + name), e);
        }
        Path wavFile = outMp3.resolveSibling(outMp3.getFileName() + ".wav");
        Wav format = joined.format();
        try (OutputStream out = Files.newOutputStream(wavFile)) {
            out.write(Wav.header(format.sampleRate(), format.channels(), format.bitsPerSample(), joined.pcm().length));
            out.write(joined.pcm());
        }
        double duration = joined.durationSeconds();
        try {
            encode(wavFile, outMp3, tags);
        } finally {
            Files.deleteIfExists(wavFile);
        }
        return new AssembledAudio(outMp3, duration, Files.size(outMp3), joined.partStarts());
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
