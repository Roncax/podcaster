package org.roncax.podcaster.domain;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.support.TestData;
import org.roncax.podcaster.support.WireMockResource;
import org.roncax.podcaster.util.Exceptions;

@QuarkusTest
@WithTestResource(WireMockResource.class)
@WithTestResource(org.roncax.podcaster.support.PostgresResource.class)
class DomainPersistenceTest {

    @BeforeEach
    void clean() { TestData.cleanDb(); }

    @Test
    void persistsJsonColumns() {
        Show show = TestData.show("json");
        Source source = TestData.source(show.id, "http://x/feed");
        long episodeId = QuarkusTransaction.requiringNew().call(() -> {
            Run run = new Run();
            run.showId = show.id;
            run.trigger = RunTrigger.MANUAL;
            run.since = Instant.now();
            run.persist();
            Episode e = new Episode();
            e.runId = run.id;
            e.showId = show.id;
            e.selection = new Selection(List.of(new Cluster("Headline", List.of(1L, 2L), 7)));
            e.outline = new Outline(500, List.of(new OutlineSegment("Headline", List.of(1L), 300)));
            e.scriptParts = List.of("intro", "body", "outro");
            e.persist();
            return e.id;
        });

        Episode loaded = QuarkusTransaction.requiringNew().call(() -> Episode.<Episode>findById(episodeId));
        assertEquals(7, loaded.selection.clusters().get(0).importance());
        assertEquals(List.of(1L), loaded.outline.itemIds());
        assertEquals(List.of("intro", "body", "outro"), loaded.scriptParts);
        Source reloaded = QuarkusTransaction.requiringNew().call(() -> Source.<Source>findById(source.id));
        assertEquals("http://x/feed", reloaded.config.get("url"));
    }

    @Test
    void onlyOneRunningRunPerShow() {
        Show show = TestData.show("lock");
        Runnable insertRunning = () -> QuarkusTransaction.requiringNew().run(() -> {
            Run run = new Run();
            run.showId = show.id;
            run.trigger = RunTrigger.MANUAL;
            run.since = Instant.now();
            run.persistAndFlush();
        });
        insertRunning.run();
        RuntimeException ex = assertThrows(RuntimeException.class, insertRunning::run);
        assertTrue(Exceptions.isUniqueViolation(ex), "expected unique violation, got " + ex);
    }

    @Test
    void stageOrder() {
        assertEquals(RunStage.SELECT, RunStage.INGEST.next());
        assertEquals(RunStage.PUBLISH, RunStage.TTS.next());
        assertThrows(IllegalStateException.class, RunStage.PUBLISH::next);
    }
}
