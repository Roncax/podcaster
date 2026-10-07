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
import org.roncax.podcaster.domain.Chapter;
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
    @Inject RunProgress progress;

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
            java.util.concurrent.atomic.AtomicInteger synthesized = new java.util.concurrent.atomic.AtomicInteger();
            for (TtsChunk chunk : chunks) futures.add(pool.submit(() -> {
                Path file = synthesize(dir, chunk, voice);
                progress.update(run.id, "Synthesizing chunk " + synthesized.incrementAndGet() + " of " + chunks.size());
                return file;
            }));
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

        progress.update(run.id, "Encoding MP3");
        AssembledAudio audio = assembler.assemble(files, chunks.stream().map(TtsChunk::pauseAfter).toList(),
                chunks.stream().map(TtsChunk::part).toList(), dir.resolve("episode.mp3"),
                new Mp3Tags(episode.title, "Podcaster", show.name, LocalDate.now().toString()));

        List<Chapter> chapters = chapters(episode, audio.partStarts());
        QuarkusTransaction.requiringNew().run(() -> {
            Episode e = Episode.findById(episode.id);
            e.durationSeconds = audio.durationSeconds();
            e.sizeBytes = audio.sizeBytes();
            e.chapters = chapters;
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

    /** Intro, one chapter per outline segment, outro; starts come from the assembled audio. */
    static List<Chapter> chapters(Episode episode, List<Double> partStarts) {
        List<Chapter> chapters = new ArrayList<>();
        int parts = partStarts.size();
        for (int p = 0; p < parts; p++) {
            String title;
            List<Long> itemIds = List.of();
            if (p == 0) {
                title = "Intro";
            } else if (p == parts - 1) {
                title = "Outro";
            } else {
                int seg = p - 1;
                boolean known = episode.outline != null && seg < episode.outline.segments().size();
                title = known ? episode.outline.segments().get(seg).headline() : "Story " + p;
                itemIds = known ? episode.outline.segments().get(seg).itemIds() : List.of();
            }
            chapters.add(new Chapter(title, partStarts.get(p), itemIds));
        }
        return chapters;
    }
}
