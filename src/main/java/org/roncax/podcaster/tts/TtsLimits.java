package org.roncax.podcaster.tts;

/**
 * How a voice's text is split and how many chunks may be synthesized at once: chunks aim for {@code chunkChars} and
 * may run to {@code maxChunkChars} to finish a sentence.
 */
public record TtsLimits(int chunkChars, int maxChunkChars, int parallelism) {}
