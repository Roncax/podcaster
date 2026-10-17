package org.roncax.podcaster.tts;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.support.TestAudio;

class WavConcatTest {
    @Test
    void joinsChunksWithPauses() {
        byte[] a = TestAudio.sineWav(0.5);
        WavConcat.Joined joined = WavConcat.concat(List.of(a, a), List.of(Duration.ofMillis(200), Duration.ZERO), List.of(0, 1));
        assertEquals(1.2, joined.durationSeconds(), 0.01);
        assertEquals(List.of(0.0, 0.7), joined.partStarts().stream().map(d -> Math.round(d * 100) / 100.0).toList());
        assertTrue(Wav.looksLikeWav(joined.toWavBytes()));
        assertEquals(joined.pcm().length, Wav.parse(joined.toWavBytes()).pcm().length);
    }

    @Test
    void rejectsMixedFormats() {
        byte[] mono = TestAudio.sineWav(0.1);
        byte[] stereo = TestAudio.wav(new byte[400], TestAudio.SAMPLE_RATE, 2, 16);
        assertThrows(IllegalStateException.class,
                () -> WavConcat.concat(List.of(mono, stereo), List.of(Duration.ZERO, Duration.ZERO), List.of(0, 0)));
    }
}
