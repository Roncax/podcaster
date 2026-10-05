package org.roncax.podcaster.generation;

import dev.langchain4j.model.chat.ChatModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.util.*;
import org.roncax.podcaster.config.PodcasterConfig;
import org.roncax.podcaster.domain.Item;
import org.roncax.podcaster.domain.Outline;
import org.roncax.podcaster.domain.OutlineSegment;
import org.roncax.podcaster.domain.Show;
import org.roncax.podcaster.llm.GenerationException;
import org.roncax.podcaster.llm.JsonChat;

@ApplicationScoped
public class ScriptWriter {
    private static final int TAIL_CHARS = 300;
    private final int maxSourceChars;

    @Inject
    public ScriptWriter(PodcasterConfig config) {
        this(config.script().maxSourceChars());
    }

    public ScriptWriter(int maxSourceChars) {
        this.maxSourceChars = maxSourceChars;
    }

    public Script write(ChatModel model, Show show, Outline outline, Map<Long, Item> items, LocalDate date) {
        List<String> segments = new ArrayList<>();
        String previousTail = null;
        for (OutlineSegment seg : outline.segments()) {
            List<Item> sourceItems = seg.itemIds().stream().map(items::get).filter(Objects::nonNull).toList();
            String prompt = Prompts.segment(show.language, seg.headline(), seg.words(), sources(sourceItems), previousTail, show.focusPrompt);
            String text = TtsTextNormalizer.normalize(model.chat(prompt), show.language);
            if (text.isBlank()) throw new GenerationException("Model returned an empty segment for '" + seg.headline() + "'");
            segments.add(text);
            previousTail = tail(text);
        }
        List<String> headlines = outline.segments().stream().map(OutlineSegment::headline).toList();
        Framing framing = JsonChat.ask(model, Prompts.framing(show.name, show.language, date, headlines), Framing.class);
        String intro = TtsTextNormalizer.normalize(framing.intro(), show.language);
        String outro = TtsTextNormalizer.normalize(framing.outro(), show.language);
        if (intro.isBlank() || outro.isBlank()) throw new GenerationException("Model returned an empty intro or outro");
        String title = framing.title() == null || framing.title().isBlank() ? show.name + " – " + date : framing.title().trim();

        List<String> parts = new ArrayList<>();
        parts.add(intro);
        parts.addAll(segments);
        parts.add(outro);
        return new Script(title, showNotes(framing.description(), show.language, outline, items), parts);
    }

    String sources(List<Item> sourceItems) {
        int budget = maxSourceChars / Math.max(1, sourceItems.size());
        List<String> blocks = new ArrayList<>();
        for (Item item : sourceItems) {
            String text = item.bestText();
            if (text.length() > budget) text = text.substring(0, budget) + "…";
            blocks.add("TITLE: " + item.title + "\nURL: " + item.url + "\nTEXT:\n" + text);
        }
        return String.join("\n\n---\n\n", blocks);
    }

    static String tail(String text) {
        if (text.length() <= TAIL_CHARS) return text;
        String t = text.substring(text.length() - TAIL_CHARS);
        int space = t.indexOf(' ');
        return space >= 0 ? t.substring(space + 1) : t;
    }

    static String showNotes(String description, String language, Outline outline, Map<Long, Item> items) {
        String label = language != null && language.startsWith("it") ? "Fonti" : "Sources";
        StringBuilder sb = new StringBuilder(description == null ? "" : description.trim());
        sb.append("\n\n").append(label).append(":\n");
        for (Long id : outline.itemIds()) {
            Item item = items.get(id);
            if (item != null) sb.append("- ").append(item.title).append(" — ").append(item.url).append('\n');
        }
        return sb.toString().trim();
    }
}
