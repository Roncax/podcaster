package org.roncax.podcaster.support;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.Map;

public class WireMockResource implements QuarkusTestResourceLifecycleManager {
    private WireMockServer server;

    @Override
    public Map<String, String> start() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
        installDefaults(server);
        return Map.of(
                "podcaster.tts.piper-url", server.baseUrl(),
                "podcaster.telegram.api-url", server.baseUrl());
    }

    public static void installDefaults(WireMockServer server) {
        server.resetAll();
        server.stubFor(get("/voices").willReturn(okJson("{\"it_IT-paola-medium\":{},\"en_US-lessac-medium\":{}}")));
        server.stubFor(post("/synthesize").willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "audio/wav").withBody(TestAudio.sineWav(0.5))));
        server.stubFor(post("/bottest-token/sendMessage").willReturn(okJson("{\"ok\":true}")));
    }

    @Override
    public void inject(TestInjector injector) {
        injector.injectIntoFields(server, new TestInjector.AnnotatedAndMatchesType(InjectWireMock.class, WireMockServer.class));
    }

    @Override
    public void stop() {
        if (server != null) server.stop();
    }
}
