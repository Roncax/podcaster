package org.roncax.podcaster.admin;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.support.WireMockResource;

@QuarkusTest
@WithTestResource(WireMockResource.class)
@WithTestResource(org.roncax.podcaster.support.PostgresResource.class)
class AssetsTest {

    @Test
    void layoutLinksServedBundle() {
        String html = given().get("/admin/login").then().statusCode(200).extract().asString();
        Matcher css = Pattern.compile("href=\"(/static/bundle/app[^\"]*\\.css)\"").matcher(html);
        assertTrue(css.find(), "layout must link the app CSS bundle: " + html.substring(0, Math.min(600, html.length())));
        String body = given().get(css.group(1)).then().statusCode(200).extract().asString();
        assertTrue(body.contains("--color-accent"), "theme tokens compiled into the bundle");
        assertTrue(body.contains("IBM Plex Sans"), "fonts bundled");
        Matcher js = Pattern.compile("src=\"(/static/bundle/app[^\"]*\\.js)\"").matcher(html);
        assertTrue(js.find(), "layout must load the app JS bundle");
        given().get(js.group(1)).then().statusCode(200);
    }

    @Test
    void noExternalAssetReferences() {
        String html = given().get("/admin/login").then().statusCode(200).extract().asString();
        for (String host : new String[] {"unpkg.com", "jsdelivr", "fonts.googleapis", "cdn."}) {
            assertFalse(html.contains(host), "external asset reference: " + host);
        }
    }
}
