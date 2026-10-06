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
class RunRecoveryTest {
    @InjectWireMock com.github.tomakehurst.wiremock.WireMockServer wm;
    @Inject RunRecovery recovery;
    @Inject RunLauncher launcher;

    @BeforeEach
    void setup() {
        TestData.cleanDb();
        WireMockResource.installDefaults(wm);
        FakeChatModelRegistry.install(new FakeChatModel().responder(FakeResponses::pipeline));
    }

    @Test
    void interruptedRunIsFailedAndResumesAtItsStage() {
        Show show = TestData.show("rec");
        Source source = TestData.source(show.id, "http://localhost:1/unreachable-feed"); // INGEST would fail
        TestData.item(show.id, source.id, "http://story", Instant.now());
        Run stuck = TestData.run(show.id, Instant.now().minus(1, ChronoUnit.HOURS));
        QuarkusTransaction.requiringNew().run(() -> Run.<Run>findById(stuck.id).stage = RunStage.SELECT);

        assertEquals(1, recovery.recover());
        Run failed = QuarkusTransaction.requiringNew().call(() -> Run.<Run>findById(stuck.id));
        assertEquals(RunStatus.FAILED, failed.status);
        assertEquals("Interrupted by restart", failed.error);

        launcher.retry(stuck.id);
        Run done = TestData.awaitRun(stuck.id);
        assertEquals(RunStatus.DONE, done.status, done.error);
    }
}
