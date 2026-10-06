package org.roncax.podcaster.generation;

import dev.langchain4j.model.chat.ChatModel;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.*;
import org.roncax.podcaster.domain.Cluster;
import org.roncax.podcaster.domain.Item;
import org.roncax.podcaster.domain.Selection;
import org.roncax.podcaster.llm.GenerationException;
import org.roncax.podcaster.llm.JsonChat;
import org.roncax.podcaster.prompts.PromptSet;

@ApplicationScoped
public class StoryRanker {
    private static final int SNIPPET_CHARS = 500;

    public Selection rank(ChatModel model, PromptSet prompts, String showName, String language, String focusPrompt, List<Item> candidates) {
        List<String> lines = candidates.stream()
                .map(i -> "[id=" + i.id + "] " + i.title + " — " + snippet(i.bestText()))
                .toList();
        Selection raw = JsonChat.ask(model, Prompts.rank(prompts, showName, language, focusPrompt, lines), Selection.class, prompts);
        return sanitize(raw, candidates);
    }

    static Selection sanitize(Selection raw, List<Item> candidates) {
        Map<Long, Item> byId = new HashMap<>();
        candidates.forEach(i -> byId.put(i.id, i));
        Set<Long> used = new HashSet<>();
        List<Cluster> clusters = new ArrayList<>();
        if (raw != null && raw.clusters() != null) {
            for (Cluster c : raw.clusters()) {
                if (c == null || c.itemIds() == null) continue;
                List<Long> ids = c.itemIds().stream()
                        .filter(Objects::nonNull)
                        .filter(byId::containsKey)
                        .filter(used::add)
                        .toList();
                if (ids.isEmpty()) continue;
                String headline = c.headline() == null || c.headline().isBlank() ? byId.get(ids.get(0)).title : c.headline().trim();
                int importance = Math.max(1, Math.min(10, c.importance()));
                clusters.add(new Cluster(headline, ids, importance));
            }
        }
        if (clusters.isEmpty()) throw new GenerationException("Ranker returned no usable clusters");
        clusters.sort(Comparator.comparingInt(Cluster::importance).reversed());
        return new Selection(clusters);
    }

    private static String snippet(String text) {
        String s = text.replaceAll("\\s+", " ").trim();
        return s.length() > SNIPPET_CHARS ? s.substring(0, SNIPPET_CHARS) + "…" : s;
    }
}
