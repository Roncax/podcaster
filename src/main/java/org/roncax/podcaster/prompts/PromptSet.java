package org.roncax.podcaster.prompts;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.roncax.podcaster.llm.GenerationException;

/** The prompt versions resolved for one stage of one show. */
public final class PromptSet {
    public record Entry(int version, String body) {}

    private final PromptRenderer renderer;
    private final Map<PromptKey, Entry> entries;

    public PromptSet(PromptRenderer renderer, Map<PromptKey, Entry> entries) {
        this.renderer = renderer;
        this.entries = Map.copyOf(entries);
    }

    public String render(PromptKey key, Map<String, Object> vars) {
        Entry entry = entries.get(key);
        if (entry == null) throw new GenerationException("No version resolved for prompt " + key.dbKey());
        Map<String, Object> all = new HashMap<>(vars);
        if (key.contract() != null) all.put("contract", key.contract());
        try {
            String body = renderer.render(entry.body(), all);
            return key.header() == null ? body : "TASK: " + key.header() + "\n" + body;
        } catch (RuntimeException e) {
            throw new GenerationException("prompt " + key.dbKey() + " v" + entry.version() + ": " + e.getMessage(), e);
        }
    }

    public int version(PromptKey key) {
        return entries.get(key).version();
    }

    public Map<String, Integer> versions(PromptKey... keys) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (PromptKey key : keys) out.put(key.dbKey(), version(key));
        return out;
    }
}
