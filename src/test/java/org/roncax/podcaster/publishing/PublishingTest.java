package org.roncax.podcaster.publishing;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.domain.*;
import org.roncax.podcaster.support.TestData;
import org.roncax.podcaster.support.WireMockResource;

@QuarkusTest
@WithTestResource(WireMockResource.class)
@WithTestResource(org.roncax.podcaster.support.PostgresResource.class)
class PublishingTest {
    @Inject AudioStorage storage;
    @Inject RetentionService retention;

    @BeforeEach
    void clean() { TestData.cleanDb(); }

    private Episode episode(Show show, String title, String audioPath, Instant publishedAt) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Run run = new Run();
            run.showId = show.id;
            run.trigger = RunTrigger.MANUAL;
            run.status = RunStatus.DONE;
            run.stage = RunStage.PUBLISH;
            run.since = Instant.now();
            run.persist();
            Episode e = new Episode();
            e.runId = run.id;
            e.showId = show.id;
            e.title = title;
            e.description = "Notes";
            e.audioPath = audioPath;
            e.sizeBytes = 1234L;
            e.durationSeconds = 61.4;
            e.publishedAt = publishedAt;
            e.persist();
            return e;
        });
    }

    private String storeBytes(String key, int size) throws Exception {
        Path tmp = Files.createTempFile("ep", ".mp3");
        Files.write(tmp, new byte[size]);
        storage.store(key, tmp);
        return key;
    }

    @Test
    void feedListsPublishedEpisodesOnly() {
        Show show = TestData.show("feed1");
        episode(show, "News & Views", "feed1/1.mp3", Instant.now());
        episode(show, "Draft", null, null);

        String xml = given().get(show.feedPath()).then().statusCode(200)
                .contentType(containsString("rss+xml")).extract().asString();

        assertTrue(xml.contains("News &amp; Views"));
        assertTrue(xml.contains("url=\"http://podcaster.test/media/" + show.feedToken + "/feed1/1.mp3\""));
        assertTrue(xml.contains("<link>http://podcaster.test" + show.feedPath() + "</link>"));
        assertTrue(xml.contains("length=\"1234\""));
        assertTrue(xml.contains("<itunes:duration>61</itunes:duration>"));
        assertEquals(1, xml.split("<item>", -1).length - 1);
        assertFalse(xml.contains("Draft"));
    }

    @Test
    void unknownFeedIs404() {
        given().get("/feeds/nope.xml").then().statusCode(404);
    }

    @Test
    void feedRequiresTheShowsToken() {
        Show show = TestData.show("feed2");
        Show other = TestData.show("feed3");
        given().get("/feeds/feed2.xml").then().statusCode(404);
        given().get("/feeds/0123456789abcdef0123456789abcdef/feed2.xml").then().statusCode(404);
        given().get("/feeds/" + other.feedToken + "/feed2.xml").then().statusCode(404);
        given().get("/feeds/" + show.feedToken + "/feed2.xml").then().statusCode(200);
    }

    @Test
    void showsGetDistinctUnguessableTokens() {
        Show a = TestData.show("tok1");
        Show b = TestData.show("tok2");
        assertTrue(a.feedToken.matches("[0-9a-f]{32}"), a.feedToken);
        assertNotEquals(a.feedToken, b.feedToken);
    }

    @Test
    void mediaIsServedWithRangeSupport() throws Exception {
        Show show = TestData.show("media1");
        storeBytes("media1/ep.mp3", 100);
        String url = "/media/" + show.feedToken + "/media1/ep.mp3";
        given().get(url).then().statusCode(200).header("Content-Length", "100");
        given().header("Range", "bytes=0-9").get(url).then().statusCode(206).header("Content-Length", "10");
    }

    @Test
    void mediaRequiresTheShowsToken() throws Exception {
        TestData.show("media2");
        Show other = TestData.show("media3");
        storeBytes("media2/ep.mp3", 100);
        given().get("/media/media2/ep.mp3").then().statusCode(404);
        given().get("/media/0123456789abcdef0123456789abcdef/media2/ep.mp3").then().statusCode(404);
        given().get("/media/" + other.feedToken + "/media2/ep.mp3").then().statusCode(404);
        given().urlEncodingEnabled(false).get("/media/" + other.feedToken + "/media3/../media2/ep.mp3").then().statusCode(404);
    }

    @Test
    void storageRejectsPathTraversal() {
        assertThrows(IllegalArgumentException.class, () -> storage.resolve("../etc/passwd"));
    }

    @Test
    void retentionKeepsNewestEpisodes() throws Exception {
        Show show = TestData.show("ret1");
        QuarkusTransaction.requiringNew().run(() -> Show.<Show>findById(show.id).retainEpisodes = 2);
        Instant now = Instant.now();
        Episode oldest = episode(show, "e1", storeBytes("ret1/e1.mp3", 10), now.minus(3, ChronoUnit.DAYS));
        episode(show, "e2", storeBytes("ret1/e2.mp3", 10), now.minus(2, ChronoUnit.DAYS));
        episode(show, "e3", storeBytes("ret1/e3.mp3", 10), now.minus(1, ChronoUnit.DAYS));

        assertEquals(1, retention.apply(show.id));

        Episode reloaded = QuarkusTransaction.requiringNew().call(() -> Episode.<Episode>findById(oldest.id));
        assertNull(reloaded.audioPath);
        assertFalse(storage.exists("ret1/e1.mp3"));
        assertTrue(storage.exists("ret1/e3.mp3"));
    }
}
