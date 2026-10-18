package org.roncax.podcaster.tts;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.*;
import org.roncax.podcaster.support.TestAudio;

class HttpTtsEngineTest {
    static WireMockServer wm;
    HttpTtsEngine engine;

    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }

    @BeforeEach
    void reset() {
        wm.resetAll();
        engine = engine("Piper", wm.baseUrl() + "/", Map.of(), 3, Duration.ofSeconds(5));
    }

    private static HttpTtsEngine engine(String name, String url, Map<String, String> headers, int attempts, Duration timeout) {
        return new HttpTtsEngine(name, url, headers, attempts, Duration.ofMillis(5), timeout, Duration.ofSeconds(10));
    }

    @Test
    void postsTextVoiceAndLengthScale() throws Exception {
        byte[] wav = TestAudio.sineWav(0.2);
        wm.stubFor(post("/synthesize").willReturn(aResponse().withStatus(200).withBody(wav)));
        assertArrayEquals(wav, engine.synthesize("Ciao a tutti.", new VoiceConfig("it_IT-paola-medium", 1.1)));
        wm.verify(postRequestedFor(urlEqualTo("/synthesize")).withRequestBody(
                equalToJson("{\"text\":\"Ciao a tutti.\",\"voice\":\"it_IT-paola-medium\",\"length_scale\":1.1}")));
    }

    @Test
    void retriesServerErrors() throws Exception {
        wm.stubFor(post("/synthesize").inScenario("p").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(serviceUnavailable()).willSetStateTo("up"));
        wm.stubFor(post("/synthesize").inScenario("p").whenScenarioStateIs("up")
                .willReturn(aResponse().withStatus(200).withBody(TestAudio.sineWav(0.1))));
        assertTrue(Wav.looksLikeWav(engine.synthesize("x", new VoiceConfig("v", 1.0))));
        wm.verify(2, postRequestedFor(urlEqualTo("/synthesize")));
    }

    @Test
    void retriesTooManyRequests() throws Exception {
        wm.stubFor(post("/synthesize").inScenario("r").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(429)).willSetStateTo("ok"));
        wm.stubFor(post("/synthesize").inScenario("r").whenScenarioStateIs("ok")
                .willReturn(aResponse().withStatus(200).withBody(TestAudio.sineWav(0.1))));
        assertTrue(Wav.looksLikeWav(engine.synthesize("x", new VoiceConfig("v", 1.0))));
        wm.verify(2, postRequestedFor(urlEqualTo("/synthesize")));
    }

    @Test
    void rejectsNonWavBody() {
        wm.stubFor(post("/synthesize").willReturn(aResponse().withStatus(200).withBody("<html>oops</html>")));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> engine.synthesize("x", new VoiceConfig("v", 1.0)));
        assertTrue(ex.getMessage().contains("Piper returned a non-WAV"), ex.getMessage());
    }

    @Test
    void clientErrorsAreNotRetried() {
        wm.stubFor(post("/synthesize").willReturn(aResponse().withStatus(400).withBody("bad voice")));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> engine.synthesize("x", new VoiceConfig("missing", 1.0)));
        assertTrue(ex.getMessage().contains("400"), ex.getMessage());
        assertTrue(ex.getMessage().contains("bad voice"), ex.getMessage());
        wm.verify(1, postRequestedFor(urlEqualTo("/synthesize")));
    }

    @Test
    void rejectedCredentialsAreNotRetriedAndNameTheEngine() {
        HttpTtsEngine sibilia = engine("Sibilia", wm.baseUrl(), Map.of("Modal-Key", "k", "Modal-Secret", "s"), 3, Duration.ofSeconds(5));
        wm.stubFor(post("/synthesize").willReturn(aResponse().withStatus(401).withBody("missing credentials")));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> sibilia.synthesize("x", new VoiceConfig("sibilia", 1.0)));
        assertEquals("Sibilia rejected the credentials (HTTP 401)", ex.getMessage());
        wm.verify(1, postRequestedFor(urlEqualTo("/synthesize")));
    }

    @Test
    void sendsConfiguredHeaders() throws Exception {
        HttpTtsEngine sibilia = engine("Sibilia", wm.baseUrl(), Map.of("Modal-Key", "wk-1", "Modal-Secret", "ws-2"), 1, Duration.ofSeconds(5));
        wm.stubFor(post("/synthesize").willReturn(aResponse().withStatus(200).withBody(TestAudio.sineWav(0.1, 24000))));
        wm.stubFor(get("/voices").willReturn(okJson("{\"sibilia\":{}}")));
        sibilia.synthesize("x", new VoiceConfig("sibilia", 1.0));
        sibilia.voices();
        wm.verify(postRequestedFor(urlEqualTo("/synthesize")).withHeader("Modal-Key", equalTo("wk-1")).withHeader("Modal-Secret", equalTo("ws-2")));
        wm.verify(getRequestedFor(urlEqualTo("/voices")).withHeader("Modal-Key", equalTo("wk-1")).withHeader("Modal-Secret", equalTo("ws-2")));
    }

    @Test
    void followsModalRedirectKeepingHeaders() throws Exception {
        HttpTtsEngine sibilia = engine("Sibilia", wm.baseUrl(), Map.of("Modal-Key", "wk-1", "Modal-Secret", "ws-2"), 1, Duration.ofSeconds(5));
        byte[] wav = TestAudio.sineWav(0.1, 24000);
        wm.stubFor(post("/synthesize").willReturn(aResponse().withStatus(303).withHeader("Location", "/synthesize?__modal_function_call_id=fc-1")));
        wm.stubFor(get("/synthesize?__modal_function_call_id=fc-1").willReturn(aResponse().withStatus(200).withBody(wav)));
        assertArrayEquals(wav, sibilia.synthesize("x", new VoiceConfig("sibilia", 1.0)));
        wm.verify(getRequestedFor(urlEqualTo("/synthesize?__modal_function_call_id=fc-1"))
                .withHeader("Modal-Key", equalTo("wk-1")).withHeader("Modal-Secret", equalTo("ws-2")));
    }

    @Test
    void listsVoices() throws Exception {
        wm.stubFor(get("/voices").willReturn(okJson("{\"it_IT-paola-medium\":{\"x\":1},\"en_US-lessac-medium\":{}}")));
        assertEquals(Set.of("it_IT-paola-medium", "en_US-lessac-medium"), engine.voices());
    }

    @Test
    void voicesTimeoutIsConfigurable() {
        wm.stubFor(get("/voices").willReturn(okJson("{}").withFixedDelay(1500)));
        HttpTtsEngine impatient = new HttpTtsEngine("Sibilia", wm.baseUrl(), Map.of(), 1, Duration.ofMillis(5),
                Duration.ofSeconds(5), Duration.ofMillis(300));
        Exception ex = assertThrows(Exception.class, impatient::voices);
        assertTrue(ex.getMessage().contains("Sibilia"), ex.getMessage());
    }

    @Test
    void requestTimeoutIsConfigurable() throws Exception {
        wm.stubFor(post("/synthesize").willReturn(aResponse().withStatus(200).withBody(TestAudio.sineWav(0.1)).withFixedDelay(1500)));

        HttpTtsEngine impatient = engine("Piper", wm.baseUrl(), Map.of(), 2, Duration.ofMillis(300));
        Exception ex = assertThrows(Exception.class, () -> impatient.synthesize("x", new VoiceConfig("v", 1.0)));
        assertTrue(ex.getMessage().contains("timed out"), ex.getMessage());
        wm.verify(2, postRequestedFor(urlEqualTo("/synthesize")));

        HttpTtsEngine patient = engine("Piper", wm.baseUrl(), Map.of(), 1, Duration.ofSeconds(5));
        assertTrue(Wav.looksLikeWav(patient.synthesize("x", new VoiceConfig("v", 1.0))));
    }

    @Test
    void exposesNameAndHostOnly() {
        HttpTtsEngine sibilia = engine("Sibilia", "https://ws--podcaster-sibilia-api.modal.run", Map.of("Modal-Key", "k"), 1, Duration.ofSeconds(5));
        assertEquals("Sibilia", sibilia.name());
        assertEquals("ws--podcaster-sibilia-api.modal.run", sibilia.host());
    }
}
