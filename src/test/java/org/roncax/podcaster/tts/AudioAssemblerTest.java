package org.roncax.podcaster.tts;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roncax.podcaster.support.TestAudio;

class AudioAssemblerTest {
    AudioAssembler assembler = new AudioAssembler("ffmpeg", "64k");

    @Test
    void concatenatesWithPausesAndEncodesMp3(@TempDir Path dir) throws Exception {
        Path a = Files.write(dir.resolve("a.wav"), TestAudio.sineWav(1.0));
        Path b = Files.write(dir.resolve("b.wav"), TestAudio.sineWav(1.0));
        Path out = dir.resolve("episode.mp3");

        AssembledAudio audio = assembler.assemble(List.of(a, b),
                List.of(Duration.ofMillis(500), Duration.ofMillis(500)), out,
                new Mp3Tags("Title", "Podcaster", "Daily", "2026-10-06"));

        assertEquals(3.0, audio.durationSeconds(), 0.01);
        assertTrue(Files.exists(out));
        assertEquals(Files.size(out), audio.sizeBytes());
        byte[] head = Files.readAllBytes(out);
        assertEquals("ID3", new String(head, 0, 3));
        assertFalse(Files.exists(dir.resolve("episode.mp3.wav")), "temporary WAV is removed");
    }

    @Test
    void rejectsMixedFormats(@TempDir Path dir) throws Exception {
        Path a = Files.write(dir.resolve("a.wav"), TestAudio.sineWav(0.2));
        Path b = Files.write(dir.resolve("b.wav"), TestAudio.wav(new byte[3200], 16000, 1, 16));
        assertThrows(IllegalStateException.class, () -> assembler.assemble(List.of(a, b),
                List.of(Duration.ZERO, Duration.ZERO), dir.resolve("x.mp3"), new Mp3Tags("t", "a", "b", "d")));
    }
}
