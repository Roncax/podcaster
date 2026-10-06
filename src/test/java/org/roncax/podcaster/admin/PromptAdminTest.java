package org.roncax.podcaster.admin;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.domain.Show;
import org.roncax.podcaster.prompts.PromptKey;
import org.roncax.podcaster.prompts.PromptLabel;
import org.roncax.podcaster.prompts.PromptRegistry;
import org.roncax.podcaster.support.*;

@QuarkusTest
@WithTestResource(WireMockResource.class)
@WithTestResource(org.roncax.podcaster.support.PostgresResource.class)
class PromptAdminTest {
    static final String REPAIR_V2 = "JSON broken ({error}). Send only the object.";

    @InjectWireMock WireMockServer wm;
    @Inject PromptRegistry registry;

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
    void promptsPageListsAllPrompts() {
        admin().get("/admin/prompts").then().statusCode(200)
                .body(containsString("rank")).body(containsString("segment"))
                .body(containsString("framing")).body(containsString("json_repair"));
    }

    @Test
    void createVersionFromForm() {
        String location = admin().formParam("body", REPAIR_V2).formParam("note", "shorter")
                .post("/admin/prompts/json_repair/versions").then().statusCode(303).extract().header("Location");
        assertTrue(location.endsWith("/admin/prompts/json_repair?v=2"), location);
        admin().get(location).then().statusCode(200).body(containsString("shorter")).body(containsString("diff-add"));
    }

    @Test
    void invalidFormShowsErrorsAndKeepsText() {
        admin().formParam("body", "no placeholders").post("/admin/prompts/json_repair/versions").then().statusCode(200)
                .body(containsString("Missing required variable {error}"))
                .body(containsString("no placeholders"));
    }

    @Test
    void promoteFromForm() {
        registry.createVersion(PromptKey.JSON_REPAIR, REPAIR_V2, null);
        admin().formParam("version", "2").post("/admin/prompts/json_repair/labels/production").then().statusCode(303);
        assertEquals(2, registry.labels(PromptKey.JSON_REPAIR).get(PromptLabel.PRODUCTION));
    }

    @Test
    void showOverridesFromForm() {
        Show show = TestData.show("ovr");
        admin().formParam("rank", "1").formParam("segment", "").formParam("framing", "").formParam("json_repair", "")
                .post("/admin/shows/" + show.id + "/prompts").then().statusCode(303);
        assertEquals(Map.of(PromptKey.RANK, 1), registry.overrides(show.id));
        admin().get("/admin/shows/" + show.id).then().statusCode(200).body(containsString("Prompt overrides"));
        admin().formParam("rank", "").formParam("segment", "").formParam("framing", "").formParam("json_repair", "")
                .post("/admin/shows/" + show.id + "/prompts").then().statusCode(303);
        assertTrue(registry.overrides(show.id).isEmpty());
    }

    @Test
    void dryRunFragmentExplainsMissingItems() {
        Show show = TestData.show("dryui");
        admin().formParam("showId", String.valueOf(show.id)).post("/admin/prompts/dry-run").then().statusCode(200)
                .body(containsString("No unused items"));
    }

    @Test
    void dryRunFragmentShowsModelFailure() {
        Show show = TestData.show("dryfail");
        var source = TestData.source(show.id, "http://unused/feed");
        TestData.item(show.id, source.id, "http://story/x", java.time.Instant.now());
        FakeChatModelRegistry.install(new FakeChatModel().responder(prompt -> { throw new RuntimeException("connection refused by model host"); }));
        admin().formParam("showId", String.valueOf(show.id)).post("/admin/prompts/dry-run").then().statusCode(200)
                .body(containsString("connection refused by model host"));
    }
}
