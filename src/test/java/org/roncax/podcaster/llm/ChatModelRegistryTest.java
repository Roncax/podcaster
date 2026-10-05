package org.roncax.podcaster.llm;

import static org.junit.jupiter.api.Assertions.*;

import dev.langchain4j.model.chat.ChatModel;
import io.quarkiverse.langchain4j.ModelName;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.support.WireMockResource;

@QuarkusTest
@WithTestResource(WireMockResource.class)
@WithTestResource(org.roncax.podcaster.support.PostgresResource.class)
class ChatModelRegistryTest {
    @Inject ChatModelRegistry registry;
    @Inject @Any Instance<ChatModel> models;

    @Test
    void allSlotBeansExistEvenWhenDisabled() {
        for (String slot : new String[] {"gpt", "claude", "gemini", "local"}) {
            assertTrue(models.select(ModelName.Literal.of(slot)).isResolvable(), "slot bean missing: " + slot);
        }
    }

    @Test
    void disabledSlotsAreNotAvailable() {
        assertTrue(registry.availableNames().isEmpty(), registry.availableNames().toString());
        UnknownModelException ex = assertThrows(UnknownModelException.class, () -> registry.get("claude"));
        assertTrue(ex.getMessage().contains("claude"));
    }
}
