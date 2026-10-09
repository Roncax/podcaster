package org.roncax.podcaster.publishing;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.domain.*;
import org.roncax.podcaster.support.TestData;
import org.roncax.podcaster.support.WireMockResource;

@QuarkusTest
@WithTestResource(WireMockResource.class)
@WithTestResource(org.roncax.podcaster.support.PostgresResource.class)
class FeedChaptersTest {

    @BeforeEach
    void clean() { TestData.cleanDb(); }

    private Episode episode(Show show, List<Chapter> chapters, Instant publishedAt) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Run run = new Run();
            run.showId = show.id; run.trigger = RunTrigger.MANUAL; run.status = RunStatus.DONE; run.stage = RunStage.PUBLISH;
            run.since = Instant.now();
            run.persist();
            Episode e = new Episode();
            e.runId = run.id; e.showId = show.id; e.title = "Episodio"; e.audioPath = show.slug + "/" + run.id + ".mp3";
            e.sizeBytes = 1000L; e.durationSeconds = 240.0; e.publishedAt = publishedAt; e.chapters = chapters;
            e.persist();
            return e;
        });
    }

    private static String chaptersPath(Show show, Episode e) {
        return "/feeds/" + show.feedToken + "/" + show.slug + "/chapters/" + e.id + ".json";
    }

    @Test
    void feedAdvertisesChaptersOnlyForEpisodesWithTimedChapters() {
        Show show = TestData.show("chap");
        Episode with = episode(show, List.of(new Chapter("Intro", 0.0, List.of()), new Chapter("Outro", 200.0, List.of())), Instant.now());
        Episode without = episode(show, null, Instant.now().minusSeconds(3600));

        String feed = given().get(show.feedPath()).then().statusCode(200).extract().asString();

        assertTrue(feed.contains("xmlns:podcast=\"https://podcastindex.org/namespace/1.0\""), feed);
        assertTrue(feed.contains(chaptersPath(show, with) + "\" type=\"application/json+chapters\""), feed);
        assertFalse(feed.contains("/chapters/" + without.id + ".json"), feed);
        assertEquals(1, feed.split("<podcast:chapters ", -1).length - 1, feed);
    }

    @Test
    void chaptersJsonHasTimesTitlesAndSafeSourceLinks() {
        Show show = TestData.show("chapjson");
        Source src = TestData.source(show.id, "http://feed");
        Item good = TestData.item(show.id, src.id, "https://news.example/story", Instant.now());
        Item evil = TestData.item(show.id, src.id, "https://news.example/evil", Instant.now());
        QuarkusTransaction.requiringNew().run(() -> Item.<Item>findById(evil.id).url = "javascript:alert(1)");
        Episode e = episode(show, List.of(
                new Chapter("Intro", 0.0, List.of()),
                new Chapter("Storia buona", 31.4, List.of(good.id)),
                new Chapter("Storia cattiva", 120.0, List.of(evil.id)),
                new Chapter("Outro", 221.0, List.of())), Instant.now());

        String body = given().get(chaptersPath(show, e)).then().statusCode(200)
                .contentType(startsWith("application/json+chapters")).extract().asString();
        JsonPath json = new JsonPath(body);

        assertEquals("1.2.0", json.getString("version"));
        assertEquals(List.of("Intro", "Storia buona", "Storia cattiva", "Outro"), json.getList("chapters.title"));
        assertEquals(31.4f, json.getFloat("chapters[1].startTime"), 0.001);
        assertEquals("https://news.example/story", json.getString("chapters[1].url"));
        assertNull(json.get("chapters[0].url"));
        assertNull(json.get("chapters[2].url"), "non-http source links are never published");
    }

    @Test
    void chaptersJsonIsAsPrivateAsTheFeed() {
        Show show = TestData.show("chappriv");
        Show other = TestData.show("chapother");
        List<Chapter> chapters = List.of(new Chapter("Intro", 0.0, List.of()));
        Episode published = episode(show, chapters, Instant.now());
        Episode othersEpisode = episode(other, chapters, Instant.now());
        Episode unpublished = episode(show, chapters, null);
        Episode noChapters = episode(show, null, Instant.now());

        given().get("/feeds/" + "0".repeat(32) + "/" + show.slug + "/chapters/" + published.id + ".json").then().statusCode(404);
        given().get("/feeds/" + show.feedToken + "/" + other.slug + "/chapters/" + published.id + ".json").then().statusCode(404);
        given().get(chaptersPath(show, othersEpisode)).then().statusCode(404);
        given().get(chaptersPath(show, unpublished)).then().statusCode(404);
        given().get(chaptersPath(show, noChapters)).then().statusCode(404);
        given().get(chaptersPath(show, published)).then().statusCode(200).body(containsString("Intro"));
    }
}
