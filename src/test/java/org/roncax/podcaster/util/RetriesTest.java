package org.roncax.podcaster.util;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RetriesTest {

    @Test
    void retriesRetryableUntilSuccess() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String result = Retries.withBackoff(3, Duration.ofMillis(1), () -> {
            if (calls.incrementAndGet() < 3) throw new RetryableException("boom");
            return "ok";
        });
        assertEquals("ok", result);
        assertEquals(3, calls.get());
    }

    @Test
    void doesNotRetryOtherExceptions() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> Retries.withBackoff(3, Duration.ofMillis(1), () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("fatal");
        }));
        assertEquals(1, calls.get());
    }

    @Test
    void givesUpAfterAttempts() {
        AtomicInteger calls = new AtomicInteger();
        RetryableException ex = assertThrows(RetryableException.class, () -> Retries.withBackoff(2, Duration.ofMillis(1), () -> {
            calls.incrementAndGet();
            throw new RetryableException("still failing");
        }));
        assertEquals("still failing", ex.getMessage());
        assertEquals(2, calls.get());
    }
}
