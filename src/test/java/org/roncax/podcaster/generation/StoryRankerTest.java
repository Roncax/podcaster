package org.roncax.podcaster.generation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.domain.Cluster;
import org.roncax.podcaster.domain.Item;
import org.roncax.podcaster.domain.Selection;
import org.roncax.podcaster.llm.GenerationException;
import org.roncax.podcaster.support.FakeChatModel;
import org.roncax.podcaster.support.TestPrompts;

class StoryRankerTest {
    StoryRanker ranker = new StoryRanker();

    static Item item(long id, String title) {
        Item i = new Item();
        i.id = id;
        i.title = title;
        i.summary = "Summary for " + title;
        return i;
    }

    List<Item> items = List.of(item(1, "Alpha"), item(2, "Beta"), item(3, "Gamma"));

    @Test
    void sanitizesAndSortsClusters() {
        FakeChatModel model = new FakeChatModel().respond("""
                ```json
                {"clusters":[
                  {"headline":"B story","itemIds":[2,99],"importance":5},
                  {"headline":"A story","itemIds":[1,2],"importance":12},
                  {"headline":"Nothing","itemIds":[42],"importance":9}
                ]}
                ```""");

        Selection s = ranker.rank(model, TestPrompts.seeded(), "Daily", "it", null, items);

        assertEquals(2, s.clusters().size());
        Cluster first = s.clusters().get(0);
        assertEquals("A story", first.headline());
        assertEquals(10, first.importance());
        assertEquals(List.of(1L), first.itemIds());
        assertEquals(List.of(2L), s.clusters().get(1).itemIds());
    }

    @Test
    void promptListsItemsLanguageAndFocus() {
        FakeChatModel model = new FakeChatModel().respond("{\"clusters\":[{\"headline\":\"x\",\"itemIds\":[3],\"importance\":3}]}");
        ranker.rank(model, TestPrompts.seeded(), "Daily", "it", "Prioritise AI news", items);
        String prompt = model.userMessage(0);
        assertTrue(prompt.startsWith("TASK: RANK"));
        assertTrue(prompt.contains("[id=1] Alpha"));
        assertTrue(prompt.contains("Italian"));
        assertTrue(prompt.contains("Prioritise AI news"));
    }

    @Test
    void repairsInvalidJsonOnce() {
        FakeChatModel model = new FakeChatModel().respond("I think the clusters are...",
                "{\"clusters\":[{\"headline\":\"x\",\"itemIds\":[1],\"importance\":3}]}");
        Selection s = ranker.rank(model, TestPrompts.seeded(), "Daily", "en", null, items);
        assertEquals(1, s.clusters().size());
        assertEquals(2, model.requests.size());
        assertTrue(model.userMessage(1).contains("could not be parsed as JSON"));
    }

    @Test
    void failsAfterTwoInvalidReplies() {
        FakeChatModel model = new FakeChatModel().respond("nope", "still nope");
        assertThrows(GenerationException.class, () -> ranker.rank(model, TestPrompts.seeded(), "Daily", "en", null, items));
    }

    @Test
    void failsWhenNoClusterReferencesCandidates() {
        FakeChatModel model = new FakeChatModel().respond("{\"clusters\":[{\"headline\":\"x\",\"itemIds\":[77],\"importance\":3}]}");
        assertThrows(GenerationException.class, () -> ranker.rank(model, TestPrompts.seeded(), "Daily", "en", null, items));
    }

    @Test
    void blankHeadlineFallsBackToFirstItemTitle() {
        FakeChatModel model = new FakeChatModel().respond("{\"clusters\":[{\"headline\":\"\",\"itemIds\":[3],\"importance\":3}]}");
        assertEquals("Gamma", ranker.rank(model, TestPrompts.seeded(), "Daily", "en", null, items).clusters().get(0).headline());
    }
}
