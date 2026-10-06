package org.roncax.podcaster.support;

import java.util.EnumMap;
import java.util.Map;
import org.roncax.podcaster.prompts.PromptKey;
import org.roncax.podcaster.prompts.PromptRenderer;
import org.roncax.podcaster.prompts.PromptSet;

public final class TestPrompts {
    private static final PromptRenderer RENDERER = new PromptRenderer();

    private TestPrompts() {}

    public static PromptSet seeded() {
        Map<PromptKey, PromptSet.Entry> entries = new EnumMap<>(PromptKey.class);
        for (PromptKey key : PromptKey.values()) entries.put(key, new PromptSet.Entry(1, key.seedBody()));
        return new PromptSet(RENDERER, entries);
    }

    public static PromptSet with(PromptKey key, String body, int version) {
        Map<PromptKey, PromptSet.Entry> entries = new EnumMap<>(PromptKey.class);
        for (PromptKey k : PromptKey.values()) entries.put(k, new PromptSet.Entry(1, k.seedBody()));
        entries.put(key, new PromptSet.Entry(version, body));
        return new PromptSet(RENDERER, entries);
    }
}
