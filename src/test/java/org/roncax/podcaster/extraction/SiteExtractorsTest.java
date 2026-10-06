package org.roncax.podcaster.extraction;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.http.HttpFetcher;
import org.roncax.podcaster.support.Fixtures;

/** Real pages saved 2026-10-06. */
class SiteExtractorsTest {
    ContentExtractionService service = new ContentExtractionService(
            new HttpFetcher("Test", Duration.ofSeconds(1), Duration.ZERO, 1, Duration.ofMillis(1)),
            List.of(new DefaultContentExtractor(), new AnsaContentExtractor(),
                    new IlFoglioContentExtractor(), new IlManifestoContentExtractor(), new InternazionaleContentExtractor()));

    private String extract(String url, String fixture) {
        return service.extract(url, Fixtures.bytes(fixture)).orElseThrow();
    }

    @Test
    void ilFoglioFreeArticleHasLeadAndFullBody() {
        String text = extract("https://www.ilfoglio.it/esteri/2026/10/06/news/putin-testa-gli-europei--409828", "ilfoglio-article-free.html");
        assertTrue(text.startsWith("Sciami di droni russi contro le navi commerciali"), text.substring(0, 80));
        assertTrue(text.contains("\n\nIl Mar Nero è diventato poco sicuro e poco navigabile"));
        assertTrue(text.length() > 4000, "length " + text.length());
        assertFalse(text.contains("Cosmopolitics"), "author bio must not leak in");
    }

    @Test
    void ilFoglioPaywalledArticleUsesFullTextInPage() {
        String text = extract("https://www.ilfoglio.it/milano/2026/10/06/news/meazza--1", "ilfoglio-article-paywalled.html");
        assertTrue(text.startsWith("Il Tar della Lombardia respinge"), text.substring(0, 80));
        assertTrue(text.contains("La Grande funzione urbana San Siro"));
        assertTrue(text.length() > 1500, "length " + text.length());
    }

    @Test
    void ilManifestoUsesDeckAndAbstractOnly() {
        String text = extract("https://ilmanifesto.it/unita-a-sinistra-solo-dieci-giorni-per-decidere", "ilmanifesto-article.html");
        assertTrue(text.startsWith("L’attività parlamentare passa alla «Deputazione permanente»"), text);
        assertTrue(text.contains("\n\nLa sinistra spagnola è in crisi di unità"), text);
        assertFalse(text.contains("Registrati"), text);
        assertFalse(text.contains("Abbonamento"), text);
    }

    @Test
    void ddayWorksWithGenericExtractor() {
        String text = extract("https://www.dday.it/redazione/59004/le-beats-solo-4", "dday-article.html");
        assertTrue(text.startsWith("Le Beats Solo 4 sono cuffie wireless"), text.substring(0, 80));
    }

    @Test
    void internazionaleCollectsAllTextBlocks() {
        String text = extract("https://www.internazionale.it/opinione/pierre-haski/2026/10/06/elezioni-spagna-europa", "internazionale-article.html");
        assertTrue(text.startsWith("Ancora una volta l’Europa si gioca il futuro"), text.substring(0, 80));
        assertTrue(text.contains("Questi partiti si sono pronunciati anche a favore"), "last block included");
        assertTrue(text.length() > 3000, "length " + text.length());
        assertFalse(text.contains("Internazionale pubblica ogni settimana"), "boilerplate note must not leak in");
    }
}
