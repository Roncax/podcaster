package org.roncax.podcaster.tts;

import java.util.Set;

public interface TtsEngine {
    /** Returns a PCM WAV file. */
    byte[] synthesize(String text, VoiceConfig voice) throws Exception;

    Set<String> voices() throws Exception;
}
