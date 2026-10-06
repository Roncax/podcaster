package org.roncax.podcaster.prompts;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.support.TestData;
import org.roncax.podcaster.support.WireMockResource;

@QuarkusTest
@WithTestResource(WireMockResource.class)
@WithTestResource(org.roncax.podcaster.support.PostgresResource.class)
class PromptSeederTest {
    @Inject PromptSeeder seeder;

    @BeforeEach
    void clean() { TestData.cleanDb(); }

    @Test
    void seedsVersionOneWithBothLabels() {
        for (PromptKey key : PromptKey.values()) {
            PromptVersion v = QuarkusTransaction.requiringNew().call(() ->
                    PromptVersion.<PromptVersion>find("promptKey = ?1 and version = 1", key.dbKey()).firstResult());
            assertNotNull(v, "missing v1 for " + key);
            assertEquals(key.seedBody(), v.body);
            for (PromptLabel label : PromptLabel.values()) {
                PromptLabelAssignment a = QuarkusTransaction.requiringNew().call(() ->
                        PromptLabelAssignment.<PromptLabelAssignment>find("promptKey = ?1 and label = ?2", key.dbKey(), label.dbValue()).firstResult());
                assertEquals(v.id, a.versionId, key + " " + label);
            }
        }
    }

    @Test
    void seedingIsIdempotent() {
        seeder.seed();
        seeder.seed();
        long versions = QuarkusTransaction.requiringNew().call(() -> PromptVersion.count());
        assertEquals(PromptKey.values().length, versions);
    }

    @Test
    void seedBodiesHaveExpectedEndings() {
        assertTrue(PromptKey.RANK.seedBody().endsWith("{items}\n"));
        assertFalse(PromptKey.JSON_REPAIR.seedBody().endsWith("\n"));
        assertEquals(PromptKey.SEGMENT, PromptKey.fromDb("segment").orElseThrow());
        assertTrue(PromptKey.fromDb("nope").isEmpty());
    }
}
