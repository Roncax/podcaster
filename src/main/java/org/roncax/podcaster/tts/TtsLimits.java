package org.roncax.podcaster.tts;

/** How a voice's text is split and how many chunks may be synthesized at once. */
public record TtsLimits(int chunkChars, int parallelism) {}
