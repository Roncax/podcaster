package org.roncax.podcaster.support;

import io.quarkus.narayana.jta.QuarkusTransaction;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.roncax.podcaster.domain.*;

public final class TestData {
    private TestData() {}

    public static void cleanDb() {
        QuarkusTransaction.requiringNew().run(() -> {
            Item.deleteAll();
            Episode.deleteAll();
            Run.deleteAll();
            Source.deleteAll();
            Show.deleteAll();
            VoiceCalibration.deleteAll();
        });
    }

    public static Show show(String slug) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Show s = new Show();
            s.name = "Show " + slug;
            s.slug = slug;
            s.language = "it";
            s.voiceId = "it_IT-paola-medium";
            s.writerModel = "fake";
            s.minItems = 1;
            s.targetDurationMinutes = 5;
            s.persist();
            return s;
        });
    }

    public static Source source(long showId, String url) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Source s = new Source();
            s.showId = showId;
            s.connectorType = "rss";
            s.config = new java.util.HashMap<>(Map.of("url", url));
            s.persist();
            return s;
        });
    }

    public static Item item(long showId, long sourceId, String url, Instant publishedAt) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Item i = new Item();
            i.showId = showId;
            i.sourceId = sourceId;
            i.url = url;
            i.contentHash = Integer.toHexString(url.hashCode());
            i.title = "Title " + url;
            i.summary = "Summary of " + url;
            i.fullText = "Full text of " + url + ". It has several sentences. This is one more.";
            i.publishedAt = publishedAt;
            i.fetchedAt = Instant.now();
            i.persist();
            return i;
        });
    }

    public static Run run(long showId, Instant since) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Run r = new Run();
            r.showId = showId;
            r.trigger = RunTrigger.MANUAL;
            r.since = since;
            r.persist();
            return r;
        });
    }

    public static Run awaitRun(long runId) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        while (Instant.now().isBefore(deadline)) {
            Run run = QuarkusTransaction.requiringNew().call(() -> Run.<Run>findById(runId));
            if (run != null && run.status != RunStatus.RUNNING) return run;
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        throw new AssertionError("Run " + runId + " did not finish within 60s");
    }
}
