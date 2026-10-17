package org.roncax.podcaster.ingestion;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ToItemTest {
    @Test
    void keepsDiscussionUrlOnlyWhenHttp() {
        var good = new RawItem("http://a/1", "T", null, Instant.now(), "s", null, "https://reddit.com/r/x/comments/1");
        var bad = new RawItem("http://a/2", "T", null, Instant.now(), "s", null, "javascript:alert(1)");
        assertEquals("https://reddit.com/r/x/comments/1", IngestionService.toItem(1, 2, new CollectedItem(good, "f", "h")).discussionUrl);
        assertNull(IngestionService.toItem(1, 2, new CollectedItem(bad, "f", "h")).discussionUrl);
        assertEquals(1000, IngestionService.toItem(1, 2, new CollectedItem(
                new RawItem("http://a/3", "x".repeat(2000), null, null, null, null), null, "h")).title.length());
    }
}
