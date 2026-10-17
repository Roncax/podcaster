package org.roncax.podcaster.prompts;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.support.TestData;
import org.roncax.podcaster.support.WireMockResource;

@QuarkusTest
@WithTestResource(WireMockResource.class)
@WithTestResource(org.roncax.podcaster.support.PostgresResource.class)
class PromptSeederTest {
    @Inject PromptSeeder seeder;
    @Inject PromptRegistry registry;

    @BeforeEach
    void clean() { TestData.cleanDb(); }

    @Test
    void seedsDefaultVersionOneWithoutLabels() {
        for (PromptKey key : PromptKey.values()) {
            PromptVersion v = registry.latestDefault(key);
            assertEquals(1, v.version);
            assertEquals(key.seedBody(), v.body);
        }
        assertEquals(0L, QuarkusTransaction.requiringNew().call(() -> PromptLabelAssignment.count()));
    }

    @Test
    void seedingIsIdempotent() {
        seeder.seed();
        seeder.seed();
        assertEquals(PromptKey.values().length, QuarkusTransaction.requiringNew().call(() -> PromptVersion.count("showId is null")));
    }

    @Test
    void backfillsShowsMissingPrompts() {
        long showId = QuarkusTransaction.requiringNew().call(() -> {
            org.roncax.podcaster.domain.Show s = new org.roncax.podcaster.domain.Show();
            s.name = "Raw"; s.slug = "raw"; s.language = "it"; s.voiceId = "it_IT-paola-medium"; s.writerModel = "fake";
            s.persist();
            return s.id;
        });
        seeder.seed();
        for (PromptKey key : PromptKey.values()) {
            assertEquals(Map.of(PromptLabel.PRODUCTION, 1, PromptLabel.DRAFT, 1), registry.labels(showId, key));
        }
    }

    @Test
    void seedBodiesHaveExpectedEndings() {
        assertTrue(PromptKey.RANK.seedBody().endsWith("{items}\n"));
        assertFalse(PromptKey.JSON_REPAIR.seedBody().endsWith("\n"));
        assertEquals(PromptKey.SEGMENT, PromptKey.fromDb("segment").orElseThrow());
        assertTrue(PromptKey.fromDb("nope").isEmpty());
    }
}
