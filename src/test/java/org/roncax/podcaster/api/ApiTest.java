package org.roncax.podcaster.api;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.runs.ShowScheduler;
import org.roncax.podcaster.support.*;

@QuarkusTest
@WithTestResource(WireMockResource.class)
@WithTestResource(org.roncax.podcaster.support.PostgresResource.class)
class ApiTest {
    @InjectWireMock WireMockServer wm;
    @Inject ShowScheduler scheduler;

    @BeforeEach
    void setup() {
        TestData.cleanDb();
        WireMockResource.installDefaults(wm);
        FakeChatModelRegistry.install(new FakeChatModel().responder(FakeResponses::pipeline));
    }

    private RequestSpecification api() {
        return given().header("X-API-Key", "test-key").contentType(ContentType.JSON);
    }

    private Map<String, Object> showJson(String slug) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", "Show " + slug);
        m.put("slug", slug);
        m.put("language", "it");
        m.put("voiceId", "it_IT-paola-medium");
        m.put("writerModel", "fake");
        m.put("minItems", 1);
        return m;
    }

    private long createShow(String slug) {
        return api().body(showJson(slug)).post("/api/shows").then().statusCode(201).extract().jsonPath().getLong("id");
    }

    private String rss(String... paths) {
        String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.now().atOffset(ZoneOffset.UTC));
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>t</title><link>http://x</link><description>d</description>");
        for (String p : paths) {
            sb.append("<item><title>Story ").append(p).append("</title><link>").append(wm.baseUrl()).append(p)
              .append("</link><pubDate>").append(date).append("</pubDate></item>");
        }
        return sb.append("</channel></rss>").toString();
    }

    @Test
    void requiresApiKey() {
        given().get("/api/shows").then().statusCode(401);
        given().header("X-API-Key", "wrong").get("/api/shows").then().statusCode(401);
        api().get("/api/shows").then().statusCode(200);
        given().get("/q/health").then().statusCode(200);
    }

    @Test
    void createsShowWithSourcesAndSchedule() {
        Map<String, Object> json = showJson("daily");
        json.put("cron", "0 7 * * *");
        long id = api().body(json).post("/api/shows").then().statusCode(201)
                .body("slug", is("daily")).body("targetDurationMinutes", is(20)).body("persistent", nullValue())
                .extract().jsonPath().getLong("id");

        assertTrue(scheduler.scheduledJobIds().contains("show-" + id));
        api().body(Map.of("connectorType", "rss", "config", Map.of("url", "https://www.ansa.it/sito/ansait_rss.xml")))
                .post("/api/shows/" + id + "/sources").then().statusCode(201).body("fetchFullText", is(true));
        api().get("/api/shows/" + id + "/sources").then().statusCode(200).body("size()", is(1));
        api().get("/api/shows/" + id).then().statusCode(200).body("name", is("Show daily"));
    }

    @Test
    void rejectsSemanticallyInvalidShow() {
        Map<String, Object> json = showJson("bad");
        json.put("writerModel", "gpt");
        json.put("cron", "every morning");
        json.put("voiceId", "xx_XX-nobody-low");
        api().body(json).post("/api/shows").then().statusCode(400)
                .body("details", hasItems(containsString("writerModel 'gpt'"), containsString("Invalid cron"), containsString("voiceId 'xx_XX-nobody-low'")));
    }

    @Test
    void rejectsBeanValidationErrors() {
        Map<String, Object> json = showJson("Bad Slug");
        api().body(json).post("/api/shows").then().statusCode(400);
    }

    @Test
    void rejectsDuplicateSlug() {
        createShow("dup");
        api().body(showJson("dup")).post("/api/shows").then().statusCode(400)
                .body("details", hasItem(containsString("already used")));
    }

    @Test
    void rejectsUnknownConnector() {
        long id = createShow("conn");
        api().body(Map.of("connectorType", "site:nope", "config", Map.of()))
                .post("/api/shows/" + id + "/sources").then().statusCode(400)
                .body("details", hasItem(containsString("Unknown connectorType")));
        api().body(Map.of("connectorType", "rss", "config", Map.of()))
                .post("/api/shows/" + id + "/sources").then().statusCode(400)
                .body("details", hasItem(containsString("config.url")));
    }

    @Test
    void triggersRunAndRejectsRetryOfNonFailedRun() {
        long id = createShow("trig");
        wm.stubFor(get("/tfeed").willReturn(okXml(rss())));
        api().body(Map.of("connectorType", "rss", "config", Map.of("url", wm.baseUrl() + "/tfeed")))
                .post("/api/shows/" + id + "/sources").then().statusCode(201);

        long runId = api().post("/api/shows/" + id + "/runs").then().statusCode(202).extract().jsonPath().getLong("runId");
        TestData.awaitRun(runId);

        api().get("/api/runs?showId=" + id).then().statusCode(200).body("[0].status", is("SKIPPED"));
        api().post("/api/runs/" + runId + "/retry").then().statusCode(409);
    }

    @Test
    void testsSourceWithoutPersisting() {
        long id = createShow("srctest");
        wm.stubFor(get("/sfeed").willReturn(okXml(rss("/s1", "/s2"))));
        wm.stubFor(get("/s1").willReturn(aResponse().withStatus(200).withBody(Fixtures.bytes("ilpost-article.html"))));
        long sourceId = api().body(Map.of("connectorType", "rss", "config", Map.of("url", wm.baseUrl() + "/sfeed")))
                .post("/api/shows/" + id + "/sources").then().statusCode(201).extract().jsonPath().getLong("id");

        api().post("/api/sources/" + sourceId + "/test").then().statusCode(200)
                .body("items.size()", is(2))
                .body("sampleText", containsString("sciopero"))
                .body("error", nullValue());
        assertEquals(0L, (long) io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
                .call(() -> org.roncax.podcaster.domain.Item.count()));
    }

    @Test
    void metaEndpoints() {
        api().get("/api/meta/models").then().statusCode(200).body("$", hasItem("fake"));
        api().get("/api/meta/connectors").then().statusCode(200).body("$", hasItem("rss"));
        api().get("/api/meta/voices").then().statusCode(200).body("$", hasItem("it_IT-paola-medium"));
    }

    @Test
    void deletingShowRemovesItAndItsSchedule() {
        Map<String, Object> json = showJson("gone");
        json.put("cron", "0 7 * * *");
        long id = api().body(json).post("/api/shows").then().statusCode(201).extract().jsonPath().getLong("id");
        api().delete("/api/shows/" + id).then().statusCode(204);
        api().get("/api/shows/" + id).then().statusCode(404);
        assertFalse(scheduler.scheduledJobIds().contains("show-" + id));
    }

    @Test
    void unknownEpisodeAudioIs404() {
        api().get("/api/episodes/999999/audio").then().statusCode(404);
    }
}
