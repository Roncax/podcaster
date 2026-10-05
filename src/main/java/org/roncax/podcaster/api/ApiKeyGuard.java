package org.roncax.podcaster.api;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.Optional;
import java.util.Set;
import org.roncax.podcaster.config.PodcasterConfig;

/** Refuses to start with a missing, placeholder or short API key: it is the only protection on /api and /admin. */
@ApplicationScoped
public class ApiKeyGuard {
    static final int MIN_LENGTH = 16;
    private static final Set<String> PLACEHOLDERS = Set.of("change-me", "change-me-to-a-long-random-string");

    void onStart(@Observes StartupEvent event, PodcasterConfig config) {
        problem(config.apiKey()).ifPresent(p -> {
            throw new IllegalStateException("Invalid PODCASTER_API_KEY: " + p);
        });
    }

    public static Optional<String> problem(String key) {
        if (key == null || key.isBlank()) return Optional.of("it is not set");
        if (PLACEHOLDERS.contains(key.trim())) return Optional.of("it is still the example placeholder");
        if (key.trim().length() < MIN_LENGTH) return Optional.of("use at least " + MIN_LENGTH + " characters");
        return Optional.empty();
    }
}
