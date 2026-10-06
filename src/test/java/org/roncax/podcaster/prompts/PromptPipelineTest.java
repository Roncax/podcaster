package org.roncax.podcaster.prompts;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.domain.*;
import org.roncax.podcaster.runs.RunLauncher;
import org.roncax.podcaster.support.*;

@QuarkusTest
@WithTestResource(WireMockResource.class)
@WithTestResource(org.roncax.podcaster.support.PostgresResource.class)
class PromptPipelineTest {
    static final String RANK_V2 = "MARKER-RANK-V2 for \"{showName}\" in {language}.\n{contract}\n\nITEMS:\n{items}\n";
    static final String SEGMENT_V2 = "MARKER-SEGMENT-V2 about {headline} in {language}, about {words} words.\nSOURCES:\n{sources}\n";

    @InjectWireMock WireMockServer wm;
    @Inject RunLauncher launcher;
    @Inject PromptRegistry registry;
    FakeChatModel model;
    Show show;

    @BeforeEach
    void setup() {
        TestData.cleanDb();
        WireMockResource.installDefaults(wm);
        model = new FakeChatModel().responder(FakeResponses::pipeline);
        FakeChatModelRegistry.install(model);
        show = TestData.show("lineage");
        TestData.source(show.id, wm.baseUrl() + "/lfeed");
        String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.now().atOffset(ZoneOffset.UTC));
        wm.stubFor(get("/lfeed").willReturn(okXml("<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>t</title><link>http://x</link><description>d</description>"
                + "<item><title>Story one</title><link>" + wm.baseUrl() + "/l1</link><pubDate>" + date + "</pubDate></item></channel></rss>")));
        wm.stubFor(get("/l1").willReturn(aResponse().withStatus(200).withBody(Fixtures.bytes("ilpost-article.html"))));
    }

    private Episode episodeOf(Run run) {
        return QuarkusTransaction.requiringNew().call(() -> Episode.findByRun(run.id).orElseThrow());
    }

    @Test
    void recordsPromptVersions() {
        Run run = TestData.awaitRun(launcher.launch(show.id, RunTrigger.MANUAL));
        assertEquals(RunStatus.DONE, run.status, run.error);
        assertEquals(Map.of("rank", 1, "json_repair", 1, "segment", 1, "framing", 1), episodeOf(run).promptVersions);
    }

    @Test
    void promotedRankVersionIsUsed() {
        registry.createVersion(PromptKey.RANK, RANK_V2, "test");
        registry.setLabel(PromptKey.RANK, PromptLabel.PRODUCTION, 2);

        Run run = TestData.awaitRun(launcher.launch(show.id, RunTrigger.MANUAL));

        assertEquals(RunStatus.DONE, run.status, run.error);
        assertTrue(model.userMessage(0).startsWith("TASK: RANK\nMARKER-RANK-V2"), model.userMessage(0));
        assertEquals(2, episodeOf(run).promptVersions.get("rank"));
    }

    @Test
    void pinnedShowUsesPinnedDraftVersion() {
        registry.createVersion(PromptKey.RANK, RANK_V2, "draft only");
        registry.pin(show.id, PromptKey.RANK, 2);

        Run run = TestData.awaitRun(launcher.launch(show.id, RunTrigger.MANUAL));

        assertTrue(model.userMessage(0).contains("MARKER-RANK-V2"));
        assertEquals(2, episodeOf(run).promptVersions.get("rank"));
    }

    @Test
    void retryAfterPromotionRecordsNewVersions() {
        AtomicBoolean failedOnce = new AtomicBoolean();
        model.responder(prompt -> {
            if (prompt.startsWith("TASK: SEGMENT") && failedOnce.compareAndSet(false, true)) {
                throw new IllegalStateException("model outage");
            }
            return FakeResponses.pipeline(prompt);
        });
        Run failed = TestData.awaitRun(launcher.launch(show.id, RunTrigger.MANUAL));
        assertEquals(RunStatus.FAILED, failed.status);
        assertEquals(RunStage.SCRIPT, failed.stage);

        registry.createVersion(PromptKey.SEGMENT, SEGMENT_V2, "fix");
        registry.setLabel(PromptKey.SEGMENT, PromptLabel.PRODUCTION, 2);
        launcher.retry(failed.id);
        Run done = TestData.awaitRun(failed.id);

        assertEquals(RunStatus.DONE, done.status, done.error);
        Map<String, Integer> versions = episodeOf(done).promptVersions;
        assertEquals(1, versions.get("rank"));
        assertEquals(2, versions.get("segment"));
    }
}
