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
                .body(containsString("Fetch sources first"))
                .body(not(containsString("every 2s"))).body(containsString("hx-trigger=\"test-done from:body\""));
    }

    @Test
    void fullChainThroughAdminForms() {
        Show show = showWithFeed("ttc");
        String base = "/admin/shows/" + show.id + "/test";
        // While running only the small status element polls, never the whole panel.
        admin().header("HX-Request", "true").post(base + "/sources").then().statusCode(200)
                .body(containsString("<div id=\"test-status\" class=\"text-xs text-muted\" hx-get=\"" + base + "/status\" hx-trigger=\"every 2s\""))
                .body(not(containsString("hx-get=\"" + base + "\" hx-trigger=\"every 2s\"")));
        awaitIdle(show.id);
        admin().get(base + "/status").then().statusCode(200).header("HX-Trigger", "test-done")
                .body(containsString("id=\"test-status\"")).body(not(containsString("every 2s")));
        admin().get(base).then().statusCode(200).body(containsString("First story"));

        admin().header("HX-Request", "true").formParam("mode", "production").formParam("items", "session").post(base + "/prompts").then().statusCode(200);
        awaitIdle(show.id);
        admin().get(base).then().statusCode(200).body(containsString("Test episode")).body(containsString("data-voice-part"))
                .body(containsString("<option value=\"production\" selected>"))
                .body(containsString("<option value=\"session\"  selected>"));

        admin().header("HX-Request", "true").formParam("text", "Ciao a tutti.").formParam("lengthScale", "1.15").post(base + "/voice").then().statusCode(200);
        awaitIdle(show.id);
        admin().get(base).then().statusCode(200).body(containsString("<audio"))
                .body(containsString("data-voice-text>Ciao a tutti.</textarea>"))
                .body(containsString("name=\"lengthScale\" type=\"number\" step=\"0.05\" min=\"0.5\" max=\"2.0\" value=\"1.15\""))
                .body(containsString("voice.wav?v=1\""));
        admin().get(base + "/voice.wav").then().statusCode(200).contentType("audio/wav").header("Cache-Control", "no-store");

        admin().header("HX-Request", "true").formParam("text", "Di nuovo.").post(base + "/voice").then().statusCode(200);
        awaitIdle(show.id);
        admin().get(base).then().statusCode(200).body(containsString("voice.wav?v=2\""));
    }

    @Test
    void validationErrorIsShownInPanel() {
        Show show = TestData.show("tterr");
        admin().header("HX-Request", "true").formParam("text", "a".repeat(1001)).post("/admin/shows/" + show.id + "/test/voice")
                .then().statusCode(200).body(containsString("the limit is 1000"))
                .body(containsString("data-voice-text>" + "a".repeat(1001) + "</textarea>"));
    }

    @Test
    void invalidLengthScaleIsKeptInForm() {
        Show show = TestData.show("ttscale");
        admin().header("HX-Request", "true").formParam("text", "Ciao.").formParam("lengthScale", "abc").post("/admin/shows/" + show.id + "/test/voice")
                .then().statusCode(200).body(containsString("Length scale must be a number")).body(containsString("value=\"abc\""));
    }

    @Test
    void unknownShowIs404BeforeAnyStepStarts() {
        TestSessionStore store = io.quarkus.arc.Arc.container().instance(TestSessionStore.class).get();
        long missing = 987654L;
        admin().header("HX-Request", "true").post("/admin/shows/" + missing + "/test/sources").then().statusCode(404);
        admin().header("HX-Request", "true").formParam("text", "Ciao.").post("/admin/shows/" + missing + "/test/voice").then().statusCode(404);
        admin().get("/admin/shows/" + missing + "/test/status").then().statusCode(404);
        org.junit.jupiter.api.Assertions.assertTrue(store.find(missing).isEmpty());
    }

    @Test
    void nonHttpItemLinksAreNotRendered() {
        Show show = TestData.show("tthref");
        TestSessionStore store = io.quarkus.arc.Arc.container().instance(TestSessionStore.class).get();
        Item item = new Item();
        item.id = 1L;
        item.sourceId = 7L;
        item.title = "Evil item";
        item.url = "javascript:alert(1)";
        store.session(show.id).fetched(new TestSession.Fetched(
                java.util.List.of(new TestSession.SourceResult(7L, "Feed", 1, null)), java.util.List.of(item)));
        admin().get("/admin/shows/" + show.id + "/test").then().statusCode(200)
                .body(containsString("Evil item")).body(not(containsString("javascript:")));
    }

    @Test
    void showPromptPageLinksToTestTab() {
        Show show = TestData.show("ttl");
        admin().get("/admin/shows/" + show.id + "/prompts/rank").then().statusCode(200)
                .body(containsString("/admin/shows/" + show.id + "?tab=test"));
    }

    @Test
    void singlePromptRowIsRenderedWithHints() {
        Show show = TestData.show("tsp");
        admin().get("/admin/shows/" + show.id + "?tab=test").then().statusCode(200)
                .body(containsString("Test one prompt"))
                .body(containsString("<option value=\"rank\""))
                .body(containsString("<option value=\"segment\""))
                .body(containsString("<option value=\"framing\""))
                .body(not(containsString("<option value=\"json_repair\"")))
                .body(containsString("segment and framing need a prompt dry-run first"));
    }

    @Test
    void promptQueryPreselectsKey() {
        Show show = TestData.show("tsq");
        admin().get("/admin/shows/" + show.id + "?tab=test&prompt=framing").then().statusCode(200)
                .body(containsString("<option value=\"framing\" selected>"));
        // A link's preselection wins over a key submitted earlier in the session.
        admin().header("HX-Request", "true").formParam("key", "segment").post("/admin/shows/" + show.id + "/test/prompt").then().statusCode(200);
        admin().get("/admin/shows/" + show.id + "?tab=test&prompt=rank").then().statusCode(200)
                .body(containsString("<option value=\"rank\" selected>"));
    }

    @Test
    void singlePromptThroughAdminForm() {
        Show show = showWithFeed("tsr");
        String base = "/admin/shows/" + show.id + "/test";
        admin().header("HX-Request", "true").post(base + "/sources").then().statusCode(200);
        awaitIdle(show.id);
        admin().header("HX-Request", "true").formParam("key", "rank").formParam("mode", "draft").formParam("items", "session")
                .post(base + "/prompt").then().statusCode(200);
        awaitIdle(show.id);
        admin().get(base).then().statusCode(200)
                .body(containsString("rank · draft v1"))
                .body(containsString("TASK: RANK"))
                .body(containsString("&quot;clusters&quot;"))
                .body(containsString("<option value=\"rank\" selected>"));
    }

    @Test
    void singlePromptMissingInputIsShownInPanel() {
        Show show = TestData.show("tsm");
        admin().header("HX-Request", "true").formParam("key", "segment").formParam("mode", "draft")
                .post("/admin/shows/" + show.id + "/test/prompt").then().statusCode(200)
                .body(containsString("Run the prompt dry-run first"));
    }

    @Test
    void showPromptPageTestLinkPreselectsPrompt() {
        Show show = TestData.show("tsl");
        admin().get("/admin/shows/" + show.id + "/prompts/segment").then().statusCode(200)
                .body(containsString("/admin/shows/" + show.id + "?tab=test&amp;prompt=segment"));
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
