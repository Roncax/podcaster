package org.roncax.podcaster.ingestion;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.roncax.podcaster.http.HttpFetcher;
import org.roncax.podcaster.support.Fixtures;

class RssSourceConnectorTest {
    static WireMockServer wm;
    RssSourceConnector connector = new RssSourceConnector(
            new HttpFetcher("Test", Duration.ofSeconds(5), Duration.ZERO, 1, Duration.ofMillis(1)));

    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }
    @BeforeEach void reset() { wm.resetAll(); }

    private SourceConfig feed(String path, byte[] body) {
        wm.stubFor(get(path).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/rss+xml").withBody(body)));
        return new SourceConfig(Map.of("url", wm.baseUrl() + path));
    }

    @Test
    void parsesAnsaFeed() throws Exception {
        List<RawItem> items = connector.fetch(feed("/ansa", Fixtures.bytes("ansa-feed.xml")),
                Instant.parse("2026-10-05T12:00:00Z"));
        assertEquals(26, items.size());
        RawItem first = items.get(0);
        assertEquals("Deepfake audio, per realizzarli servono tre secondi di voce vera e 30 euro", first.title());
        assertEquals(Instant.parse("2026-10-05T15:56:49Z"), first.publishedAt());
        assertTrue(first.url().startsWith("https://www.ansa.it/canale_tecnologia/notizie/cybersecurity/"));
        assertTrue(first.summary().startsWith("Meloni registra la voce"), first.summary());
        assertNull(first.fullText());
    }

    @Test
    void parsesIlPostFeedWithEmptyFields() throws Exception {
        List<RawItem> items = connector.fetch(feed("/ilpost", Fixtures.bytes("ilpost-italia-feed.xml")),
                Instant.parse("2026-10-04T00:00:00Z"));
        assertEquals(7, items.size());
        RawItem first = items.get(0);
        assertEquals("Venerdì 9 ottobre è previsto uno sciopero dei mezzi pubblici a Milano", first.title());
        assertEquals("https://www.ilpost.it/2026/10/05/sciopero-atm-milano-como/", first.url());
        assertNull(first.author());
        assertNull(first.summary());
    }

    @Test
    void itemsWithoutDateAreKept() throws Exception {
        String rss = """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0"><channel><title>t</title><link>http://x</link><description>d</description>
                <item><title>Undated story</title><link>http://x/undated</link><description>Body</description></item>
                </channel></rss>""";
        List<RawItem> items = connector.fetch(feed("/undated", rss.getBytes()), Instant.now());
        assertEquals(1, items.size());
        assertNull(items.get(0).publishedAt());
        assertEquals("Body", items.get(0).summary());
    }

    @Test
    void missingUrlIsRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> connector.fetch(new SourceConfig(Map.of()), Instant.now()));
        assertTrue(ex.getMessage().contains("url"));
    }

    @Test
    void validateRequiresUrl() {
        assertEquals(List.of("rss sources need config.url"), connector.validate(new SourceConfig(Map.of())));
        assertTrue(connector.validate(new SourceConfig(Map.of("url", "https://x/feed"))).isEmpty());
    }
}
