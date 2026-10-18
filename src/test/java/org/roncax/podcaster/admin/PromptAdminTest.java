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
                .body(containsString("Default prompts")).body(containsString("rank")).body(containsString("segment"))
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
    void longPromptSavesFromForm() {
        String body = "Rispondi in italiano, perché è più naturale. ".repeat(500) + REPAIR_V2; // ~23 kB of form data
        admin().formParam("body", body).formParam("note", "long").post("/admin/prompts/json_repair/versions")
                .then().statusCode(303);
        assertEquals(body, registry.latestDefault(PromptKey.JSON_REPAIR).body);
    }

    @Test
    void invalidFormShowsErrorsAndKeepsText() {
        admin().formParam("body", "no placeholders").post("/admin/prompts/json_repair/versions").then().statusCode(200)
                .body(containsString("Missing required variable {error}"))
                .body(containsString("no placeholders"));
    }

    @Test
    void firstDefaultVersionRenders() {
        admin().get("/admin/prompts/rank").then().statusCode(200)
                .body(containsString("v1 (first version)")).body(containsString("Existing shows change only via Reset to default"))
                .body(not(containsString("Promote")));
    }

    @Test
    void formWorksWithoutIsland() {
        String html = admin().get("/admin/prompts/json_repair").then().statusCode(200).extract().asString();
        assertTrue(html.contains("data-prompt-editor"), "editor island hook");
        assertTrue(html.contains("name=\"body\""), "plain textarea is the submitted field");
        assertTrue(html.contains("data-prompt-diff"), "diff island hook");
        assertTrue(html.contains("/static/bundle/editor"), "editor bundle loaded");
        assertTrue(html.contains("data-insert-var=\"{error}\""), "variable chips");
        admin().formParam("body", REPAIR_V2).formParam("note", "plain form").post("/admin/prompts/json_repair/versions")
                .then().statusCode(303);
        assertEquals(2, registry.latestDefault(PromptKey.JSON_REPAIR).version);
    }

    @Test
    void showPromptsTabListsKeys() {
        Show show = TestData.show("tabp");
        admin().get("/admin/shows/" + show.id + "?tab=prompts").then().statusCode(200)
                .body(containsString("/admin/shows/" + show.id + "/prompts/rank")).body(containsString("production v1"));
    }

    @Test
    void showPromptCreatePromoteAndReset() {
        Show show = TestData.show("pp");
        String base = "/admin/shows/" + show.id + "/prompts/json_repair";
        String location = admin().formParam("body", REPAIR_V2).formParam("note", "shorter")
                .post(base + "/versions").then().statusCode(303).extract().header("Location");
        assertTrue(location.endsWith(base + "?v=2"), location);
        admin().get(base + "?v=2").then().statusCode(200).body(containsString("shorter")).body(containsString("diff-add"))
                .body(containsString("Promote")).body(containsString("Reset to default"));
        admin().formParam("version", "2").post(base + "/labels/production").then().statusCode(303);
        assertEquals(2, registry.labels(show.id, PromptKey.JSON_REPAIR).get(PromptLabel.PRODUCTION));
        admin().post(base + "/reset").then().statusCode(303);
        assertEquals(3, registry.labels(show.id, PromptKey.JSON_REPAIR).get(PromptLabel.DRAFT));
    }

    @Test
    void invalidShowPromptKeepsText() {
        Show show = TestData.show("ppbad");
        admin().formParam("body", "no placeholders").post("/admin/shows/" + show.id + "/prompts/json_repair/versions")
                .then().statusCode(200).body(containsString("Missing required variable")).body(containsString("no placeholders"));
    }
}
