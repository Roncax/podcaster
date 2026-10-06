package org.roncax.podcaster.tts;

import java.nio.file.Path;

public record AssembledAudio(Path file, double durationSeconds, long sizeBytes) {}
