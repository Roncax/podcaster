package org.roncax.podcaster.ingestion.reddit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.domain.Item;
import org.roncax.podcaster.domain.Show;
import org.roncax.podcaster.domain.Source;
import org.roncax.podcaster.ingestion.IngestionService;
import org.roncax.podcaster.support.*;

@QuarkusTest
@WithTestResource(WireMockResource.class)
@WithTestResource(org.roncax.podcaster.support.PostgresResource.class)
class RedditIngestionTest {
    @InjectWireMock WireMockServer wm;
    @Inject IngestionService ingestion;

    @BeforeEach
    void setup() {
        TestData.cleanDb();
        WireMockResource.installDefaults(wm);
        FakeChatModelRegistry.install(new FakeChatModel().responder(FakeResponses::pipeline));
    }

    private Source redditSource(long showId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Source s = new Source();
            s.showId = showId;
            s.connectorType = "reddit";
            s.config = new java.util.HashMap<>(Map.of("subreddit", "italy", "topComments", "2"));
            s.persist();
            return s;
        });
    }

    @Test
    void ingestsRedditAndDedupesAgainstRss() {
        Show show = TestData.show("reddit");
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String article = wm.baseUrl() + "/article-shared";
        wm.stubFor(get("/article-shared").willReturn(aResponse().withStatus(200).withBody(Fixtures.bytes("ilpost-article.html"))));
        wm.stubFor(get(urlPathEqualTo("/r/italy/top/.rss")).willReturn(okXml(RedditFeeds.listing(
                RedditFeeds.entry("p1", "Sciopero ATM", article, null, now),
                RedditFeeds.entry("p2", "Discussione", RedditFeeds.thread("p2"), "Opinione ".repeat(40), now),
                RedditFeeds.entry("p3", "Meme", "https://i.redd.it/meme.jpg", null, now)))));
        wm.stubFor(get(urlPathEqualTo("/comments/p1/.rss")).willReturn(okXml(RedditFeeds.comments("p1", "<p>Che disastro</p>", "<p>Ci risiamo</p>"))));
        wm.stubFor(get(urlPathEqualTo("/comments/p2/.rss")).willReturn(aResponse().withStatus(429)));
        String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(now.atOffset(ZoneOffset.UTC));
        wm.stubFor(get("/rssfeed").willReturn(okXml("<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>t</title><link>http://x</link><description>d</description>"
                + "<item><title>Sciopero ATM</title><link>" + article + "</link><pubDate>" + date + "</pubDate></item></channel></rss>")));
        redditSource(show.id);
        TestData.source(show.id, wm.baseUrl() + "/rssfeed");

        var report = ingestion.ingest(show.id, now.minus(1, ChronoUnit.HOURS));

        assertTrue(report.errors().isEmpty(), report.errors().toString());
        List<Item> items = QuarkusTransaction.requiringNew().call(() -> Item.<Item>list("showId", show.id));
        assertEquals(2, items.size(), "shared article stored once, meme skipped");
        Item shared = items.stream().filter(i -> i.url.equals(article)).findFirst().orElseThrow();
        assertEquals(RedditFeeds.thread("p1"), shared.discussionUrl);
        assertTrue(shared.fullText.contains("sciopero"));
        assertTrue(shared.fullText.endsWith("Reddit discussion (top comments):\n- Che disastro\n- Ci risiamo"), shared.fullText);
        Item discussion = items.stream().filter(i -> i.url.equals(RedditFeeds.thread("p2"))).findFirst().orElseThrow();
        assertFalse(discussion.fullText.contains("Reddit discussion"));
    }

    @Test
    void apiValidatesRedditConfigAndTestsSource() {
        Show show = TestData.show("reddit-api");
        var api = given().header("X-API-Key", "test-api-key-0123456789").contentType(ContentType.JSON);
        api.body(Map.of("connectorType", "reddit", "config", Map.of("subreddit", "r/italy")))
                .post("/api/shows/" + show.id + "/sources").then().statusCode(400)
                .body("details", hasItem(containsString("subreddit must be")));

        wm.stubFor(get(urlPathEqualTo("/r/italy/top/.rss")).willReturn(okXml(RedditFeeds.listing(
                RedditFeeds.entry("t1", "Discussione", RedditFeeds.thread("t1"), "Opinione ".repeat(40), Instant.now())))));
        wm.stubFor(get(urlPathEqualTo("/comments/t1/.rss")).willReturn(okXml(RedditFeeds.comments("t1", "<p>Bene</p>"))));
        long sourceId = given().header("X-API-Key", "test-api-key-0123456789").contentType(ContentType.JSON)
                .body(Map.of("connectorType", "reddit", "config", Map.of("subreddit", "italy")))
                .post("/api/shows/" + show.id + "/sources").then().statusCode(201).extract().jsonPath().getLong("id");

        given().header("X-API-Key", "test-api-key-0123456789").contentType(ContentType.JSON).post("/api/sources/" + sourceId + "/test").then().statusCode(200)
                .body("items.size()", is(1))
                .body("items[0].discussionUrl", is(RedditFeeds.thread("t1")))
                .body("sampleText", containsString("Reddit discussion (top comments):\n- Bene"));
    }
}
