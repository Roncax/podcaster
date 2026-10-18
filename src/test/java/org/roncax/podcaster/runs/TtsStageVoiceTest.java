package org.roncax.podcaster.runs;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.config.PodcasterConfig;
import org.roncax.podcaster.domain.*;
import org.roncax.podcaster.support.*;

@QuarkusTest
@WithTestResource(WireMockResource.class)
@WithTestResource(org.roncax.podcaster.support.PostgresResource.class)
class TtsStageVoiceTest {
    /** Nine ~100-character sentences: 2 chunks at Piper's 500 characters, 3+ at Sibilia's 300. */
    static final String TEXT = String.join(" ", java.util.Collections.nCopies(9,
            "Questa è una frase di prova abbastanza lunga da occupare circa cento caratteri nel testo finale."));

    @InjectWireMock WireMockServer wm;
    @Inject TtsStage stage;
    @Inject PodcasterConfig config;

    private final List<Long> runIds = new java.util.ArrayList<>();

    @BeforeEach
    void setup() {
        TestData.cleanDb();
        WireMockResource.installDefaults(wm);
    }

    /**
     * Run ids restart with every test JVM while the work dir survives, so a run may meet chunks another test JVM left
     * under the same id: start each run from an empty dir and never leave Sibilia chunks behind.
     */
    @org.junit.jupiter.api.AfterEach
    void removeWorkDirs() throws java.io.IOException {
        for (long id : runIds) deleteWorkDir(id);
    }

    private void deleteWorkDir(long runId) throws java.io.IOException {
        Path dir = TtsStage.workDir(config, runId);
        if (!Files.exists(dir)) return;
        try (var files = Files.walk(dir)) {
            for (Path p : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }

    private Run run(String voiceId) throws java.io.IOException {
        Run run = persistRun(voiceId);
        deleteWorkDir(run.id);
        return run;
    }

    private Run persistRun(String voiceId) {
        Show show = TestData.show("voice" + System.nanoTime());
        return QuarkusTransaction.requiringNew().call(() -> {
            Show s = Show.findById(show.id);
            s.voiceId = voiceId;
            Run r = new Run();
            r.showId = show.id; r.trigger = RunTrigger.MANUAL; r.status = RunStatus.RUNNING; r.stage = RunStage.TTS;
            r.since = Instant.now();
            r.persist();
            Episode e = new Episode();
            e.runId = r.id; e.showId = show.id; e.title = "Episodio"; e.scriptParts = List.of(TEXT);
            e.persist();
            runIds.add(r.id);
            return r;
        });
    }

    private static int sibiliaCalls(WireMockServer wm) {
        return wm.findAll(postRequestedFor(urlEqualTo("/sibilia/synthesize"))).size();
    }

    @Test
    void sibiliaVoiceUsesItsOwnChunkSize() throws Exception {
        Run run = run("sibilia");
        stage.execute(run);
        assertTrue(sibiliaCalls(wm) >= 3, "expected 300-char chunks, got " + sibiliaCalls(wm));
        wm.verify(0, postRequestedFor(urlEqualTo("/synthesize")));
        assertEquals("sibilia|300", Files.readString(TtsStage.workDir(config, run.id).resolve("voice.txt")));
    }

    @Test
    void piperVoiceKeepsGlobalChunkSize() throws Exception {
        stage.execute(run("it_IT-paola-medium"));
        wm.verify(2, postRequestedFor(urlEqualTo("/synthesize")));
        assertEquals(0, sibiliaCalls(wm));
    }

    @Test
    void voiceChangeDiscardsOldChunks() throws Exception {
        Run run = run("sibilia");
        Path dir = TtsStage.workDir(config, run.id);
        Files.createDirectories(dir);
        Files.write(dir.resolve("chunk-0000.wav"), TestAudio.sineWav(0.5)); // 22.05 kHz, made by Piper
        Files.writeString(dir.resolve("voice.txt"), "it_IT-paola-medium|500");

        stage.execute(run); // would fail with a format mismatch if the Piper chunk were reused

        int chunks = sibiliaCalls(wm);
        assertTrue(chunks >= 3, "every chunk re-synthesized with Sibilia, got " + chunks);
        assertEquals("sibilia|300", Files.readString(dir.resolve("voice.txt")));
    }

    @Test
    void sameVoiceResumes() throws Exception {
        Run run = run("sibilia");
        Path dir = TtsStage.workDir(config, run.id);
        Files.createDirectories(dir);
        Files.write(dir.resolve("chunk-0000.wav"), TestAudio.sineWav(0.5, 24000));
        Files.writeString(dir.resolve("voice.txt"), "sibilia|300");

        stage.execute(run);
        int resumed = sibiliaCalls(wm);

        wm.resetRequests();
        stage.execute(run("sibilia"));
        int fresh = sibiliaCalls(wm);

        assertEquals(fresh - 1, resumed, "chunk 0 was reused");
    }
}
