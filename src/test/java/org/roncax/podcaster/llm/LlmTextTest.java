package org.roncax.podcaster.llm;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LlmTextTest {
    @Test
    void stripsThinkBlocks() {
        assertEquals("Hello there.", LlmText.clean("<think>\nLet me reason...\n</think>\n\nHello there."));
    }

    @Test
    void unwrapsCodeFences() {
        assertEquals("{\"a\":1}", LlmText.clean("```json\n{\"a\":1}\n```"));
    }

    @Test
    void findsJsonInsideProseAndThinking() {
        assertEquals("{\"a\":{\"b\":2}}", LlmText.jsonObject("<think>{not this}</think>Sure! Here it is: {\"a\":{\"b\":2}} Hope it helps."));
    }

    @Test
    void missingJsonIsAnError() {
        assertThrows(IllegalArgumentException.class, () -> LlmText.jsonObject("no json here"));
    }

    @Test
    void nullIsEmpty() {
        assertEquals("", LlmText.clean(null));
    }
}
