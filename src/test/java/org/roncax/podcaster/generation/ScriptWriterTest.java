package org.roncax.podcaster.generation;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.domain.Item;
import org.roncax.podcaster.domain.Outline;
import org.roncax.podcaster.domain.OutlineSegment;
import org.roncax.podcaster.domain.Show;
import org.roncax.podcaster.llm.GenerationException;
import org.roncax.podcaster.support.FakeChatModel;
import org.roncax.podcaster.support.TestPrompts;

class ScriptWriterTest {
    static final String FRAMING = "{\"title\":\"Ep\",\"description\":\"Notes.\",\"intro\":\"Welcome.\",\"outro\":\"Bye.\"}";

    static Item item(long id, String text) {
        Item i = new Item();
        i.id = id;
        i.title = "Title " + id;
        i.url = "https://news.example/" + id;
        i.fullText = text;
        return i;
    }

    static Show show() {
        Show s = new Show();
        s.name = "Daily";
        s.language = "it";
        return s;
    }

    Map<Long, Item> items = new java.util.HashMap<>(Map.of(1L, item(1, "Alpha full text."), 2L, item(2, "Beta full text."), 3L, item(3, "Gamma full text.")));
    Outline outline = new Outline(900, List.of(
            new OutlineSegment("Story A", List.of(1L, 2L), 400),
            new OutlineSegment("Story B", List.of(3L), 300)));

    @Test
    void writesSegmentsThenFraming() {
        FakeChatModel model = new FakeChatModel().respond(
                "Segment A text. **Bold** words.",
                "<think>hmm</think>Segment B text.",
                FRAMING);

        Script script = new ScriptWriter(12000).write(model, TestPrompts.seeded(), show(), outline, items, LocalDate.of(2026, 10, 6));

        assertEquals(List.of("Welcome.", "Segment A text. Bold words.", "Segment B text.", "Bye."), script.parts());
        assertEquals("Ep", script.title());
        assertTrue(script.description().startsWith("Notes."));
        assertTrue(script.description().contains("Fonti:"));
        assertTrue(script.description().contains("https://news.example/3"));

        String first = model.userMessage(0);
        assertTrue(first.startsWith("TASK: SEGMENT"));
        assertTrue(first.contains("about 400 words"));
        assertTrue(first.contains("Italian"));
        assertTrue(first.contains("Alpha full text.") && first.contains("Beta full text."));
        assertTrue(first.contains("first story"));
        assertTrue(model.userMessage(1).contains("Segment A text."), "second segment gets a transition from the first");
        String framing = model.userMessage(2);
        assertTrue(framing.startsWith("TASK: FRAMING"));
        assertTrue(framing.contains("1. Story A") && framing.contains("2. Story B"));
        assertTrue(framing.contains("ottobre"), "date is localized to the show language");
    }

    @Test
    void emptySegmentFails() {
        FakeChatModel model = new FakeChatModel().respond("   ", "x", FRAMING);
        assertThrows(GenerationException.class,
                () -> new ScriptWriter(12000).write(model, TestPrompts.seeded(), show(), outline, items, LocalDate.now()));
    }

    @Test
    void sourceTextIsTruncatedToBudget() {
        Map<Long, Item> big = Map.of(1L, item(1, "x".repeat(5000)), 2L, item(2, "y"), 3L, item(3, "z"));
        FakeChatModel model = new FakeChatModel().respond("A.", "B.", FRAMING);
        new ScriptWriter(1000).write(model, TestPrompts.seeded(), show(), outline, big, LocalDate.now());
        assertFalse(model.userMessage(0).contains("x".repeat(600)));
    }

    @Test
    void segmentCutOffByTokenLimitFails() {
        dev.langchain4j.model.chat.ChatModel truncating = new dev.langchain4j.model.chat.ChatModel() {
            @Override
            public dev.langchain4j.model.chat.response.ChatResponse doChat(dev.langchain4j.model.chat.request.ChatRequest request) {
                return dev.langchain4j.model.chat.response.ChatResponse.builder()
                        .aiMessage(dev.langchain4j.data.message.AiMessage.from("A segment that stops mid"))
                        .finishReason(dev.langchain4j.model.output.FinishReason.LENGTH)
                        .build();
            }
        };
        GenerationException ex = assertThrows(GenerationException.class,
                () -> new ScriptWriter(12000).write(truncating, TestPrompts.seeded(), show(), outline, items, LocalDate.now()));
        assertTrue(ex.getMessage().contains("token limit"), ex.getMessage());
    }

    @Test
    void showNotesGroupLinksByStoryWithRedditThread() {
        items.get(3L).discussionUrl = "https://www.reddit.com/r/italy/comments/abc/x/";
        String notes = ScriptWriter.showNotes("Notes.", "it", outline, items);
        assertEquals("""
                Notes.

                Fonti:
                1. Story A
                - Title 1 — https://news.example/1
                - Title 2 — https://news.example/2
                2. Story B
                - Title 3 — https://news.example/3
                - Discussione su Reddit — https://www.reddit.com/r/italy/comments/abc/x/""", notes);
    }

    @Test
    void showNotesUseEnglishLabelsForOtherLanguages() {
        String notes = ScriptWriter.showNotes("Notes.", "en", outline, items);
        assertTrue(notes.contains("\n\nSources:\n1. Story A\n"), notes);
    }

}
