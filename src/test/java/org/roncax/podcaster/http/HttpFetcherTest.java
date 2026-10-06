package org.roncax.podcaster.http;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.*;

class HttpFetcherTest {
    static WireMockServer wm;
    HttpFetcher fetcher = new HttpFetcher("TestAgent/1.0", Duration.ofSeconds(5), Duration.ZERO, 3, Duration.ofMillis(5));

    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }
    @BeforeEach void reset() { wm.resetAll(); }

    @Test
    void returnsBodyAndSendsUserAgent() throws Exception {
        wm.stubFor(get("/page").willReturn(ok("hello")));
        assertEquals("hello", new String(fetcher.get(wm.baseUrl() + "/page"), StandardCharsets.UTF_8));
        wm.verify(getRequestedFor(urlEqualTo("/page")).withHeader("User-Agent", equalTo("TestAgent/1.0")));
    }

    @Test
    void retriesServerErrors() throws Exception {
        wm.stubFor(get("/flaky").inScenario("f").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(serverError()).willSetStateTo("ok"));
        wm.stubFor(get("/flaky").inScenario("f").whenScenarioStateIs("ok").willReturn(ok("fine")));
        assertEquals("fine", new String(fetcher.get(wm.baseUrl() + "/flaky"), StandardCharsets.UTF_8));
        wm.verify(2, getRequestedFor(urlEqualTo("/flaky")));
    }

    @Test
    void clientErrorsFailWithoutRetry() {
        wm.stubFor(get("/missing").willReturn(notFound()));
        FetchException ex = assertThrows(FetchException.class, () -> fetcher.get(wm.baseUrl() + "/missing"));
        assertTrue(ex.getMessage().contains("404"), ex.getMessage());
        wm.verify(1, getRequestedFor(urlEqualTo("/missing")));
    }

    @Test
    void persistentServerErrorsFailAfterAttempts() {
        wm.stubFor(get("/down").willReturn(serviceUnavailable()));
        assertThrows(FetchException.class, () -> fetcher.get(wm.baseUrl() + "/down"));
        wm.verify(3, getRequestedFor(urlEqualTo("/down")));
    }

    @Test
    void singleAttemptDoesNotRetry() {
        wm.stubFor(get("/once").willReturn(aResponse().withStatus(429)));
        assertThrows(FetchException.class, () -> fetcher.get(wm.baseUrl() + "/once", 1));
        wm.verify(1, getRequestedFor(urlEqualTo("/once")));
    }

    @Test
    void untrustedFetchChecksEveryRedirectHop() {
        wm.stubFor(get("/start").willReturn(aResponse().withStatus(302).withHeader("Location", "/internal/secret")));
        wm.stubFor(get("/internal/secret").willReturn(ok("secret")));
        java.util.function.Predicate<java.net.URI> policy = u -> !u.getPath().startsWith("/internal");
        FetchException ex = assertThrows(FetchException.class, () -> fetcher.getUntrusted(wm.baseUrl() + "/start", policy));
        assertTrue(ex instanceof BlockedUrlException, ex.toString());
        wm.verify(0, getRequestedFor(urlEqualTo("/internal/secret")));
    }

    @Test
    void untrustedFetchFollowsAllowedRedirects() throws Exception {
        wm.stubFor(get("/a").willReturn(aResponse().withStatus(301).withHeader("Location", "/b")));
        wm.stubFor(get("/b").willReturn(ok("article")));
        assertEquals("article", new String(fetcher.getUntrusted(wm.baseUrl() + "/a", u -> true)));
    }
}
