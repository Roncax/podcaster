package org.roncax.podcaster.extraction;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.http.HttpFetcher;
import org.roncax.podcaster.support.Fixtures;

class ContentExtractionTest {
    ContentExtractionService service = new ContentExtractionService(
            new HttpFetcher("Test", Duration.ofSeconds(1), Duration.ZERO, 1, Duration.ofMillis(1)),
            List.of(new DefaultContentExtractor(), new AnsaContentExtractor()));

    private static Document parse(String fixture, String url) throws Exception {
        return Jsoup.parse(new ByteArrayInputStream(Fixtures.bytes(fixture)), null, url);
    }

    @Test
    void ansaExtractorReturnsCleanBody() throws Exception {
        String text = new AnsaContentExtractor().extract(parse("ansa-article.html", "https://www.ansa.it/a.html")).orElseThrow();
        assertTrue(text.startsWith("Una traccia vocale sintetica"), text.substring(0, 60));
        assertTrue(text.contains("security.org"));
        assertFalse(text.contains("Riproduzione riservata"));
        assertTrue(text.contains("\n\n"), "paragraphs are separated by blank lines");
    }

    @Test
    void defaultExtractorFindsIlPostBody() throws Exception {
        String text = new DefaultContentExtractor().extract(parse("ilpost-article.html", "https://www.ilpost.it/a/")).orElseThrow();
        assertTrue(text.startsWith("Venerdì 9 ottobre è previsto uno sciopero"), text.substring(0, 60));
        assertTrue(text.contains("52 per cento"));
        assertFalse(text.contains("Tag:"));
    }

    @Test
    void serviceUsesSiteExtractorForAnsaHost() {
        String text = service.extract("https://www.ansa.it/sito/x.html", Fixtures.bytes("ansa-article.html")).orElseThrow();
        assertFalse(text.contains("Riproduzione riservata"));
    }

    @Test
    void serviceFallsBackToDefaultExtractor() {
        String text = service.extract("https://www.ilpost.it/2026/10/05/x/", Fixtures.bytes("ilpost-article.html")).orElseThrow();
        assertTrue(text.contains("funicolare Como-Brunate"));
    }

    @Test
    void pagesWithoutArticleTextYieldEmpty() {
        byte[] html = "<html><body><nav><p>Menu</p></nav><p>Short.</p></body></html>".getBytes();
        assertTrue(service.extract("https://example.com/", html).isEmpty());
    }
}
