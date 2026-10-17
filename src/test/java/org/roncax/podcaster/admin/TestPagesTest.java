package org.roncax.podcaster.admin;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.domain.*;
import org.roncax.podcaster.support.*;
import org.roncax.podcaster.testing.TestSession;
import org.roncax.podcaster.testing.TestSessionStore;

@QuarkusTest
@WithTestResource(WireMockResource.class)
@WithTestResource(org.roncax.podcaster.support.PostgresResource.class)
class TestPagesTest {
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
    void testTabRendersIdlePanel() {
        Show show = TestData.show("tt");
        admin().get("/admin/shows/" + show.id + "?tab=test").then().statusCode(200)
                .body(containsString("Fetch sources")).body(containsString("Run dry-run")).body(containsString("Synthesize"))
                .body(containsString("Fetch sources first"));
    }

    @Test
    void fullChainThroughAdminForms() {
        Show show = showWithFeed("ttc");
        String base = "/admin/shows/" + show.id + "/test";
        admin().header("HX-Request", "true").post(base + "/sources").then().statusCode(200).body(containsString("hx-trigger=\"every 2s\""));
        awaitIdle(show.id);
        admin().get(base).then().statusCode(200).body(containsString("First story"));

        admin().header("HX-Request", "true").formParam("mode", "draft").formParam("items", "session").post(base + "/prompts").then().statusCode(200);
        awaitIdle(show.id);
        admin().get(base).then().statusCode(200).body(containsString("Test episode")).body(containsString("data-voice-part"));

        admin().header("HX-Request", "true").formParam("text", "Ciao a tutti.").post(base + "/voice").then().statusCode(200);
        awaitIdle(show.id);
        admin().get(base).then().statusCode(200).body(containsString("<audio"));
        admin().get(base + "/voice.wav").then().statusCode(200).contentType("audio/wav");
    }

    @Test
    void validationErrorIsShownInPanel() {
        Show show = TestData.show("tterr");
        admin().header("HX-Request", "true").formParam("text", "a".repeat(1001)).post("/admin/shows/" + show.id + "/test/voice")
                .then().statusCode(200).body(containsString("the limit is 1000"));
    }

    @Test
    void showPromptPageLinksToTestTab() {
        Show show = TestData.show("ttl");
        admin().get("/admin/shows/" + show.id + "/prompts/rank").then().statusCode(200)
                .body(containsString("/admin/shows/" + show.id + "?tab=test"));
    }

    void awaitIdle(long showId) {
        TestSessionStore store = io.quarkus.arc.Arc.container().instance(TestSessionStore.class).get();
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (store.find(showId).map(TestSession::running).orElse(false)) {
            if (Instant.now().isAfter(deadline)) throw new AssertionError("still running");
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
}
