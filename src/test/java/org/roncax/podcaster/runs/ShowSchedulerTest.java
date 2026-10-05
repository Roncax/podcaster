package org.roncax.podcaster.runs;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.domain.Show;
import org.roncax.podcaster.support.TestData;
import org.roncax.podcaster.support.WireMockResource;

@QuarkusTest
@WithTestResource(WireMockResource.class)
@WithTestResource(org.roncax.podcaster.support.PostgresResource.class)
class ShowSchedulerTest {
    @Inject ShowScheduler scheduler;

    @BeforeEach
    void clean() { TestData.cleanDb(); }

    @Test
    void schedulesAndUnschedulesShows() {
        Show show = TestData.show("cron");
        show.cron = "0 7 * * *";

        scheduler.reschedule(show);
        assertTrue(scheduler.scheduledJobIds().contains("show-" + show.id));

        show.enabled = false;
        scheduler.reschedule(show);
        assertFalse(scheduler.scheduledJobIds().contains("show-" + show.id));

        show.enabled = true;
        scheduler.reschedule(show);
        scheduler.unschedule(show.id);
        assertFalse(scheduler.scheduledJobIds().contains("show-" + show.id));
    }

    @Test
    void showWithoutCronIsNotScheduled() {
        Show show = TestData.show("nocron");
        scheduler.reschedule(show);
        assertFalse(scheduler.scheduledJobIds().contains("show-" + show.id));
    }
}
