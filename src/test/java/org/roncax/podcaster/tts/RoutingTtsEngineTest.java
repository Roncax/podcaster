package org.roncax.podcaster.tts;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.*;
import org.roncax.podcaster.support.TestAudio;

class RoutingTtsEngineTest {
    static WireMockServer wm;
    static final TtsLimits PIPER = new TtsLimits(500, 2);
    static final TtsLimits SIBILIA = new TtsLimits(300, 2);

    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }

    @BeforeEach
    void stubs() {
        wm.resetAll();
        wm.stubFor(get("/piper/voices").willReturn(okJson("{\"it_IT-paola-medium\":{}}")));
        wm.stubFor(post("/piper/synthesize").willReturn(aResponse().withStatus(200).withBody(TestAudio.sineWav(0.1))));
        wm.stubFor(get("/sibilia/voices").willReturn(okJson("{\"sibilia\":{},\"sibilia-anna\":{}}")));
        wm.stubFor(post("/sibilia/synthesize").willReturn(aResponse().withStatus(200).withBody(TestAudio.sineWav(0.1, 24000))));
    }

    private static HttpTtsEngine http(String name, String path) {
        return new HttpTtsEngine(name, wm.baseUrl() + path, Map.of(), 1, Duration.ofMillis(5), Duration.ofSeconds(5), Duration.ofSeconds(2));
    }

    private static RoutingTtsEngine router(boolean withSibilia) {
        return new RoutingTtsEngine(http("Piper", "/piper"), withSibilia ? Optional.of(http("Sibilia", "/sibilia")) : Optional.empty(), PIPER, SIBILIA);
    }

    @Test
    void recognisesCloudVoices() {
        assertTrue(RoutingTtsEngine.isCloudVoice("sibilia"));
        assertTrue(RoutingTtsEngine.isCloudVoice("sibilia-anna"));
        assertTrue(RoutingTtsEngine.isCloudVoice("azzurra"));
        assertFalse(RoutingTtsEngine.isCloudVoice("azzurra-anna"), "azzurra has no reference voices");
        assertFalse(RoutingTtsEngine.isCloudVoice("sibilian"));
        assertFalse(RoutingTtsEngine.isCloudVoice("it_IT-paola-medium"));
        assertFalse(RoutingTtsEngine.isCloudVoice(null));
    }

    @Test
    void routesByVoice() throws Exception {
        RoutingTtsEngine router = router(true);
        router.synthesize("ciao", new VoiceConfig("sibilia-anna", 1.0));
        router.synthesize("ciao", new VoiceConfig("azzurra", 1.0));
        router.synthesize("ciao", new VoiceConfig("it_IT-paola-medium", 1.0));
        wm.verify(1, postRequestedFor(urlEqualTo("/sibilia/synthesize")).withRequestBody(matchingJsonPath("$.voice", equalTo("sibilia-anna"))));
        wm.verify(1, postRequestedFor(urlEqualTo("/sibilia/synthesize")).withRequestBody(matchingJsonPath("$.voice", equalTo("azzurra"))));
        wm.verify(1, postRequestedFor(urlEqualTo("/piper/synthesize")).withRequestBody(matchingJsonPath("$.voice", equalTo("it_IT-paola-medium"))));
    }

    @Test
    void mergesVoices() throws Exception {
        assertEquals(Set.of("it_IT-paola-medium", "sibilia", "sibilia-anna"), router(true).voices());
    }

    @Test
    void sibiliaDownStillListsPiperVoices() throws Exception {
        wm.stubFor(get("/sibilia/voices").willReturn(serverError()));
        assertEquals(Set.of("it_IT-paola-medium"), router(true).voices());
    }

    @Test
    void unconfiguredSibiliaListsPiperOnlyAndRefusesSibiliaVoices() throws Exception {
        RoutingTtsEngine router = router(false);
        assertEquals(Set.of("it_IT-paola-medium"), router.voices());
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> router.synthesize("ciao", new VoiceConfig("sibilia", 1.0)));
        assertEquals("Cloud TTS is not configured (PODCASTER_SIBILIA_URL)", ex.getMessage());
        wm.verify(0, postRequestedFor(urlPathMatching("/.*synthesize")));
    }

    @Test
    void limitsPerVoice() {
        RoutingTtsEngine router = router(true);
        assertEquals(SIBILIA, router.limits("sibilia"));
        assertEquals(SIBILIA, router.limits("azzurra"));
        assertEquals(PIPER, router.limits("it_IT-paola-medium"));
    }

    @Test
    void cloudStatus() {
        assertEquals("Not configured", router(false).cloudStatus());
        assertEquals("Connected · localhost", router(true).cloudStatus());
        wm.stubFor(get("/sibilia/voices").willReturn(serverError()));
        assertEquals("Unreachable · localhost", router(true).cloudStatus());
    }
}
