package org.roncax.podcaster.prompts;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.roncax.podcaster.llm.GenerationException;

/** Resolves which version of each prompt a show uses: the show's label for the mode. */
@ApplicationScoped
public class PromptResolver {
    public enum Mode { PRODUCTION, DRAFT }

    @Inject PromptRenderer renderer;
    private final Map<String, PromptSet> cache = new ConcurrentHashMap<>();

    public PromptSet resolve(long showId, Mode mode) {
        return cache.computeIfAbsent(showId + ":" + mode, k -> load(showId, mode));
    }

    public void invalidate() {
        cache.clear();
    }

    private PromptSet load(long showId, Mode mode) {
        String label = (mode == Mode.DRAFT ? PromptLabel.DRAFT : PromptLabel.PRODUCTION).dbValue();
        Map<PromptKey, PromptSet.Entry> entries = QuarkusTransaction.requiringNew().call(() -> {
            Map<PromptKey, PromptSet.Entry> map = new EnumMap<>(PromptKey.class);
            for (PromptKey key : PromptKey.values()) {
                Long versionId = PromptLabelAssignment.<PromptLabelAssignment>find("showId = ?1 and promptKey = ?2 and label = ?3",
                                showId, key.dbKey(), label)
                        .firstResultOptional().map(a -> a.versionId)
                        .orElseThrow(() -> new GenerationException("No " + label + " version for prompt " + key.dbKey() + " in show " + showId));
                PromptVersion v = PromptVersion.findById(versionId);
                map.put(key, new PromptSet.Entry(v.version, v.body));
            }
            return map;
        });
        return new PromptSet(renderer, entries);
    }
}
