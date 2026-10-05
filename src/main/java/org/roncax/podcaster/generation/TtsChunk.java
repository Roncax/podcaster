package org.roncax.podcaster.generation;

import java.time.Duration;

public record TtsChunk(int index, String text, Duration pauseAfter) {}
