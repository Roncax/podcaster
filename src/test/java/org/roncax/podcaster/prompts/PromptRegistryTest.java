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
    void createVersionLandsAsDraftOnly() {
        PromptVersion v2 = registry.createVersion(PromptKey.JSON_REPAIR, REPAIR_V2, "shorter");
        assertEquals(2, v2.version);
        assertEquals(Map.of(PromptLabel.PRODUCTION, 1, PromptLabel.DRAFT, 2), registry.labels(PromptKey.JSON_REPAIR));
        assertEquals("JSON broken (E). Send only the object.", repair(resolver.resolve(null, PromptResolver.Mode.DRAFT)));
        assertTrue(repair(resolver.resolve(null, PromptResolver.Mode.PRODUCTION)).startsWith("Your previous reply"));
    }

    @Test
    void invalidBodyIsRejectedAndNothingStored() {
        InvalidPromptException ex = assertThrows(InvalidPromptException.class,
                () -> registry.createVersion(PromptKey.RANK, "no variables here", null));
        assertTrue(ex.errors().contains("Missing required variable {items}"));
        assertEquals(1, registry.versions(PromptKey.RANK).size());
    }

    @Test
    void promoteAndRollBack() {
        registry.createVersion(PromptKey.JSON_REPAIR, REPAIR_V2, null);
        registry.setLabel(PromptKey.JSON_REPAIR, PromptLabel.PRODUCTION, 2);
        assertTrue(repair(resolver.resolve(null, PromptResolver.Mode.PRODUCTION)).startsWith("JSON broken"));
        registry.setLabel(PromptKey.JSON_REPAIR, PromptLabel.PRODUCTION, 1);
        assertTrue(repair(resolver.resolve(null, PromptResolver.Mode.PRODUCTION)).startsWith("Your previous reply"));
        assertEquals(java.util.List.of("draft"), registry.labelsOf(PromptKey.JSON_REPAIR, 2));
    }

    @Test
    void unknownVersionIsRejected() {
        assertThrows(InvalidPromptException.class, () -> registry.setLabel(PromptKey.RANK, PromptLabel.PRODUCTION, 99));
    }

    @Test
    void showOverrideBeatsLabel() {
        Show pinned = TestData.show("pinned");
        Show other = TestData.show("other");
        registry.createVersion(PromptKey.JSON_REPAIR, REPAIR_V2, null);
        registry.pin(pinned.id, PromptKey.JSON_REPAIR, 2);

        assertTrue(repair(resolver.resolve(pinned.id, PromptResolver.Mode.PRODUCTION)).startsWith("JSON broken"));
        assertTrue(repair(resolver.resolve(other.id, PromptResolver.Mode.PRODUCTION)).startsWith("Your previous reply"));
        assertEquals(Map.of(PromptKey.JSON_REPAIR, 2), registry.overrides(pinned.id));

        registry.unpin(pinned.id, PromptKey.JSON_REPAIR);
        assertTrue(repair(resolver.resolve(pinned.id, PromptResolver.Mode.PRODUCTION)).startsWith("Your previous reply"));
    }

    @Test
    void pinningUnknownShowIs404() {
        assertThrows(jakarta.ws.rs.NotFoundException.class, () -> registry.pin(999_999L, PromptKey.RANK, 1));
    }

    @Test
    void deletingShowRemovesOnlyItsOverrides() {
        Show show = TestData.show("gone");
        registry.createVersion(PromptKey.JSON_REPAIR, REPAIR_V2, null);
        registry.pin(show.id, PromptKey.JSON_REPAIR, 2);

        QuarkusTransaction.requiringNew().run(() -> Show.deleteById(show.id));

        assertEquals(0L, QuarkusTransaction.requiringNew().call(() -> ShowPromptOverride.count()));
        assertEquals(2, registry.versions(PromptKey.JSON_REPAIR).size());
    }

    @Test
    void versionsAreNewestFirst() {
        registry.createVersion(PromptKey.JSON_REPAIR, REPAIR_V2, "two");
        registry.createVersion(PromptKey.JSON_REPAIR, REPAIR_V2 + " Please.", "three");
        assertEquals(java.util.List.of(3, 2, 1), registry.versions(PromptKey.JSON_REPAIR).stream().map(v -> v.version).toList());
        assertEquals("two", registry.version(PromptKey.JSON_REPAIR, 2).orElseThrow().note);
    }
}
