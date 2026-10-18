package org.roncax.podcaster.tts;

import java.util.Set;

public interface TtsEngine {
    /** Returns a PCM WAV file. */
    byte[] synthesize(String text, VoiceConfig voice) throws Exception;

    Set<String> voices() throws Exception;

    /** Chunk size and parallelism to use for this voice's engine. */
    TtsLimits limits(String voiceId);
}
