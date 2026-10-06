package org.roncax.podcaster.support;

import dev.langchain4j.model.chat.ChatModel;
import io.quarkus.test.junit.QuarkusMock;
import java.util.SortedSet;
import java.util.TreeSet;
import org.roncax.podcaster.llm.ChatModelRegistry;
import org.roncax.podcaster.llm.UnknownModelException;

public class FakeChatModelRegistry extends ChatModelRegistry {
    public final FakeChatModel model;

    public FakeChatModelRegistry(FakeChatModel model) {
        this.model = model;
    }

    /** Replaces the registry bean for the current test; only "fake" is available. */
    public static FakeChatModelRegistry install(FakeChatModel model) {
        FakeChatModelRegistry registry = new FakeChatModelRegistry(model);
        QuarkusMock.installMockForType(registry, ChatModelRegistry.class);
        return registry;
    }

    @Override
    public SortedSet<String> availableNames() {
        return new TreeSet<>(java.util.Set.of("fake"));
    }

    @Override
    public ChatModel get(String name) {
        if (!isAvailable(name)) throw new UnknownModelException(name, availableNames());
        return model;
    }
}
