package org.roncax.podcaster.runs;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.roncax.podcaster.config.PodcasterConfig;
import org.roncax.podcaster.domain.*;
import org.roncax.podcaster.publishing.AudioStorage;
import org.roncax.podcaster.publishing.RetentionService;

@ApplicationScoped
public class PublishStage implements Stage {
    @Inject AudioStorage storage;
    @Inject RetentionService retention;
    @Inject PodcasterConfig config;

    @Override
    public RunStage stage() { return RunStage.PUBLISH; }

    @Override
    public StageResult execute(Run run) throws Exception {
        Show show = QuarkusTransaction.requiringNew().call(() -> Show.<Show>findById(run.showId));
        Episode episode = QuarkusTransaction.requiringNew().call(() -> Episode.findByRun(run.id)
                .orElseThrow(() -> new StageException("Run " + run.id + " has no episode")));
        String key = show.slug + "/" + episode.id + ".mp3";
        Path dir = TtsStage.workDir(config, run.id);
        Path mp3 = dir.resolve("episode.mp3");
        if (Files.exists(mp3)) {
            storage.store(key, mp3);
        } else if (!storage.exists(key)) {
            throw new StageException("No audio to publish for run " + run.id);
        }
        long size = Files.size(storage.resolve(key));

        QuarkusTransaction.requiringNew().run(() -> {
            Episode e = Episode.findById(episode.id);
            e.audioPath = key;
            e.sizeBytes = size;
            e.publishedAt = Instant.now();
            List<Long> used = e.outline == null ? List.of() : e.outline.itemIds();
            if (!used.isEmpty()) Item.update("usedInEpisodeId = ?1 where id in ?2", e.id, used);
        });
        retention.apply(show.id);
        deleteRecursively(dir);
        return StageResult.CONTINUE;
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
