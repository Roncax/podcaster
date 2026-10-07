package org.roncax.podcaster.admin;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.domain.Show;
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
        given().redirects().follow(false).get("/admin/shows").then().statusCode(303).header("Location", endsWith("/admin/login"));
    }

    @Test
    void loginSetsCookie() {
        given().redirects().follow(false).formParam("key", "test-api-key-0123456789").post("/admin/login")
                .then().statusCode(303).cookie("podcaster_key", "test-api-key-0123456789");
        given().formParam("key", "nope").post("/admin/login").then().statusCode(200).body(containsString("Wrong key"));
    }

    @Test
    void listsShows() {
        Show show = TestData.show("listed");
        admin().get("/admin/shows").then().statusCode(200).body(containsString("Show listed"))
                .body(containsString("/feeds/" + show.feedToken + "/listed.xml"));
    }

    @Test
    void regeneratesFeedToken() {
        Show show = TestData.show("rotated");
        admin().post("/admin/shows/" + show.id + "/feed-token").then().statusCode(303)
                .header("Location", endsWith("/admin/shows/" + show.id));
        String token = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
                .call(() -> Show.<Show>findById(show.id).feedToken);
        org.junit.jupiter.api.Assertions.assertNotEquals(show.feedToken, token);
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
    }

    @Test
    void invalidFormIsRerenderedWithErrors() {
        admin().formParam("name", "X").formParam("slug", "x").formParam("language", "it")
                .formParam("voiceId", "it_IT-paola-medium").formParam("writerModel", "gpt")
                .formParam("targetDurationMinutes", "abc")
                .post("/admin/shows").then().statusCode(200)
                .body(containsString("writerModel &#39;gpt&#39;"))
                .body(containsString("targetDurationMinutes must be a number"));
    }

    @Test
    void addsSourceAndTriggersRun() {
        Show show = TestData.show("ui");
        admin().formParam("connectorType", "rss").formParam("config", "url=" + wm.baseUrl() + "/uifeed")
                .formParam("fetchFullText", "on")
                .post("/admin/shows/" + show.id + "/sources").then().statusCode(303);
        admin().get("/admin/shows/" + show.id).then().statusCode(200).body(containsString(wm.baseUrl() + "/uifeed"));
        admin().post("/admin/shows/" + show.id + "/run").then().statusCode(200).body(containsString("id=\"runs\""));
        admin().get("/admin/shows/" + show.id + "/runs").then().statusCode(200).body(containsString("<table"));
    }
}
