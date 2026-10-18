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
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.domain.*;
import org.roncax.podcaster.support.*;

@QuarkusTest
@WithTestResource(WireMockResource.class)
@WithTestResource(org.roncax.podcaster.support.PostgresResource.class)
class SinglePromptApiTest {
    static final String RANK_V2 = "MARKER-RANK-V2 for \"{showName}\".\n{contract}\n\nITEMS:\n{items}\n";

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

    /** Show with two unused DB items whose dry-run outline has two stories ("Story A", "Story B"). */
    Show showWithTwoStoryDryRun(String slug) {
        Show show = TestData.show(slug);
        Source source = TestData.source(show.id, "http://unused/" + slug);
        Item a = TestData.item(show.id, source.id, "http://story/" + slug + "/a", Instant.now());
        Item b = TestData.item(show.id, source.id, "http://story/" + slug + "/b", Instant.now());
        model.responder(prompt -> prompt.startsWith("TASK: RANK")
                ? "{\"clusters\":[{\"headline\":\"Story A\",\"itemIds\":[" + a.id + "],\"importance\":9},"
                  + "{\"headline\":\"Story B\",\"itemIds\":[" + b.id + "],\"importance\":5}]}"
                : FakeResponses.pipeline(prompt));
        api().body(Map.of("mode", "draft", "items", "database")).post("/api/shows/" + show.id + "/test/prompts").then().statusCode(202);
        assertEquals("DONE", await(show.id, "PROMPTS").getString("steps.PROMPTS.status"));
        model.requests.clear();
        return show;
    }

    @Test
    void singleRankUsesDraftAndMakesOneCall() {
        Show show = showWithFeed("one-rank");
        api().post("/api/shows/" + show.id + "/test/sources").then().statusCode(202);
        await(show.id, "SOURCES");
        api().body(Map.of("body", RANK_V2)).post("/api/shows/" + show.id + "/prompts/rank/versions").then().statusCode(201);

        api().body(Map.of("mode", "draft", "items", "session")).post("/api/shows/" + show.id + "/test/prompt/rank").then().statusCode(202);
        JsonPath json = await(show.id, "PROMPT_ONE");

        assertEquals("DONE", json.getString("steps.PROMPT_ONE.status"), json.getString("steps.PROMPT_ONE.error"));
        assertEquals(1, model.requests.size());
        assertTrue(model.userMessage(0).startsWith("TASK: RANK\nMARKER-RANK-V2"), model.userMessage(0));
        assertTrue(model.userMessage(0).contains("[id=1]"));
        assertEquals("rank", json.getString("singlePrompt.key"));
        assertEquals(2, json.getInt("singlePrompt.version"));
        assertEquals("draft", json.getString("singlePrompt.mode"));
        assertEquals(model.userMessage(0), json.getString("singlePrompt.prompt"));
        assertTrue(json.getString("singlePrompt.reply").contains("clusters"));
        assertTrue(json.getString("singlePrompt.parsed").contains("\"clusters\""));
        assertNull(json.getString("singlePrompt.parseError"));
        assertEquals(0L, (long) QuarkusTransaction.requiringNew().call(() -> Item.count()));
    }

    @Test
    void singleRankInProductionModeUsesProductionText() {
        Show show = showWithTwoStoryDryRun("one-prod");
        api().body(Map.of("body", RANK_V2)).post("/api/shows/" + show.id + "/prompts/rank/versions").then().statusCode(201);
        api().body(Map.of("mode", "production", "items", "database")).post("/api/shows/" + show.id + "/test/prompt/rank").then().statusCode(202);
        JsonPath json = await(show.id, "PROMPT_ONE");
        assertEquals(1, json.getInt("singlePrompt.version"));
        assertFalse(model.userMessage(0).contains("MARKER-RANK-V2"));
    }

