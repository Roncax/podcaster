package org.roncax.podcaster;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.support.PostgresResource;
import org.roncax.podcaster.support.WireMockResource;

@QuarkusTest
@WithTestResource(WireMockResource.class)
@WithTestResource(PostgresResource.class)
class HealthTest {
    @Test
    void appStartsAndIsHealthy() {
        given().get("/q/health").then().statusCode(200).body("status", is("UP"));
    }
}
