package org.roncax.podcaster.runs;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.domain.*;
import org.roncax.podcaster.support.*;

@QuarkusTest
@WithTestResource(WireMockResource.class)
@WithTestResource(org.roncax.podcaster.support.PostgresResource.class)
class SelectStageTest {
    @Inject SelectStage stage;
    FakeChatModel model;
    Show show;
    Source source;
    Instant since = Instant.now().minus(1, ChronoUnit.HOURS);

    @BeforeEach
    void setup() {
        TestData.cleanDb();
        model = new FakeChatModel().responder(FakeResponses::pipeline);
        FakeChatModelRegistry.install(model);
        show = TestData.show("sel");
        source = TestData.source(show.id, "http://unused/feed");
    }

    private Item item(String url, Instant publishedAt) {
        return TestData.item(show.id, source.id, url, publishedAt);
    }

    @Test
    void storesSelectionOnEpisode() throws Exception {
        Item a = item("http://a", Instant.now());
        Run run = TestData.run(show.id, since);

        assertEquals(StageResult.CONTINUE, stage.execute(run));

        Episode episode = QuarkusTransaction.requiringNew().call(() -> Episode.findByRun(run.id).orElseThrow());
        assertEquals(java.util.List.of(a.id), episode.selection.clusters().get(0).itemIds());
    }

    @Test
    void skipsWhenTooFewItems() throws Exception {
        QuarkusTransaction.requiringNew().run(() -> Show.<Show>findById(show.id).minItems = 3);
        item("http://a", Instant.now());
        item("http://b", Instant.now());
        Run run = TestData.run(show.id, since);

        assertEquals(StageResult.SKIP, stage.execute(run));
        assertTrue(model.requests.isEmpty());
    }

    @Test
    void excludesUsedAndOldItems() throws Exception {
        Item fresh = item("http://fresh", Instant.now());
        item("http://old", Instant.now().minus(2, ChronoUnit.DAYS));
        Item used = item("http://used", Instant.now());
        Run previous = QuarkusTransaction.requiringNew().call(() -> {
            Run r = new Run();
            r.showId = show.id;
            r.trigger = RunTrigger.MANUAL;
            r.status = RunStatus.DONE;
            r.since = since;
            r.persist();
            Episode e = new Episode();
            e.runId = r.id;
            e.showId = show.id;
            e.persist();
            Item.update("usedInEpisodeId = ?1 where id = ?2", e.id, used.id);
            return r;
        });
        Run run = TestData.run(show.id, since);

        stage.execute(run);

        assertEquals(java.util.List.of(fresh.id), FakeResponses.ids(model.userMessage(0)));
    }

    @Test
    void usesFetchedAtWhenNoPublishDate() throws Exception {
        Item undated = item("http://undated", null);
        Run run = TestData.run(show.id, since);

        stage.execute(run);

        assertEquals(java.util.List.of(undated.id), FakeResponses.ids(model.userMessage(0)));
    }

    @Test
    void capsCandidates() throws Exception {
        for (int i = 0; i < 90; i++) item("http://bulk/" + i, Instant.now().minusSeconds(i));
        Run run = TestData.run(show.id, since);

        stage.execute(run);

        assertEquals(80, FakeResponses.ids(model.userMessage(0)).size());
    }
}
