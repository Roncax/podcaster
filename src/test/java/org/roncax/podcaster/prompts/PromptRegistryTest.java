package org.roncax.podcaster.prompts;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.domain.Show;
import org.roncax.podcaster.support.TestData;
import org.roncax.podcaster.support.WireMockResource;

@QuarkusTest
@WithTestResource(WireMockResource.class)
@WithTestResource(org.roncax.podcaster.support.PostgresResource.class)
class PromptRegistryTest {
    @Inject PromptRegistry registry;
    @Inject PromptResolver resolver;

    static final String REPAIR_V2 = "JSON broken ({error}). Send only the object.";

    @BeforeEach
    void clean() { TestData.cleanDb(); }

    private static String repair(PromptSet set) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("error", "E");
        return set.render(PromptKey.JSON_REPAIR, vars);
    }

    @Test
    void newShowGetsVersionOneOfEveryKey() {
        Show show = TestData.show("fresh");
        for (PromptKey key : PromptKey.values()) {
            assertEquals(Map.of(PromptLabel.PRODUCTION, 1, PromptLabel.DRAFT, 1), registry.labels(show.id, key), key.dbKey());
            assertEquals(key.seedBody(), registry.version(show.id, key, 1).orElseThrow().body);
            assertEquals("Forked from default v1", registry.version(show.id, key, 1).orElseThrow().note);
        }
    }

    @Test
    void createVersionLandsAsDraftOnlyForThatShow() {
        Show a = TestData.show("a");
        Show b = TestData.show("b");
        PromptVersion v2 = registry.createVersion(a.id, PromptKey.JSON_REPAIR, REPAIR_V2, "shorter");
        assertEquals(2, v2.version);
        assertEquals(Map.of(PromptLabel.PRODUCTION, 1, PromptLabel.DRAFT, 2), registry.labels(a.id, PromptKey.JSON_REPAIR));
        assertEquals("JSON broken (E). Send only the object.", repair(resolver.resolve(a.id, PromptResolver.Mode.DRAFT)));
        assertTrue(repair(resolver.resolve(a.id, PromptResolver.Mode.PRODUCTION)).startsWith("Your previous reply"));
        assertTrue(repair(resolver.resolve(b.id, PromptResolver.Mode.DRAFT)).startsWith("Your previous reply"));
        assertEquals(1, registry.versions(b.id, PromptKey.JSON_REPAIR).size());
    }

    @Test
    void versionsAreNumberedPerShow() {
        Show a = TestData.show("a");
        Show b = TestData.show("b");
        registry.createVersion(a.id, PromptKey.JSON_REPAIR, REPAIR_V2, null);
        assertEquals(2, registry.createVersion(b.id, PromptKey.JSON_REPAIR, REPAIR_V2, null).version);
    }

    @Test
    void invalidBodyIsRejectedAndNothingStored() {
        Show show = TestData.show("bad");
        InvalidPromptException ex = assertThrows(InvalidPromptException.class,
                () -> registry.createVersion(show.id, PromptKey.RANK, "no variables here", null));
        assertTrue(ex.errors().contains("Missing required variable {items}"));
        assertEquals(1, registry.versions(show.id, PromptKey.RANK).size());
    }

    @Test
    void promoteAndRollBack() {
        Show show = TestData.show("promo");
        registry.createVersion(show.id, PromptKey.JSON_REPAIR, REPAIR_V2, null);
        registry.setLabel(show.id, PromptKey.JSON_REPAIR, PromptLabel.PRODUCTION, 2);
        assertTrue(repair(resolver.resolve(show.id, PromptResolver.Mode.PRODUCTION)).startsWith("JSON broken"));
        registry.setLabel(show.id, PromptKey.JSON_REPAIR, PromptLabel.PRODUCTION, 1);
        assertTrue(repair(resolver.resolve(show.id, PromptResolver.Mode.PRODUCTION)).startsWith("Your previous reply"));
        assertEquals(java.util.List.of("draft"), registry.labelsOf(show.id, PromptKey.JSON_REPAIR, 2));
    }

    @Test
    void labelCannotPointAtAnotherShowsVersion() {
        Show a = TestData.show("a");
        Show b = TestData.show("b");
        registry.createVersion(a.id, PromptKey.JSON_REPAIR, REPAIR_V2, null);
        assertThrows(InvalidPromptException.class, () -> registry.setLabel(b.id, PromptKey.JSON_REPAIR, PromptLabel.PRODUCTION, 2));
    }

    @Test
    void unknownShowIs404() {
        assertThrows(jakarta.ws.rs.NotFoundException.class,
                () -> registry.createVersion(999_999L, PromptKey.JSON_REPAIR, REPAIR_V2, null));
    }

    @Test
    void resetToDefaultCreatesDraftFromLatestDefault() {
        Show show = TestData.show("reset");
        registry.createVersion(show.id, PromptKey.JSON_REPAIR, REPAIR_V2, null);
        registry.setLabel(show.id, PromptKey.JSON_REPAIR, PromptLabel.PRODUCTION, 2);
        registry.createDefaultVersion(PromptKey.JSON_REPAIR, REPAIR_V2 + " Default two.", "d2");

        PromptVersion reset = registry.resetToDefault(show.id, PromptKey.JSON_REPAIR);

        assertEquals(3, reset.version);
        assertEquals(REPAIR_V2 + " Default two.", reset.body);
        assertEquals("Reset to default v2", reset.note);
        assertEquals(Map.of(PromptLabel.PRODUCTION, 2, PromptLabel.DRAFT, 3), registry.labels(show.id, PromptKey.JSON_REPAIR));
    }

    @Test
    void newDefaultDoesNotChangeExistingShowsButForksNewOnes() {
        Show before = TestData.show("before");
        registry.createDefaultVersion(PromptKey.JSON_REPAIR, REPAIR_V2, "d2");
        Show after = TestData.show("after");
        assertTrue(repair(resolver.resolve(before.id, PromptResolver.Mode.PRODUCTION)).startsWith("Your previous reply"));
        assertTrue(repair(resolver.resolve(after.id, PromptResolver.Mode.PRODUCTION)).startsWith("JSON broken"));
        assertEquals("Forked from default v2", registry.version(after.id, PromptKey.JSON_REPAIR, 1).orElseThrow().note);
    }

    @Test
    void invalidDefaultIsRejected() {
        assertThrows(InvalidPromptException.class, () -> registry.createDefaultVersion(PromptKey.RANK, "nothing", null));
        assertEquals(1, registry.defaultVersions(PromptKey.RANK).size());
    }

    @Test
    void deletingShowRemovesOnlyItsPrompts() {
        Show show = TestData.show("gone");
        registry.createVersion(show.id, PromptKey.JSON_REPAIR, REPAIR_V2, null);
        QuarkusTransaction.requiringNew().run(() -> Show.deleteById(show.id));
        assertEquals(0L, QuarkusTransaction.requiringNew().call(() -> PromptVersion.count("showId is not null")));
        assertEquals(0L, QuarkusTransaction.requiringNew().call(() -> PromptLabelAssignment.count()));
        assertEquals(1, registry.defaultVersions(PromptKey.JSON_REPAIR).size());
    }

    @Test
    void versionsAreNewestFirst() {
        Show show = TestData.show("hist");
        registry.createVersion(show.id, PromptKey.JSON_REPAIR, REPAIR_V2, "two");
        registry.createVersion(show.id, PromptKey.JSON_REPAIR, REPAIR_V2 + " Please.", "three");
        assertEquals(java.util.List.of(3, 2, 1), registry.versions(show.id, PromptKey.JSON_REPAIR).stream().map(v -> v.version).toList());
        assertEquals("two", registry.version(show.id, PromptKey.JSON_REPAIR, 2).orElseThrow().note);
    }
}