    @Test
    void singleSegmentUsesChosenStoryAndPreviousTail() {
        Show show = showWithTwoStoryDryRun("one-seg");

        api().body(Map.of("mode", "draft", "story", 1)).post("/api/shows/" + show.id + "/test/prompt/segment").then().statusCode(202);
        JsonPath json = await(show.id, "PROMPT_ONE");

        assertEquals("DONE", json.getString("steps.PROMPT_ONE.status"), json.getString("steps.PROMPT_ONE.error"));
        assertEquals(1, model.requests.size());
        String prompt = model.userMessage(0);
        assertTrue(prompt.startsWith("TASK: SEGMENT"), prompt);
        assertTrue(prompt.contains("Story B"), prompt);
        assertFalse(prompt.contains("Story A"), prompt);
        assertTrue(prompt.contains("It has two sentences."), "previous segment's tail is passed: " + prompt);
        assertEquals("This is the segment text. It has two sentences.", json.getString("singlePrompt.reply"));
        assertNull(json.getString("singlePrompt.parsed"));
        assertEquals(1, json.getInt("singlePrompt.story"));
    }

    @Test
    void singleFramingUsesOutlineHeadlines() {
        Show show = showWithTwoStoryDryRun("one-frame");
        api().body(Map.of("mode", "draft")).post("/api/shows/" + show.id + "/test/prompt/framing").then().statusCode(202);
        JsonPath json = await(show.id, "PROMPT_ONE");
        assertEquals(1, model.requests.size());
        assertTrue(model.userMessage(0).startsWith("TASK: FRAMING"));
        assertTrue(model.userMessage(0).contains("1. Story A\n2. Story B"), model.userMessage(0));
        assertTrue(json.getString("singlePrompt.parsed").contains("\"title\""));
    }

    @Test
    void invalidJsonIsShownNotRepaired() {
        Show show = showWithTwoStoryDryRun("one-bad");
        model.responder(prompt -> "not json at all");
        api().body(Map.of("mode", "draft", "items", "database")).post("/api/shows/" + show.id + "/test/prompt/rank").then().statusCode(202);
        JsonPath json = await(show.id, "PROMPT_ONE");
        assertEquals("DONE", json.getString("steps.PROMPT_ONE.status"));
        assertEquals(1, model.requests.size());
        assertEquals("not json at all", json.getString("singlePrompt.reply"));
        assertNull(json.getString("singlePrompt.parsed"));
        assertNotNull(json.getString("singlePrompt.parseError"));
    }

    @Test
    void missingInputsAndBadRequests() {
        Show show = TestData.show("one-none");
        String base = "/api/shows/" + show.id + "/test/prompt/";
        api().body(Map.of("mode", "draft", "items", "session")).post(base + "rank").then().statusCode(409)
                .body("error", containsString("Fetch sources first"));
        api().body(Map.of("mode", "draft")).post(base + "segment").then().statusCode(409)
                .body("error", containsString("Run the prompt dry-run first"));
        api().body(Map.of("mode", "draft")).post(base + "framing").then().statusCode(409)
                .body("error", containsString("Run the prompt dry-run first"));
        api().body(Map.of("mode", "draft")).post(base + "json_repair").then().statusCode(400);
        api().body(Map.of("mode", "draft")).post(base + "nope").then().statusCode(400);
        api().body(Map.of("mode", "staging")).post(base + "rank").then().statusCode(400);
        api().body(Map.of("mode", "draft")).post("/api/shows/999999/test/prompt/rank").then().statusCode(404);
        assertTrue(model.requests.isEmpty());
    }

    @Test
    void storyOutOfRangeIs400() {
        Show show = showWithTwoStoryDryRun("one-range");
        api().body(Map.of("mode", "draft", "story", 2)).post("/api/shows/" + show.id + "/test/prompt/segment").then().statusCode(400)
                .body("details[0]", containsString("story"));
        assertTrue(model.requests.isEmpty());
    }

    @Test
    void newFetchClearsSinglePromptResult() {
        Show show = showWithFeed("one-clear");
        api().post("/api/shows/" + show.id + "/test/sources").then().statusCode(202);
        await(show.id, "SOURCES");
        api().body(Map.of("mode", "draft", "items", "session")).post("/api/shows/" + show.id + "/test/prompt/rank").then().statusCode(202);
        assertNotNull(await(show.id, "PROMPT_ONE").getString("singlePrompt.key"));
        api().post("/api/shows/" + show.id + "/test/sources").then().statusCode(202);
        JsonPath json = await(show.id, "SOURCES");
        assertNull(json.getString("singlePrompt"));
        assertEquals("IDLE", json.getString("steps.PROMPT_ONE.status"));
    }
}
