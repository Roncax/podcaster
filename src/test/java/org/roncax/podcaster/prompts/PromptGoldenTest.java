package org.roncax.podcaster.prompts;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.support.LegacyPrompts;
import org.roncax.podcaster.support.TestPrompts;

/** Seeded v1 must render exactly what the pre-registry code produced. */
class PromptGoldenTest {
    PromptSet prompts = TestPrompts.seeded();

    private static Map<String, Object> vars(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void rankWithAndWithoutFocus() {
        List<String> lines = List.of("[id=1] Primo — testo \"citato\" & altro", "[id=2] Secondo — 45% {graffe}");
        for (String focus : new String[] {null, "Prioritise AI news"}) {
            String expected = LegacyPrompts.rank("Daily", "it", focus, lines);
            String actual = prompts.render(PromptKey.RANK, vars(
                    "showName", "Daily", "language", "Italian", "focus", focus, "items", String.join("\n", lines)));
            assertEquals(expected, actual, "focus=" + focus);
        }
    }

    @Test
    void segmentFirstAndFollowing() {
        String sources = "TITLE: A\nURL: https://x/1\nTEXT:\nBody with \"quotes\" & {braces}";
        assertEquals(LegacyPrompts.segment("it", "Story A", 400, sources, null, null),
                prompts.render(PromptKey.SEGMENT, vars("language", "Italian", "headline", "Story A", "words", 400,
                        "sources", sources, "previousTail", null, "focus", null)));
        assertEquals(LegacyPrompts.segment("it", "Story B", 300, sources, "…fine del segmento.", "Solo politica"),
                prompts.render(PromptKey.SEGMENT, vars("language", "Italian", "headline", "Story B", "words", 300,
                        "sources", sources, "previousTail", "…fine del segmento.", "focus", "Solo politica")));
    }

    @Test
    void framing() {
        LocalDate date = LocalDate.of(2026, 10, 6);
        List<String> headlines = List.of("Story A", "Story B");
        String stories = String.join("\n", IntStream.range(0, headlines.size()).mapToObj(i -> (i + 1) + ". " + headlines.get(i)).toList());
        assertEquals(LegacyPrompts.framing("Daily", "it", date, headlines),
                prompts.render(PromptKey.FRAMING, vars("showName", "Daily", "language", "Italian",
                        "date", "6 ottobre 2026", "stories", stories)));
    }

    @Test
    void jsonRepair() {
        assertEquals(LegacyPrompts.REPAIR.formatted("Unexpected character ('I')"),
                prompts.render(PromptKey.JSON_REPAIR, vars("error", "Unexpected character ('I')")));
    }
}
