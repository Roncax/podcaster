package org.roncax.podcaster.runs;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.domain.*;
import org.roncax.podcaster.publishing.AudioStorage;
import org.roncax.podcaster.support.*;

@QuarkusTest
@WithTestResource(WireMockResource.class)
@WithTestResource(org.roncax.podcaster.support.PostgresResource.class)
class RunPipelineTest {
    @InjectWireMock WireMockServer wm;
    @Inject RunLauncher launcher;
    @Inject AudioStorage storage;
    FakeChatModel model;
    Show show;

    @BeforeEach
    void setup() {
        TestData.cleanDb();
        WireMockResource.installDefaults(wm);
        model = new FakeChatModel().responder(FakeResponses::pipeline);
        FakeChatModelRegistry.install(model);
        show = TestData.show("pipe");
        TestData.source(show.id, wm.baseUrl() + "/pfeed");
    }

    private void stubFeed(String... paths) {
        String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.now().atOffset(ZoneOffset.UTC));
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>t</title><link>http://x</link><description>d</description>");
        for (String p : paths) {
            sb.append("<item><title>Story ").append(p).append("</title><link>").append(wm.baseUrl()).append(p)
              .append("</link><pubDate>").append(date).append("</pubDate></item>");
            wm.stubFor(get(p).willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/html")
                    .withBody(Fixtures.bytes("ilpost-article.html"))));
        }
        wm.stubFor(get("/pfeed").willReturn(okXml(sb.append("</channel></rss>").toString())));
    }

    @Test
    void producesAndPublishesAnEpisode() {
        stubFeed("/p1", "/p2", "/p3");

        Run run = TestData.awaitRun(launcher.launch(show.id, RunTrigger.MANUAL));

        assertEquals(RunStatus.DONE, run.status, run.error);
        Episode episode = QuarkusTransaction.requiringNew().call(() -> Episode.findByRun(run.id).orElseThrow());
        assertEquals("Test episode", episode.title);
        assertEquals(3, episode.scriptParts.size()); // intro + 1 segment + outro
        assertEquals(List.of("Intro", "Top story", "Outro"), episode.chapters.stream().map(Chapter::title).toList());
        assertEquals(0.0, episode.chapters.get(0).startSeconds(), 0.001);
        assertTrue(episode.chapters.get(1).startSeconds() > 0);
        assertEquals(3, episode.chapters.get(1).itemIds().size());
        assertTrue(episode.durationSeconds > 0);
        assertTrue(storage.exists(episode.audioPath));
        assertEquals("pipe/" + episode.id + ".mp3", episode.audioPath);
        long unused = QuarkusTransaction.requiringNew().call(() -> Item.count("showId = ?1 and usedInEpisodeId is null", show.id));
        assertEquals(0, unused);
        String feed = given().get("/feeds/pipe.xml").then().statusCode(200).extract().asString();
        assertTrue(feed.contains("Test episode"));
        int calibrationSamples = QuarkusTransaction.requiringNew().call(() ->
                VoiceCalibration.<VoiceCalibration>find("voiceId", show.voiceId).firstResult().samples);
        assertEquals(1, calibrationSamples);

        Run second = TestData.awaitRun(launcher.launch(show.id, RunTrigger.MANUAL));
        assertEquals(RunStatus.SKIPPED, second.status, "no new items -> skipped");
    }

    @Test
    void retryResumesAtFailedStageWithoutNewLlmCalls() {
        stubFeed("/p1");
        wm.stubFor(post("/synthesize").willReturn(serviceUnavailable()));

        Run failed = TestData.awaitRun(launcher.launch(show.id, RunTrigger.MANUAL));

        assertEquals(RunStatus.FAILED, failed.status);
        assertEquals(RunStage.TTS, failed.stage);
        assertTrue(failed.error.startsWith("TTS: "), failed.error);
        wm.verify(postRequestedFor(urlEqualTo("/bottest-token/sendMessage"))
                .withRequestBody(matchingJsonPath("$.text", containing("TTS"))));
        int llmCalls = model.requests.size();

        WireMockResource.installDefaults(wm); // Piper is back; feed stubs are gone, so INGEST must not rerun
        launcher.retry(failed.id);
        Run resumed = TestData.awaitRun(failed.id);

        assertEquals(RunStatus.DONE, resumed.status, resumed.error);
        assertEquals(2, resumed.attempt);
        assertEquals(llmCalls, model.requests.size());
    }

    @Test
    void secondLaunchWhileRunningIsRejected() {
        stubFeed("/p1");
        wm.stubFor(post("/synthesize").willReturn(aResponse().withStatus(200).withFixedDelay(1500)
                .withBody(TestAudio.sineWav(0.2))));

        long first = launcher.launch(show.id, RunTrigger.MANUAL);
        assertThrows(RunAlreadyActiveException.class, () -> launcher.launch(show.id, RunTrigger.MANUAL));
        assertEquals(RunStatus.DONE, TestData.awaitRun(first).status);
    }

    @Test
    void emptyFeedSkips() {
        stubFeed();
        Run run = TestData.awaitRun(launcher.launch(show.id, RunTrigger.MANUAL));
        assertEquals(RunStatus.SKIPPED, run.status);
        assertTrue(model.requests.isEmpty());
    }

    @Test
    void onlyFailedRunsCanBeRetried() {
        stubFeed();
        long id = launcher.launch(show.id, RunTrigger.MANUAL);
        TestData.awaitRun(id);
        assertThrows(IllegalStateException.class, () -> launcher.retry(id));
    }

    @Test
    void errorThrownInStageStillFailsTheRun() {
        stubFeed("/p1");
        io.quarkus.test.junit.QuarkusMock.installMockForType(new org.roncax.podcaster.tts.AudioAssembler("ffmpeg", "64k") {
            @Override
            public org.roncax.podcaster.tts.AssembledAudio assemble(List<java.nio.file.Path> chunks, List<java.time.Duration> pauses,
                    List<Integer> parts, java.nio.file.Path out, org.roncax.podcaster.tts.Mp3Tags tags) {
                throw new OutOfMemoryError("simulated");
            }
        }, org.roncax.podcaster.tts.AudioAssembler.class);

        Run run = TestData.awaitRun(launcher.launch(show.id, RunTrigger.MANUAL));

        assertEquals(RunStatus.FAILED, run.status);
        assertTrue(run.error.contains("simulated"), run.error);
    }

    @Test
    void runReportsProgressAndClearsItWhenDone() {
        stubFeed("/p1", "/p2");
        java.util.List<String> seen = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        io.quarkus.test.junit.QuarkusMock.installMockForType(new RunProgress() {
            @Override
            public void update(long runId, String text) {
                if (text != null) seen.add(text);
                super.update(runId, text);
            }
        }, RunProgress.class);

        Run done = TestData.awaitRun(launcher.launch(show.id, RunTrigger.MANUAL));

        assertEquals(RunStatus.DONE, done.status, done.error);
        assertNull(done.progress, "progress is cleared when the run finishes");
        java.util.List<String> expectedInOrder = java.util.List.of("Fetched 1/1 sources, 2 new items", "Ranking 2 items",
                "Writing segment 1 of 1", "Writing intro and outro", "Encoding MP3", "Publishing");
        java.util.List<String> filtered = seen.stream().filter(expectedInOrder::contains).toList();
        assertEquals(expectedInOrder, filtered, seen.toString());
        assertTrue(seen.contains("Synthesizing chunk 3 of 3"), seen.toString());
    }
}
