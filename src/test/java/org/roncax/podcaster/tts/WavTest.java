package org.roncax.podcaster.tts;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.roncax.podcaster.support.TestAudio;

class WavTest {
    @Test
    void parsesPcmWav() {
        Wav wav = Wav.parse(TestAudio.sineWav(1.0));
        assertEquals(22050, wav.sampleRate());
        assertEquals(1, wav.channels());
        assertEquals(16, wav.bitsPerSample());
        assertEquals(44100, wav.pcm().length);
        assertEquals(44100, wav.bytesPerSecond());
    }

    @Test
    void headerRoundTrips() {
        byte[] pcm = new byte[400];
        byte[] header = Wav.header(16000, 1, 16, pcm.length);
        byte[] all = new byte[header.length + pcm.length];
        System.arraycopy(header, 0, all, 0, header.length);
        Wav wav = Wav.parse(all);
        assertEquals(16000, wav.sampleRate());
        assertEquals(400, wav.pcm().length);
    }

    @Test
    void detectsNonWav() {
        assertFalse(Wav.looksLikeWav("<html>error</html>".getBytes()));
        assertFalse(Wav.looksLikeWav(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> Wav.parse("<html>".getBytes()));
    }
}
