package org.roncax.podcaster.ingestion;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.domain.Item;
import org.roncax.podcaster.domain.Show;
import org.roncax.podcaster.domain.Source;
import org.roncax.podcaster.support.*;

@QuarkusTest
@WithTestResource(WireMockResource.class)
@WithTestResource(org.roncax.podcaster.support.PostgresResource.class)
class IngestionServiceTest {
    @InjectWireMock WireMockServer wm;
    @Inject IngestionService ingestion;

    @BeforeEach
    void setup() {
        TestData.cleanDb();
        WireMockResource.installDefaults(wm);
    }

    /** RSS feed whose items link to WireMock paths; each item is "title|path". */
    private String rss(String... items) {
        String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.now().atOffset(ZoneOffset.UTC));
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>t</title><link>http://x</link><description>d</description>");
        for (String it : items) {
            String[] p = it.split("\\|");
            sb.append("<item><title>").append(p[0]).append("</title><link>").append(wm.baseUrl()).append(p[1])
              .append("</link><pubDate>").append(date).append("</pubDate><description>Teaser</description></item>");
        }
        return sb.append("</channel></rss>").toString();
    }

    private void stubFeed(String path, String body) {
        wm.stubFor(get(path).willReturn(okXml(body)));
    }

    private void stubArticle(String path) {
        wm.stubFor(get(path).willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/html")
                .withBody(Fixtures.bytes("ilpost-article.html"))));
    }

    private Instant since() { return Instant.now().minus(1, ChronoUnit.DAYS); }

    @Test
    void ingestsAndExtractsFullText() {
        Show show = TestData.show("ing1");
        TestData.source(show.id, wm.baseUrl() + "/feed1");
        stubFeed("/feed1", rss("First story|/a1", "Second story|/a2"));
        stubArticle("/a1");
        stubArticle("/a2");

        IngestionReport report = ingestion.ingest(show.id, since());

        assertEquals(2, report.newItems());
        assertTrue(report.errors().isEmpty());
        List<Item> items = QuarkusTransaction.requiringNew().call(() -> Item.<Item>list("showId", show.id));
        assertEquals(2, items.size());
        assertTrue(items.get(0).fullText.contains("sciopero"));
        assertEquals("Teaser", items.get(0).summary);
    }

    @Test
    void secondIngestAddsNothingAndDoesNotRefetchArticles() {
        Show show = TestData.show("ing2");
        TestData.source(show.id, wm.baseUrl() + "/feed2");
        stubFeed("/feed2", rss("Story|/b1"));
        stubArticle("/b1");

        assertEquals(1, ingestion.ingest(show.id, since()).newItems());
        assertEquals(0, ingestion.ingest(show.id, since()).newItems());
        wm.verify(1, getRequestedFor(urlEqualTo("/b1")));
    }

    @Test
    void sameStoryUnderDifferentUrlIsDeduplicated() {
        Show show = TestData.show("ing3");
        TestData.source(show.id, wm.baseUrl() + "/feed3");
        stubFeed("/feed3", rss("Same story|/c1", "Same story|/c1-copy"));
        stubArticle("/c1");
        stubArticle("/c1-copy");

        assertEquals(1, ingestion.ingest(show.id, since()).newItems());
    }

    @Test
    void partialSourceFailureIsReportedButOthersSucceed() {
        Show show = TestData.show("ing4");
        TestData.source(show.id, wm.baseUrl() + "/feed4");
        Source broken = TestData.source(show.id, wm.baseUrl() + "/broken");
        stubFeed("/feed4", rss("Good story|/d1"));
        stubArticle("/d1");
        wm.stubFor(get("/broken").willReturn(serverError()));

        IngestionReport report = ingestion.ingest(show.id, since());

        assertEquals(1, report.newItems());
        assertEquals(1, report.errors().size());
        assertFalse(report.allFailed());
        Source reloaded = QuarkusTransaction.requiringNew().call(() -> Source.<Source>findById(broken.id));
        assertNotNull(reloaded.lastError);
    }

    @Test
    void allSourcesFailing() {
        Show show = TestData.show("ing5");
        TestData.source(show.id, wm.baseUrl() + "/broken5");
        wm.stubFor(get("/broken5").willReturn(notFound()));

        IngestionReport report = ingestion.ingest(show.id, since());

        assertTrue(report.allFailed());
        assertEquals(0, report.newItems());
    }

    @Test
    void articleExtractionFailureKeepsItemWithSummary() {
        Show show = TestData.show("ing6");
        TestData.source(show.id, wm.baseUrl() + "/feed6");
        stubFeed("/feed6", rss("Paywalled|/gone"));
        wm.stubFor(get("/gone").willReturn(forbidden()));

        assertEquals(1, ingestion.ingest(show.id, since()).newItems());
        Item item = QuarkusTransaction.requiringNew().call(() -> Item.<Item>find("showId", show.id).firstResult());
        assertNull(item.fullText);
        assertEquals("Teaser", item.bestText());
    }

    @Test
    void itemsWithNonHttpLinksAreNotStored() {
        Show show = TestData.show("ing-js");
        TestData.source(show.id, wm.baseUrl() + "/feedjs");
        String date = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(java.time.ZonedDateTime.now());
        stubFeed("/feedjs", "<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>t</title><link>http://x</link><description>d</description>"
                + "<item><title>Evil</title><link>javascript:alert(document.cookie)</link><pubDate>" + date + "</pubDate><description>x</description></item>"
                + "<item><title>Good</title><link>" + wm.baseUrl() + "/good</link><pubDate>" + date + "</pubDate><description>x</description></item>"
                + "</channel></rss>");
        stubArticle("/good");

        ingestion.ingest(show.id, since());

        List<Item> items = QuarkusTransaction.requiringNew().call(() -> Item.<Item>list("showId", show.id));
        assertEquals(List.of("Good"), items.stream().map(i -> i.title).toList());
    }
}
