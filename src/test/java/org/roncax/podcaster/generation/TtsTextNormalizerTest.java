package org.roncax.podcaster.generation;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TtsTextNormalizerTest {
    @Test
    void stripsMarkdownLinksAndUrls() {
        String in = "## Titolo\n**Grassetto** e *corsivo* con [un link](http://x.com) e https://example.com/a?b=1 fine.";
        assertEquals("Titolo Grassetto e corsivo con un link e fine.", TtsTextNormalizer.normalize(in, "it"));
    }

    @Test
    void spellsSymbolsInItalian() {
        assertEquals("Il 45 per cento paga 30 euro e altro",
                TtsTextNormalizer.normalize("Il 45% paga €30 & altro", "it"));
    }

    @Test
    void spellsSymbolsInEnglish() {
        assertEquals("Up 5 percent at 10 dollars and more",
                TtsTextNormalizer.normalize("Up 5% at $10 & more", "en"));
    }

    @Test
    void keepsParagraphsAndJoinsWrappedLines() {
        assertEquals("Line one still one.\n\nSecond.",
                TtsTextNormalizer.normalize("Line one\nstill one.\n\n\n\n  Second.  ", "en"));
    }

    @Test
    void removesBulletsAndThinking() {
        assertEquals("first second", TtsTextNormalizer.normalize("<think>plan</think>- first\n- second", "en"));
    }
}
