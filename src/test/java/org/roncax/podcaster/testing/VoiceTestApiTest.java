package org.roncax.podcaster.testing;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.domain.*;
import org.roncax.podcaster.support.*;
import org.roncax.podcaster.tts.Wav;

@QuarkusTest
@WithTestResource(WireMockResource.class)
@WithTestResource(org.roncax.podcaster.support.PostgresResource.class)
class VoiceTestApiTest {
    @InjectWireMock WireMockServer wm;

    @BeforeEach
    void setup() {
        TestData.cleanDb();
        WireMockResource.installDefaults(wm);
    }

    RequestSpecification api() {
        return given().header("X-API-Key", "test-api-key-0123456789").contentType(ContentType.JSON);
    }

    JsonPath await(long showId, String step) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            JsonPath json = api().get("/api/shows/" + showId + "/test").then().statusCode(200).extract().jsonPath();
            if (!"RUNNING".equals(json.getString("steps." + step + ".status"))) return json;
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        throw new AssertionError(step + " still running after 30s");
    }

    @Test
    void synthesizesShortClipWithShowVoice() {
        Show show = TestData.show("voice");
        api().body(Map.of("text", "Buongiorno. Questa è una prova della voce.")).post("/api/shows/" + show.id + "/test/voice")
                .then().statusCode(202);
        JsonPath json = await(show.id, "VOICE");
        assertEquals("DONE", json.getString("steps.VOICE.status"), json.getString("steps.VOICE.error"));
        assertEquals("it_IT-paola-medium", json.getString("clip.voiceId"));
        assertTrue(json.getDouble("clip.durationSeconds") > 0.4);
        byte[] wav = api().get("/api/shows/" + show.id + "/test/voice.wav").then().statusCode(200)
                .contentType("audio/wav").extract().asByteArray();
        assertTrue(Wav.looksLikeWav(wav));
        wm.verify(postRequestedFor(urlEqualTo("/synthesize")).withRequestBody(containing("it_IT-paola-medium")));
        assertEquals(0L, (long) QuarkusTransaction.requiringNew().call(() -> VoiceCalibration.count()));
    }

    @Test
    void voiceOverrideIsUsed() {
        Show show = TestData.show("voice2");
        api().body(Map.of("text", "Hello there.", "voiceId", "en_US-lessac-medium", "lengthScale", 1.2))
                .post("/api/shows/" + show.id + "/test/voice").then().statusCode(202);
        assertEquals("en_US-lessac-medium", await(show.id, "VOICE").getString("clip.voiceId"));
        wm.verify(postRequestedFor(urlEqualTo("/synthesize")).withRequestBody(containing("en_US-lessac-medium")));
    }

    @Test
    void blankTextIs400() {
        Show show = TestData.show("vblank");
        api().body(Map.of("text", "   \n ")).post("/api/shows/" + show.id + "/test/voice").then().statusCode(400);
        wm.verify(0, postRequestedFor(urlEqualTo("/synthesize")));
    }

    @Test
    void validationErrors() {
        Show show = TestData.show("vbad");
        String base = "/api/shows/" + show.id + "/test/voice";
        api().body(Map.of("text", "a".repeat(1001))).post(base).then().statusCode(400)
                .body("details[0]", containsString("1000"));
        api().body(Map.of("part", 0)).post(base).then().statusCode(400)
                .body("details[0]", containsString("Run the prompt dry-run first"));
        api().body(Map.of("text", "Ciao.", "voiceId", "xx_nope")).post(base).then().statusCode(400);
        api().body(Map.of("text", "Ciao.", "lengthScale", 3.0)).post(base).then().statusCode(400);
        api().get("/api/shows/" + show.id + "/test/voice.wav").then().statusCode(404);
    }

    @Test
    void piperFailureMarksStepFailed() {
        Show show = TestData.show("vdown");
        wm.stubFor(post("/synthesize").willReturn(aResponse().withStatus(500).withBody("boom")));
        api().body(Map.of("text", "Ciao.")).post("/api/shows/" + show.id + "/test/voice").then().statusCode(202);
        assertEquals("FAILED", await(show.id, "VOICE").getString("steps.VOICE.status"));
    }
}
