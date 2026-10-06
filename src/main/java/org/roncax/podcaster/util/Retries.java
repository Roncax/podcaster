package org.roncax.podcaster.util;

import java.time.Duration;
import java.util.concurrent.Callable;

public final class Retries {
    private Retries() {}

    public static <T> T withBackoff(int attempts, Duration initialDelay, Callable<T> action) throws Exception {
        long delay = initialDelay.toMillis();
        RetryableException last = null;
        for (int attempt = 1; attempt <= Math.max(1, attempts); attempt++) {
            try {
                return action.call();
            } catch (RetryableException e) {
                last = e;
                if (attempt < attempts) {
                    Thread.sleep(delay);
                    delay *= 2;
                }
            }
        }
        throw last;
    }
}
