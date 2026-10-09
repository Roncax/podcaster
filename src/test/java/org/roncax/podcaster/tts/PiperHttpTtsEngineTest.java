package org.roncax.podcaster.tts;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.*;
import org.roncax.podcaster.support.TestAudio;

class PiperHttpTtsEngineTest {
    static WireMockServer wm;
    PiperHttpTtsEngine engine;

    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }

    @BeforeEach
    void reset() {
        wm.resetAll();
        engine = new PiperHttpTtsEngine(wm.baseUrl() + "/", 3, Duration.ofMillis(5));
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
    void rejectsNonWavBody() {
        wm.stubFor(post("/synthesize").willReturn(aResponse().withStatus(200).withBody("<html>oops</html>")));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> engine.synthesize("x", new VoiceConfig("v", 1.0)));
        assertTrue(ex.getMessage().contains("non-WAV"), ex.getMessage());
    }

    @Test
    void clientErrorsAreNotRetried() {
        wm.stubFor(post("/synthesize").willReturn(aResponse().withStatus(400).withBody("bad voice")));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> engine.synthesize("x", new VoiceConfig("missing", 1.0)));
        assertTrue(ex.getMessage().contains("400"));
        wm.verify(1, postRequestedFor(urlEqualTo("/synthesize")));
    }

    @Test
    void listsVoices() throws Exception {
        wm.stubFor(get("/voices").willReturn(okJson("{\"it_IT-paola-medium\":{\"x\":1},\"en_US-lessac-medium\":{}}")));
        assertEquals(Set.of("it_IT-paola-medium", "en_US-lessac-medium"), engine.voices());
    }

    @Test
    void requestTimeoutIsConfigurable() throws Exception {
        wm.stubFor(post("/synthesize").willReturn(aResponse().withStatus(200).withBody(TestAudio.sineWav(0.1)).withFixedDelay(1500)));

        PiperHttpTtsEngine impatient = new PiperHttpTtsEngine(wm.baseUrl(), 2, Duration.ofMillis(5), Duration.ofMillis(300));
        Exception ex = assertThrows(Exception.class, () -> impatient.synthesize("x", new VoiceConfig("v", 1.0)));
        assertTrue(ex.getMessage().contains("timed out"), ex.getMessage());
        wm.verify(2, postRequestedFor(urlEqualTo("/synthesize")));

        PiperHttpTtsEngine patient = new PiperHttpTtsEngine(wm.baseUrl(), 1, Duration.ofMillis(5), Duration.ofSeconds(5));
        assertTrue(Wav.looksLikeWav(patient.synthesize("x", new VoiceConfig("v", 1.0))));
    }
}
