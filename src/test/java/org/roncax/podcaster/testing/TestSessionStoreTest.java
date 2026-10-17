package org.roncax.podcaster.testing;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TestSessionStoreTest {
    @Test
    void idleSessionsExpireButRunningOnesStay() {
        TestSessionStore store = new TestSessionStore();
        store.session(1L);
        TestSession running = store.session(2L);
        assertTrue(running.begin(TestSession.Step.SOURCES));

        store.evictIdle(Instant.now().plus(Duration.ofMinutes(31)));

        assertTrue(store.find(1L).isEmpty());
        assertTrue(store.find(2L).isPresent());
    }

    @Test
    void newFetchClearsLaterResults() {
        TestSession s = new TestSession(1L);
        s.clip(new TestSession.Clip(new byte[0], 1, 150, "v", 1.0));
        s.fetched(new TestSession.Fetched(java.util.List.of(), java.util.List.of()));
        assertNull(s.clip());
    }

    @Test
    void onlyOneStepRunsAtATime() {
        TestSession s = new TestSession(1L);
        assertTrue(s.begin(TestSession.Step.SOURCES));
        assertFalse(s.begin(TestSession.Step.VOICE));
        s.finish(TestSession.Step.SOURCES, null);
        assertTrue(s.begin(TestSession.Step.VOICE));
    }
}
