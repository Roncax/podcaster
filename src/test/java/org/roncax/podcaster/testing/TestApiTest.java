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
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.domain.*;
import org.roncax.podcaster.support.*;

@QuarkusTest
@WithTestResource(WireMockResource.class)
@WithTestResource(org.roncax.podcaster.support.PostgresResource.class)
class TestApiTest {
    @InjectWireMock WireMockServer wm;
    FakeChatModel model;

    @BeforeEach
    void setup() {
        TestData.cleanDb();
        WireMockResource.installDefaults(wm);
        model = new FakeChatModel().responder(FakeResponses::pipeline);
        FakeChatModelRegistry.install(model);
    }

    RequestSpecification api() {
        return given().header("X-API-Key", "test-api-key-0123456789").contentType(ContentType.JSON);
    }

    /** Polls the session until {@code step} is no longer RUNNING. */
    JsonPath await(long showId, String step) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            JsonPath json = api().get("/api/shows/" + showId + "/test").then().statusCode(200).extract().jsonPath();
            if (!"RUNNING".equals(json.getString("steps." + step + ".status"))) return json;
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        throw new AssertionError(step + " still running after 30s");
    }

    String rss(String... items) {
        String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.now().atOffset(ZoneOffset.UTC));
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>t</title><link>http://x</link><description>d</description>");
        for (String it : items) {
            String[] p = it.split("\\|");
            sb.append("<item><title>").append(p[0]).append("</title><link>").append(wm.baseUrl()).append(p[1])
              .append("</link><pubDate>").append(date).append("</pubDate><description>Teaser</description></item>");
        }
        return sb.append("</channel></rss>").toString();
    }

    Show showWithFeed(String slug) {
        Show show = TestData.show(slug);
        TestData.source(show.id, wm.baseUrl() + "/" + slug + "/feed");
        wm.stubFor(get("/" + slug + "/feed").willReturn(okXml(rss("First story|/" + slug + "/a1", "Second story|/" + slug + "/a2"))));
        wm.stubFor(get(urlPathMatching("/" + slug + "/a.")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/html").withBody(Fixtures.bytes("ilpost-article.html"))));
        return show;
    }

    @Test
    void emptySessionIsIdle() {
        Show show = TestData.show("idle");
        api().get("/api/shows/" + show.id + "/test").then().statusCode(200)
                .body("steps.SOURCES.status", is("IDLE")).body("items", empty());
        api().get("/api/shows/999999/test").then().statusCode(404);
    }

    @Test
    void sourceStepFetchesWithoutPersisting() {
        Show show = showWithFeed("src");
        Source broken = TestData.source(show.id, wm.baseUrl() + "/src/missing");

        api().post("/api/shows/" + show.id + "/test/sources").then().statusCode(202);
        JsonPath json = await(show.id, "SOURCES");

        assertEquals("DONE", json.getString("steps.SOURCES.status"));
        assertEquals(2, json.getList("items").size());
        assertEquals(1, json.getInt("items[0].id"));
        assertTrue(json.getBoolean("items[0].hasFullText"));
        assertEquals(2, json.getInt("sources[0].items")); // sources are in id order; the working feed is first
        assertNotNull(json.getString("sources.find { it.sourceId == " + broken.id + " }.error"));
        assertEquals(0L, (long) QuarkusTransaction.requiringNew().call(() -> Item.count()));
        QuarkusTransaction.requiringNew().run(() -> {
            for (Source s : Source.<Source>list("showId", show.id)) {
                assertNull(s.lastFetchedAt);
                assertNull(s.lastError);
            }
        });
    }

    @Test
    void concurrentStepIs409() {
        Show show = showWithFeed("busy");
        wm.stubFor(get("/busy/feed").willReturn(okXml(rss("Slow|/busy/a1")).withFixedDelay(1500)));
        api().post("/api/shows/" + show.id + "/test/sources").then().statusCode(202);
        api().post("/api/shows/" + show.id + "/test/sources").then().statusCode(409);
        await(show.id, "SOURCES");
    }

    @Test
    void promptDryRunOverFetchedItems() {
        Show show = showWithFeed("pdr");
        api().post("/api/shows/" + show.id + "/test/sources").then().statusCode(202);
        await(show.id, "SOURCES");

        api().body(java.util.Map.of("mode", "draft", "items", "session")).post("/api/shows/" + show.id + "/test/prompts").then().statusCode(202);
        JsonPath json = await(show.id, "PROMPTS");

        assertEquals("DONE", json.getString("steps.PROMPTS.status"), json.getString("steps.PROMPTS.error"));
        assertEquals(3, json.getList("dryRun.scriptParts").size());
        assertEquals("Test episode", json.getString("dryRun.title"));
        assertTrue(model.userMessage(0).contains("[id=1]"), model.userMessage(0));
        assertEquals(java.util.List.of(1, 2), json.getList("dryRun.selection.clusters[0].itemIds", Integer.class));
        assertEquals(0L, (long) QuarkusTransaction.requiringNew().call(() -> Episode.count()));
        assertEquals(0L, (long) QuarkusTransaction.requiringNew().call(() -> Item.count()));
        assertEquals(0L, (long) QuarkusTransaction.requiringNew().call(() -> VoiceCalibration.count()));
    }

    @Test
    void promptDryRunUsesDraftOrProductionFromDatabase() {
        Show show = TestData.show("pdb");
        Source source = TestData.source(show.id, "http://unused/feed");
        Item item = TestData.item(show.id, source.id, "http://story/1", Instant.now());
        String rankV2 = "MARKER-RANK-V2 for \"{showName}\".\n{contract}\n\nITEMS:\n{items}\n";
        api().body(java.util.Map.of("body", rankV2)).post("/api/shows/" + show.id + "/prompts/rank/versions").then().statusCode(201);

        api().body(java.util.Map.of("mode", "production", "items", "database")).post("/api/shows/" + show.id + "/test/prompts").then().statusCode(202);
        assertEquals(1, await(show.id, "PROMPTS").getInt("dryRun.promptVersions.rank"));
        assertFalse(model.userMessage(0).contains("MARKER-RANK-V2"));

        api().body(java.util.Map.of("mode", "draft", "items", "database")).post("/api/shows/" + show.id + "/test/prompts").then().statusCode(202);
        assertEquals(2, await(show.id, "PROMPTS").getInt("dryRun.promptVersions.rank"));
        assertTrue(model.requests.stream().anyMatch(r -> FakeChatModel.lastUser(r).contains("MARKER-RANK-V2")));
        assertNull(QuarkusTransaction.requiringNew().call(() -> Item.<Item>findById(item.id).usedInEpisodeId));
    }

    @Test
    void promptDryRunWithoutCandidatesIs409() {
        Show show = TestData.show("pnone");
        api().body(java.util.Map.of("mode", "draft", "items", "session")).post("/api/shows/" + show.id + "/test/prompts").then()
                .statusCode(409).body("error", containsString("Fetch sources first"));
        api().body(java.util.Map.of("mode", "draft", "items", "database")).post("/api/shows/" + show.id + "/test/prompts").then()
                .statusCode(409).body("error", containsString("No unused items"));
        api().body(java.util.Map.of("mode", "staging", "items", "database")).post("/api/shows/" + show.id + "/test/prompts").then()
                .statusCode(400);
        assertTrue(model.requests.isEmpty());
    }

    @Test
    void promptDryRunFailureIsReportedOnTheStep() {
        Show show = TestData.show("pfail");
        TestData.item(show.id, TestData.source(show.id, "http://unused/feed").id, "http://story/broken", Instant.now());
        model.responder(prompt -> "this is not json");
        api().body(java.util.Map.of("mode", "draft", "items", "database")).post("/api/shows/" + show.id + "/test/prompts").then().statusCode(202);
        JsonPath json = await(show.id, "PROMPTS");
        assertEquals("FAILED", json.getString("steps.PROMPTS.status"));
        assertTrue(json.getString("steps.PROMPTS.error").contains("did not return valid JSON"), json.getString("steps.PROMPTS.error"));
    }
}
