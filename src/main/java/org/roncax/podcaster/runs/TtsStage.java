package org.roncax.podcaster.runs;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.roncax.podcaster.config.PodcasterConfig;
import org.roncax.podcaster.domain.Episode;
import org.roncax.podcaster.domain.Run;
import org.roncax.podcaster.domain.RunStage;
import org.roncax.podcaster.domain.Show;
import org.roncax.podcaster.generation.ScriptChunker;
import org.roncax.podcaster.generation.TtsChunk;
import org.roncax.podcaster.tts.*;

@ApplicationScoped
public class TtsStage implements Stage {
    @Inject TtsEngine tts;
    @Inject AudioAssembler assembler;
    @Inject VoiceCalibrationService calibration;
    @Inject PodcasterConfig config;

    public static Path workDir(PodcasterConfig config, long runId) {
        return Path.of(config.storage().workDir()).toAbsolutePath().resolve("run-" + runId);
    }

    @Override
    public RunStage stage() { return RunStage.TTS; }

    @Override
    public StageResult execute(Run run) throws Exception {
        Show show = QuarkusTransaction.requiringNew().call(() -> Show.<Show>findById(run.showId));
        Episode episode = QuarkusTransaction.requiringNew().call(() -> Episode.findByRun(run.id)
                .orElseThrow(() -> new StageException("Run " + run.id + " has no episode")));
        if (episode.scriptParts == null || episode.scriptParts.isEmpty()) throw new StageException("Episode has no script");

        Path dir = workDir(config, run.id);
        Files.createDirectories(dir);
        PodcasterConfig.Tts cfg = config.tts();
        List<TtsChunk> chunks = ScriptChunker.chunk(episode.scriptParts, cfg.chunkChars(), cfg.chunkPause(), cfg.segmentPause());
        VoiceConfig voice = new VoiceConfig(show.voiceId, show.lengthScale);

        List<Path> files = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, cfg.parallelism()));
        try {
            List<Future<Path>> futures = new ArrayList<>();
            for (TtsChunk chunk : chunks) futures.add(pool.submit(() -> synthesize(dir, chunk, voice)));
            for (Future<Path> future : futures) {
                try {
                    files.add(future.get());
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof Exception ex) throw ex;
                    throw new IllegalStateException(e.getCause());
                }
            }
        } finally {
            pool.shutdownNow();
        }

        AssembledAudio audio = assembler.assemble(files, chunks.stream().map(TtsChunk::pauseAfter).toList(),
                dir.resolve("episode.mp3"),
                new Mp3Tags(episode.title, "Podcaster", show.name, LocalDate.now().toString()));

        QuarkusTransaction.requiringNew().run(() -> {
            Episode e = Episode.findById(episode.id);
            e.durationSeconds = audio.durationSeconds();
            e.sizeBytes = audio.sizeBytes();
        });
        int words = episode.scriptParts.stream().mapToInt(p -> p.isBlank() ? 0 : p.trim().split("\\s+").length).sum();
        calibration.record(show.voiceId, show.lengthScale, words, audio.durationSeconds());
        return StageResult.CONTINUE;
    }

    private Path synthesize(Path dir, TtsChunk chunk, VoiceConfig voice) throws Exception {
        Path file = dir.resolve("chunk-%04d.wav".formatted(chunk.index()));
        if (Files.exists(file) && Files.size(file) > 44) return file; // resume: already synthesized
        byte[] wav = tts.synthesize(chunk.text(), voice);
        Path tmp = dir.resolve(file.getFileName() + ".tmp");
        Files.write(tmp, wav);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return file;
    }
}
