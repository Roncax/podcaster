package org.roncax.podcaster.admin;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.domain.*;
import org.roncax.podcaster.support.*;

@QuarkusTest
@WithTestResource(WireMockResource.class)
@WithTestResource(org.roncax.podcaster.support.PostgresResource.class)
class AdminTest {
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

    @Test
    void redirectsToLoginWithoutCookie() {
        given().redirects().follow(false).get("/admin").then().statusCode(303).header("Location", endsWith("/admin/login"));
    }

    @Test
    void loginSetsCookieAndOpensDashboard() {
        given().redirects().follow(false).formParam("key", "test-api-key-0123456789").post("/admin/login")
                .then().statusCode(303).cookie("podcaster_key", "test-api-key-0123456789").header("Location", endsWith("/admin"));
        given().formParam("key", "nope").post("/admin/login").then().statusCode(200).body(containsString("Wrong key"));
    }

    @Test
    void dashboardShowsShowsLatestEpisodeRunsAndAttention() {
        Show show = TestData.show("dash");
        Source source = TestData.source(show.id, "http://feed/x");
        QuarkusTransaction.requiringNew().run(() -> {
            Source s = Source.findById(source.id);
            s.lastError = "HTTP 404 from http://feed/x";
            Run ok = new Run();
            ok.showId = show.id; ok.trigger = RunTrigger.MANUAL; ok.status = RunStatus.DONE; ok.stage = RunStage.PUBLISH; ok.since = Instant.now();
            ok.persist();
            Episode e = new Episode();
            e.runId = ok.id; e.showId = show.id; e.title = "Episodio di prova"; e.audioPath = "dash/1.mp3";
            e.durationSeconds = 245.0; e.publishedAt = Instant.now();
            e.persist();
            Run failed = new Run();
            failed.showId = show.id; failed.trigger = RunTrigger.SCHEDULED; failed.status = RunStatus.FAILED; failed.stage = RunStage.TTS;
            failed.error = "TTS: Piper returned HTTP 503"; failed.since = Instant.now();
            failed.persist();
        });

        admin().get("/admin").then().statusCode(200)
                .body(containsString("Show dash"))
                .body(containsString("Episodio di prova"))
                .body(containsString("/media/dash/1.mp3"))
                .body(containsString("4:05"))
                .body(containsString("Source failing"))
                .body(containsString("HTTP 404 from http://feed/x"))
                .body(containsString("TTS: Piper returned HTTP 503"))
                .body(containsString("aria-current=\"page\""));
    }

    @Test
    void settingsListsModelsVoicesAndConnectors() {
        admin().get("/admin/settings").then().statusCode(200)
                .body(containsString("fake"))
                .body(containsString("it_IT-paola-medium"))
                .body(containsString("reddit"))
                .body(containsString("rss"));
    }
}
