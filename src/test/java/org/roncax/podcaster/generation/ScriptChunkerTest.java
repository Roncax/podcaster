package org.roncax.podcaster.generation;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ScriptChunkerTest {
    static final Duration CHUNK = Duration.ofMillis(400);
    static final Duration SEGMENT = Duration.ofMillis(1200);

    @Test
    void shortPartsBecomeOneChunkEach() {
        List<TtsChunk> chunks = ScriptChunker.chunk(List.of("Intro.", "Body one. Body two.", "Outro."), 500, CHUNK, SEGMENT);
        assertEquals(List.of("Intro.", "Body one. Body two.", "Outro."), chunks.stream().map(TtsChunk::text).toList());
        assertTrue(chunks.stream().allMatch(c -> c.pauseAfter().equals(SEGMENT)));
        assertEquals(List.of(0, 1, 2), chunks.stream().map(TtsChunk::index).toList());
    }

    @Test
    void packsSentencesUpToLimitAndPreservesText() {
        String paragraph = IntStream.range(0, 10)
                .mapToObj(i -> "Sentence number " + i + " is here and it is long enough to matter.")
                .collect(Collectors.joining(" "));
        List<TtsChunk> chunks = ScriptChunker.chunk(List.of(paragraph), 200, CHUNK, SEGMENT);
        assertTrue(chunks.size() > 1);
        assertTrue(chunks.stream().allMatch(c -> c.text().length() <= 200));
        assertEquals(paragraph, chunks.stream().map(TtsChunk::text).collect(Collectors.joining(" ")));
        assertEquals(SEGMENT, chunks.get(chunks.size() - 1).pauseAfter());
        assertEquals(CHUNK, chunks.get(0).pauseAfter());
    }

    @Test
    void paragraphBreakStartsNewChunk() {
        assertEquals(2, ScriptChunker.chunk(List.of("A short one.\n\nAnother short."), 500, CHUNK, SEGMENT).size());
    }

    @Test
    void overlongSentenceIsSplitWithoutLosingWords() {
        String sentence = IntStream.range(0, 150).mapToObj(i -> "word" + i + (i % 10 == 9 ? "," : ""))
                .collect(Collectors.joining(" ")) + ".";
        List<TtsChunk> chunks = ScriptChunker.chunk(List.of(sentence), 500, CHUNK, SEGMENT);
        assertTrue(chunks.size() >= 2);
        assertTrue(chunks.stream().allMatch(c -> c.text().length() <= 500));
        assertEquals(sentence, chunks.stream().map(TtsChunk::text).collect(Collectors.joining(" ")));
    }

    @Test
    void chunksKnowTheirPart() {
        List<TtsChunk> chunks = ScriptChunker.chunk(List.of("Intro.", "One. Two.\n\nThree.", "Outro."), 500, CHUNK, SEGMENT);
        assertEquals(List.of(0, 1, 1, 2), chunks.stream().map(TtsChunk::part).toList());
    }
}
