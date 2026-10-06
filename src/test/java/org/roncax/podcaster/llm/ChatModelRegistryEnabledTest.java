package org.roncax.podcaster.llm;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.support.WireMockResource;

@QuarkusTest
@TestProfile(ChatModelRegistryEnabledTest.LocalEnabled.class)
@WithTestResource(WireMockResource.class)
@WithTestResource(org.roncax.podcaster.support.PostgresResource.class)
class ChatModelRegistryEnabledTest {

    public static class LocalEnabled implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.langchain4j.ollama.local.enable-integration", "true",
                    "quarkus.langchain4j.ollama.local.base-url", "http://localhost:1");
        }
    }

    @Inject ChatModelRegistry registry;

    @Test
    void enabledSlotIsAvailableAndResolvable() {
        assertEquals(Set.of("local"), registry.availableNames());
        assertTrue(registry.isAvailable("local"));
        assertNotNull(registry.get("local"));
        assertFalse(registry.isAvailable("gpt"));
    }
}
