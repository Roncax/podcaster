package org.roncax.podcaster.tts;

import java.nio.file.Path;
import java.util.List;

/** {@code partStarts}: start time in seconds of each script part (intro, segments, outro), in order. */
public record AssembledAudio(Path file, double durationSeconds, long sizeBytes, List<Double> partStarts) {}
