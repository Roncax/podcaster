package org.roncax.podcaster.ingestion;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ContentHasherTest {
    @Test
    void normalizesCaseAndWhitespace() {
        assertEquals(ContentHasher.hash("Big  News", "Some text\n here"), ContentHasher.hash("big news", "some text here"));
    }

    @Test
    void differentTitlesDiffer() {
        assertNotEquals(ContentHasher.hash("A", "x"), ContentHasher.hash("B", "x"));
    }

    @Test
    void handlesNullText() {
        assertEquals(64, ContentHasher.hash("Title", null).length());
    }
}
