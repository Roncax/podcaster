package org.roncax.podcaster.admin;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.domain.*;
import org.roncax.podcaster.support.*;

@QuarkusTest
@WithTestResource(WireMockResource.class)
@WithTestResource(org.roncax.podcaster.support.PostgresResource.class)
class EpisodePagesTest {
    @InjectWireMock WireMockServer wm;

    @BeforeEach
    void setup() {
        TestData.cleanDb();
        WireMockResource.installDefaults(wm);
        FakeChatModelRegistry.install(new FakeChatModel().responder(FakeResponses::pipeline));
    }

    private RequestSpecification admin() {
        return given().cookie("podcaster_key", "test-api-key-0123456789").redirects().follow(false);
    }

    private Episode episode(Show show, String title, List<Chapter> chapters, Outline outline) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Run r = new Run();
            r.showId = show.id; r.trigger = RunTrigger.MANUAL; r.status = RunStatus.DONE; r.stage = RunStage.PUBLISH; r.since = Instant.now();
            r.persist();
            Episode e = new Episode();
            e.runId = r.id; e.showId = show.id; e.title = title; e.audioPath = show.slug + "/" + r.id + ".mp3";
            e.durationSeconds = 90.0; e.sizeBytes = 1_500_000L; e.publishedAt = Instant.now();
            e.chapters = chapters; e.outline = outline; e.scriptParts = List.of("Benvenuti.", "Prima storia.", "Ciao.");
            e.promptVersions = Map.of("rank", 2, "segment", 1);
            e.persist();
            return e;
        });
    }

    @Test
    void listsEpisodesAcrossShowsWithFilter() {
        Show a = TestData.show("ep-a");
        Show b = TestData.show("ep-b");
        episode(a, "Episodio A", null, null);
        episode(b, "Episodio B", null, null);
        admin().get("/admin/episodes").then().statusCode(200).body(containsString("Episodio A")).body(containsString("Episodio B"));
        admin().get("/admin/episodes?show=" + a.id).then().statusCode(200).body(containsString("Episodio A")).body(not(containsString("Episodio B")));
    }

    @Test
    void episodePageShowsChaptersWithSourcesAndRedditThread() {
        Show show = TestData.show("ep-c");
        Source src = TestData.source(show.id, "http://feed");
        Item item = TestData.item(show.id, src.id, "https://news.example/a", Instant.now());
        QuarkusTransaction.requiringNew().run(() -> Item.<Item>findById(item.id).discussionUrl = "https://www.reddit.com/r/italy/comments/x1/y/");
        Episode e = episode(show, "Episodio con capitoli", List.of(
                new Chapter("Intro", 0.0, List.of()),
                new Chapter("Storia A", 12.5, List.of(item.id)),
                new Chapter("Outro", 80.0, List.of())), null);

        admin().get("/admin/episodes/" + e.id).then().statusCode(200)
                .body(containsString("Episodio con capitoli"))
                .body(containsString("Storia A"))
                .body(containsString("data-start=\"12.5\""))
                .body(containsString("0:13"))
                .body(containsString("https://news.example/a"))
                .body(containsString("Discussione su Reddit"))
                .body(containsString("https://www.reddit.com/r/italy/comments/x1/y/"))
                .body(containsString("rank v2"))
                .body(containsString("/static/bundle/player"));
    }

    @Test
    void oldEpisodeWithoutChaptersRenders() {
        Show show = TestData.show("ep-old");
        Episode e = episode(show, "Episodio vecchio", null,
                new Outline(500, List.of(new OutlineSegment("Storia senza tempi", List.of(), 300))));
        String html = admin().get("/admin/episodes/" + e.id).then().statusCode(200).extract().asString();
        assertTrue(html.contains("Storia senza tempi"));
        assertFalse(html.contains("data-start="), "no timestamps for episodes without recorded chapters");
    }
}
