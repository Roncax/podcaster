package org.roncax.podcaster.admin;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.domain.*;
import org.roncax.podcaster.support.*;

@QuarkusTest
@WithTestResource(WireMockResource.class)
@WithTestResource(org.roncax.podcaster.support.PostgresResource.class)
class ShowPagesTest {
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

    private Run run(Show show, RunStatus status, RunStage stage, String progress) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Run r = new Run();
            r.showId = show.id; r.trigger = RunTrigger.MANUAL; r.status = status; r.stage = stage; r.since = Instant.now(); r.progress = progress;
            r.persist();
            return r;
        });
    }

    @Test
    void listsShowsWithFeedLinks() {
        Show show = TestData.show("listed");
        admin().get("/admin/shows").then().statusCode(200).body(containsString("Show listed"))
                .body(containsString("/feeds/" + show.feedToken + "/listed.xml"));
    }

    @Test
    void regeneratesFeedTokenFromSettingsTab() {
        Show show = TestData.show("rotated");
        admin().get("/admin/shows/" + show.id + "?tab=settings").then().statusCode(200)
                .body(containsString("/admin/shows/" + show.id + "/feed-token")).body(containsString(show.feedPath()));
        admin().post("/admin/shows/" + show.id + "/feed-token").then().statusCode(303)
                .header("Location", endsWith("/admin/shows/" + show.id + "?tab=settings"));
        String token = QuarkusTransaction.requiringNew().call(() -> Show.<Show>findById(show.id).feedToken);
        assertNotEquals(show.feedToken, token);
        given().get(show.feedPath()).then().statusCode(404);
        given().get("/feeds/" + token + "/rotated.xml").then().statusCode(200);
    }

    @Test
    void createsShowFromForm() {
        String location = admin()
                .formParam("name", "Morning News").formParam("slug", "morning").formParam("language", "it")
                .formParam("voiceId", "it_IT-paola-medium").formParam("writerModel", "fake").formParam("rankerModel", "")
                .formParam("lengthScale", "1.0").formParam("targetDurationMinutes", "20").formParam("minItems", "3")
                .formParam("retainEpisodes", "30").formParam("cron", "0 7 * * *").formParam("enabled", "on")
                .post("/admin/shows").then().statusCode(303).extract().header("Location");
        admin().get(location).then().statusCode(200).body(containsString("Morning News")).body(containsString("Run now"));
        admin().get(location + "?tab=settings").then().statusCode(200).body(containsStringIgnoringCase("at 07:00"));
    }

    @Test
    void invalidShowFormIsRerenderedWithErrors() {
        admin().formParam("name", "X").formParam("slug", "x").formParam("language", "it")
                .formParam("voiceId", "it_IT-paola-medium").formParam("writerModel", "gpt")
                .formParam("targetDurationMinutes", "abc")
                .post("/admin/shows").then().statusCode(200)
                .body(containsString("writerModel &#39;gpt&#39;"))
                .body(containsString("targetDurationMinutes must be a number"));
    }

    @Test
    void addsRssAndRedditSourcesFromForm() {
        Show show = TestData.show("src");
        admin().formParam("connectorType", "rss").formParam("url", wm.baseUrl() + "/feed").formParam("fetchFullText", "on")
                .post("/admin/shows/" + show.id + "/sources").then().statusCode(303);
        admin().formParam("connectorType", "reddit").formParam("subreddit", "italy").formParam("window", "day").formParam("maxPosts", "8")
                .post("/admin/shows/" + show.id + "/sources").then().statusCode(303);
        Map<String, String> reddit = QuarkusTransaction.requiringNew().call(() ->
                Source.<Source>find("showId = ?1 and connectorType = 'reddit'", show.id).firstResult().config);
        assertEquals(Map.of("subreddit", "italy", "window", "day", "maxPosts", "8"), reddit);
        admin().get("/admin/shows/" + show.id + "?tab=sources").then().statusCode(200)
                .body(containsString(wm.baseUrl() + "/feed")).body(containsString("r/italy"));
    }

    @Test
    void invalidSourceFormShowsErrors() {
        Show show = TestData.show("srcerr");
        admin().formParam("connectorType", "reddit").formParam("subreddit", "")
                .post("/admin/shows/" + show.id + "/sources").then().statusCode(200)
                .contentType(containsString("text/html"))
                .body(containsString("reddit sources need config.subreddit"));
    }

    @Test
    void runNowReturnsLiveCardForHtmxAndRedirectsOtherwise() {
        Show show = TestData.show("live");
        wm.stubFor(get("/livefeed").willReturn(okXml("<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>t</title><link>http://x</link><description>d</description></channel></rss>")));
        TestData.source(show.id, wm.baseUrl() + "/livefeed");
        admin().header("HX-Request", "true").post("/admin/shows/" + show.id + "/run").then().statusCode(200)
                .body(containsString("id=\"live-run\""));
        TestData.awaitRun(QuarkusTransaction.requiringNew().call(() -> Run.<Run>find("showId", show.id).firstResult().id));
        admin().post("/admin/shows/" + show.id + "/run").then().statusCode(303).header("Location", endsWith("/admin/shows/" + show.id));
    }

    @Test
    void liveFragmentPollsWhileRunningWithProgress() {
        Show show = TestData.show("poll");
        run(show, RunStatus.RUNNING, RunStage.SCRIPT, "Writing segment 3 of 9");
        admin().get("/admin/shows/" + show.id + "/live").then().statusCode(200)
                .body(containsString("hx-trigger=\"every 3s\""))
                .body(containsString("Writing segment 3 of 9"))
                .body(containsString("in progress"));
    }

    @Test
    void liveFragmentStopsPollingWhenFinished() {
        Show show = TestData.show("finished");
        Run r = run(show, RunStatus.FAILED, RunStage.TTS, null);
        QuarkusTransaction.requiringNew().run(() -> Run.<Run>findById(r.id).error = "TTS: Piper returned HTTP 503");
        String html = admin().get("/admin/shows/" + show.id + "/live").then().statusCode(200).extract().asString();
        assertFalse(html.contains("hx-trigger"), html);
        assertTrue(html.contains("TTS: Piper returned HTTP 503"));
        assertTrue(html.contains("/admin/runs/" + r.id + "/retry"));
    }

    @Test
    void tabsRender() {
        Show show = TestData.show("tabs");
        for (String tab : new String[] {"overview", "sources", "episodes", "settings", "prompts"}) {
            admin().get("/admin/shows/" + show.id + "?tab=" + tab).then().statusCode(200).body(containsString("aria-selected=\"true\""));
        }
        admin().get("/admin/shows/" + show.id + "?tab=prompts").then().statusCode(200).body(containsString("Prompt overrides"));
    }

    @Test
    void addSourceFormDefaultsToRssAndTagsFieldsetsByConnector() {
        Show show = TestData.show("srcform");
        String html = admin().get("/admin/shows/" + show.id + "?tab=sources").then().statusCode(200).extract().asString();
        assertTrue(html.contains("<option value=\"rss\" selected>"), "RSS is the default connector");
        assertTrue(html.contains("data-connector=\"rss\""), html);
        assertTrue(html.contains("data-connector=\"reddit\""), html);
    }

    @Test
    void settingsKeepsAWriterModelThatIsNotEnabled() {
        Show show = TestData.show("oldmodel");
        QuarkusTransaction.requiringNew().run(() -> {
            Show s = Show.findById(show.id);
            s.writerModel = "gpt";
            s.rankerModel = "claude";
        });
        String html = admin().get("/admin/shows/" + show.id + "?tab=settings").then().statusCode(200).extract().asString();
        assertTrue(html.contains("<option value=\"gpt\" selected>gpt (not enabled)</option>"), html);
        assertTrue(html.contains("<option value=\"claude\" selected>claude (not enabled)</option>"), html);
    }
}
