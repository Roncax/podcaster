package org.roncax.podcaster.notify;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.domain.Run;
import org.roncax.podcaster.domain.RunStage;
import org.roncax.podcaster.domain.Show;
import org.roncax.podcaster.support.InjectWireMock;
import org.roncax.podcaster.support.WireMockResource;

@QuarkusTest
@WithTestResource(WireMockResource.class)
@WithTestResource(org.roncax.podcaster.support.PostgresResource.class)
class TelegramNotifierTest {
    @InjectWireMock WireMockServer wm;
    @Inject Notifier notifier;

    @BeforeEach
    void reset() { WireMockResource.installDefaults(wm); }

    static Show show() {
        Show s = new Show();
        s.id = 7L;
        s.name = "Daily";
        return s;
    }

    @Test
    void sendsRunFailure() {
        Run run = new Run();
        run.id = 99L;
        run.stage = RunStage.TTS;
        notifier.runFailed(show(), run, "Piper unreachable");
        wm.verify(postRequestedFor(urlEqualTo("/bottest-token/sendMessage"))
                .withRequestBody(matchingJsonPath("$.chat_id", equalTo("42")))
                .withRequestBody(matchingJsonPath("$.text", containing("TTS")))
                .withRequestBody(matchingJsonPath("$.text", containing("Piper unreachable")))
                .withRequestBody(matchingJsonPath("$.text", containing("Daily"))));
    }

    @Test
    void sendsWarning() {
        notifier.warning(show(), "1 of 3 sources failed");
        wm.verify(postRequestedFor(urlEqualTo("/bottest-token/sendMessage"))
                .withRequestBody(matchingJsonPath("$.text", containing("1 of 3 sources failed"))));
    }

    @Test
    void telegramErrorsAreSwallowed() {
        wm.stubFor(post("/bottest-token/sendMessage").willReturn(serverError()));
        assertDoesNotThrow(() -> notifier.warning(show(), "x"));
    }
}
