package org.roncax.podcaster.testing;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class TestSessionStore {
    static final Duration IDLE = Duration.ofMinutes(30);
    private final Map<Long, TestSession> sessions = new ConcurrentHashMap<>();

    public TestSession session(long showId) {
        TestSession s = sessions.computeIfAbsent(showId, TestSession::new);
        s.touch();
        return s;
    }

    public Optional<TestSession> find(long showId) {
        TestSession s = sessions.get(showId);
        if (s != null) s.touch();
        return Optional.ofNullable(s);
    }

    @Scheduled(every = "5m", identity = "test-session-sweep")
    void sweep() { evictIdle(Instant.now()); }

    void evictIdle(Instant now) {
        sessions.values().removeIf(s -> !s.running() && s.touchedAt().plus(IDLE).isBefore(now));
    }
}
