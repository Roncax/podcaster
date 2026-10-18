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
    /** A sentence of exactly {@code chars} characters, ending with a full stop. */
    static String sentence(char letter, int chars) {
        return String.valueOf(letter).repeat(chars - 1) + ".";
    }

    static List<String> texts(List<TtsChunk> chunks) {
        return chunks.stream().map(TtsChunk::text).toList();
    }

    @Test
    void sentenceCrossingTheSoftLimitFinishesInTheSameChunk() {
        String a = sentence('a', 100), b = sentence('b', 100), c = sentence('c', 150), d = sentence('d', 20);
        List<TtsChunk> chunks = ScriptChunker.chunk(List.of(String.join(" ", a, b, c, d)), 300, 400, CHUNK, SEGMENT);
        assertEquals(List.of(a + " " + b + " " + c, d), texts(chunks));
    }

    @Test
    void sentencePassingTheHardLimitStartsTheNextChunk() {
        String a = sentence('a', 200), b = sentence('b', 250);
        List<TtsChunk> chunks = ScriptChunker.chunk(List.of(a + " " + b), 300, 400, CHUNK, SEGMENT);
        assertEquals(List.of(a, b), texts(chunks));
    }

    @Test
    void chunkPastTheSoftLimitTakesNoMoreSentences() {
        String a = sentence('a', 310), b = sentence('b', 20);
        List<TtsChunk> chunks = ScriptChunker.chunk(List.of(a + " " + b), 300, 400, CHUNK, SEGMENT);
        assertEquals(List.of(a, b), texts(chunks));
    }

    @Test
    void sentenceBetweenTheLimitsIsNeverSplit() {
        String words = IntStream.range(0, 40).mapToObj(i -> "parola" + i).collect(Collectors.joining(" ")) + ".";
        assertTrue(words.length() > 300 && words.length() <= 400, "length " + words.length());
        assertEquals(List.of(words), texts(ScriptChunker.chunk(List.of(words), 300, 400, CHUNK, SEGMENT)));
    }

    @Test
    void sentenceOverTheHardLimitIsSplitIntoPiecesWithinIt() {
        String words = IntStream.range(0, 120).mapToObj(i -> "parola" + i).collect(Collectors.joining(" ")) + ".";
        List<TtsChunk> chunks = ScriptChunker.chunk(List.of(words), 300, 400, CHUNK, SEGMENT);
        assertTrue(chunks.size() >= 2);
        assertTrue(chunks.stream().allMatch(c -> c.text().length() <= 400), texts(chunks).toString());
        assertEquals(words, String.join(" ", texts(chunks)));
    }
}
