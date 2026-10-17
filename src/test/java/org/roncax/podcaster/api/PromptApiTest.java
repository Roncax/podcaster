package org.roncax.podcaster.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.domain.*;
import org.roncax.podcaster.prompts.PromptKey;
import org.roncax.podcaster.support.*;

@QuarkusTest
@WithTestResource(WireMockResource.class)
@WithTestResource(org.roncax.podcaster.support.PostgresResource.class)
class PromptApiTest {
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

    private RequestSpecification api() {
        return given().header("X-API-Key", "test-api-key-0123456789").contentType(ContentType.JSON);
    }

    @Test
    void listsDefaults() {
        api().get("/api/prompts").then().statusCode(200)
                .body("size()", is(4)).body("find { it.key == 'rank' }.latest", is(1));
    }

    @Test
    void createAndInspectDefaultVersions() {
        api().body(Map.of("body", RANK_V2, "note", "marker")).post("/api/prompts/rank/versions").then().statusCode(201)
                .body("version", is(2)).body("labels", empty());
        api().get("/api/prompts/rank/versions").then().statusCode(200).body("version", contains(2, 1));
        api().get("/api/prompts/rank/versions/1").then().statusCode(200).body("body", is(PromptKey.RANK.seedBody()));
        api().body(Map.of("version", 2)).put("/api/prompts/rank/labels/production").then().statusCode(404);
    }

    @Test
    void showPromptsCreatePromoteReset() {
        Show show = TestData.show("sp");
        String base = "/api/shows/" + show.id + "/prompts";
        api().get(base).then().statusCode(200).body("size()", is(4))
                .body("find { it.key == 'rank' }.production", is(1)).body("find { it.key == 'rank' }.draft", is(1));
        api().body(Map.of("body", RANK_V2, "note", "marker")).post(base + "/rank/versions").then().statusCode(201)
                .body("version", is(2)).body("labels", contains("draft"));
        api().body(Map.of("version", 2)).put(base + "/rank/labels/production").then().statusCode(200).body("production", is(2));
        api().get(base + "/rank/versions").then().statusCode(200).body("version", contains(2, 1))
                .body("[0].labels", hasItems("production", "draft"));
        api().post(base + "/rank/reset").then().statusCode(201).body("version", is(3)).body("note", is("Reset to default v1"))
                .body("body", is(PromptKey.RANK.seedBody()));
    }

    @Test
    void invalidBodyIs400() {
        api().body(Map.of("body", "hello {nonsense}")).post("/api/prompts/rank/versions").then().statusCode(400)
                .body("details", hasItem("Unknown variable 'nonsense'"));
    }

    @Test
    void showPromptErrors() {
        Show show = TestData.show("spe");
        String base = "/api/shows/" + show.id + "/prompts";
        api().get("/api/shows/999999/prompts").then().statusCode(404);
        api().get(base + "/nope/versions").then().statusCode(404);
        api().body(Map.of("version", 1)).put(base + "/rank/labels/staging").then().statusCode(404);
        api().body(Map.of("version", 42)).put(base + "/rank/labels/production").then().statusCode(400);
        api().get(base + "/rank/versions/42").then().statusCode(404);
        api().body(Map.of("body", "hello {nonsense}")).post(base + "/rank/versions").then().statusCode(400);
        api().delete(base + "/rank").then().statusCode(404);
    }

    @Test
    void versionsCannotBeEdited() {
        api().body(Map.of("body", "x")).put("/api/prompts/rank/versions/1").then().statusCode(405);
    }

    @Test
    void dryRunUsesDraftAndPersistsNothing() {
        Show show = TestData.show("dry");
        Source source = TestData.source(show.id, "http://unused/feed");
        Item item = TestData.item(show.id, source.id, "http://story/1", Instant.now());
        api().body(Map.of("body", RANK_V2)).post("/api/shows/" + show.id + "/prompts/rank/versions").then().statusCode(201);

        api().body(Map.of("showId", show.id)).post("/api/prompts/dry-run").then().statusCode(200)
                .body("promptVersions.rank", is(2))
                .body("scriptParts.size()", is(3))
                .body("title", is("Test episode"));

        assertTrue(model.userMessage(0).contains("MARKER-RANK-V2"));
        assertEquals(0L, (long) QuarkusTransaction.requiringNew().call(() -> Episode.count()));
        assertNull(QuarkusTransaction.requiringNew().call(() -> Item.<Item>findById(item.id).usedInEpisodeId));
        assertEquals(0L, (long) QuarkusTransaction.requiringNew().call(() -> VoiceCalibration.count()));
    }

    @Test
    void dryRunWithoutItemsIs409() {
        Show show = TestData.show("dry-empty");
        api().body(Map.of("showId", show.id)).post("/api/prompts/dry-run").then().statusCode(409)
                .body("error", containsString("No unused items"));
        assertTrue(model.requests.isEmpty());
    }

    @Test
    void dryRunFailureIsReportedNot500() {
        Show show = TestData.show("dry-broken");
        Source source = TestData.source(show.id, "http://unused/feed");
        TestData.item(show.id, source.id, "http://story/broken", Instant.now());
        model.responder(prompt -> "this is not json");

        api().body(Map.of("showId", show.id)).post("/api/prompts/dry-run").then().statusCode(422)
                .body("error", containsString("did not return valid JSON"));
    }
}
