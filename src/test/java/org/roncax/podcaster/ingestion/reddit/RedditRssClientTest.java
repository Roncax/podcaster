package org.roncax.podcaster.ingestion.reddit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.*;
import org.roncax.podcaster.http.FetchException;
import org.roncax.podcaster.http.HttpFetcher;
import org.roncax.podcaster.support.Fixtures;
import org.roncax.podcaster.support.RedditFeeds;

class RedditRssClientTest {
    static WireMockServer wm;
    RedditRssClient client;

    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }

    @BeforeEach
    void reset() {
        wm.resetAll();
        client = new RedditRssClient(new HttpFetcher("Test", Duration.ofSeconds(5), Duration.ZERO, 3, Duration.ofMillis(1)),
                wm.baseUrl(), Duration.ZERO);
    }

    private void stubListing(byte[] body) {
        wm.stubFor(get(urlPathEqualTo("/r/italy/top/.rss")).withQueryParam("t", equalTo("day"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/atom+xml").withBody(body)));
    }

    @Test
    void parsesRealSubredditFeed() throws Exception {
        stubListing(Fixtures.bytes("reddit-italy-top-day.xml"));

        List<RedditPost> posts = client.listing("italy", "day", 25);

        assertEquals(12, posts.size());
        assertEquals(4, posts.stream().filter(p -> p.linkUrl() != null).count(), "link posts");
        assertEquals(2, posts.stream().filter(RedditPost::media).count(), "image posts");
        RedditPost pd = posts.get(1);
        assertEquals("1wz07mz", pd.id());
        assertEquals("sr_local", pd.author());
        assertTrue(pd.linkUrl().startsWith("https://www.quotidianosanita.it/"), pd.linkUrl());
        assertTrue(pd.threadUrl().contains("/comments/1wz07mz/"));
        assertEquals(Instant.parse("2026-10-06T11:31:56Z"), pd.publishedAt());
        assertTrue(pd.selfText().startsWith("I punti chiave"), pd.selfText());
        assertNull(posts.get(3).selfText(), "Il Post link post has no self-text");
        RedditPost caffe = posts.get(6);
        assertNull(caffe.linkUrl());
        assertFalse(caffe.media());
        wm.verify(getRequestedFor(urlPathEqualTo("/r/italy/top/.rss")).withQueryParam("limit", equalTo("25")));
    }

    @Test
    void appliesLimit() throws Exception {
        stubListing(Fixtures.bytes("reddit-italy-top-day.xml"));
        assertEquals(3, client.listing("italy", "day", 3).size());
    }

    @Test
    void crosspostIsTextPost() throws Exception {
        String cross = RedditFeeds.entry("x1", "Crosspost", "https://www.reddit.com/r/other/comments/abc/orig/", "Testo", Instant.now());
        String gallery = RedditFeeds.entry("x2", "Gallery", "https://www.reddit.com/gallery/zzz", null, Instant.now());
        stubListing(RedditFeeds.listing(cross, gallery).getBytes());

        List<RedditPost> posts = client.listing("italy", "day", 10);

        assertNull(posts.get(0).linkUrl());
        assertFalse(posts.get(0).media());
        assertTrue(posts.get(1).media());
    }

    @Test
    void unknownSubredditIsNotFound() {
        wm.stubFor(get(urlPathEqualTo("/r/nope/top/.rss")).willReturn(notFound()));
        FetchException ex = assertThrows(FetchException.class, () -> client.listing("nope", "day", 10));
        assertEquals("Subreddit r/nope not found or not public", ex.getMessage());
    }

    @Test
    void htmlInsteadOfAtomIsNotFound() {
        wm.stubFor(get(urlPathEqualTo("/r/italy/top/.rss")).willReturn(okForContentType("text/html", "<html><body>blocked</body></html>")));
        FetchException ex = assertThrows(FetchException.class, () -> client.listing("italy", "day", 10));
        assertEquals("Subreddit r/italy not found or not public", ex.getMessage());
    }

    @Test
    void commentsUseIdBasedUrl() throws Exception {
        wm.stubFor(get(urlPathEqualTo("/comments/1wz07mz/.rss")).withQueryParam("sort", equalTo("top")).withQueryParam("limit", equalTo("3"))
                .willReturn(okXml(RedditFeeds.comments("1wz07mz", "<p>Uno</p>", "<p>Due</p>"))));
        assertEquals(List.of("Uno", "Due"), client.topComments("1wz07mz", 2));
    }

    @Test
    void commentsArePlainTextAndTruncated() throws Exception {
        String longComment = "<p>" + "parola ".repeat(200) + "</p>";
        wm.stubFor(get(urlPathEqualTo("/comments/p1/.rss")).willReturn(okXml(RedditFeeds.comments("p1",
                "<p>Sanità &amp; <a href=\"https://x\">territorio</a>   ok</p>", longComment))));

        List<String> comments = client.topComments("p1", 3);

        assertEquals("Sanità & territorio ok", comments.get(0));
        assertEquals(500, comments.get(1).length());
    }

    @Test
    void rateLimitedCommentsFailWithoutRetry() {
        wm.stubFor(get(urlPathEqualTo("/comments/p2/.rss")).willReturn(aResponse().withStatus(429)));
        assertThrows(FetchException.class, () -> client.topComments("p2", 3));
        wm.verify(1, getRequestedFor(urlPathEqualTo("/comments/p2/.rss")));
    }

    @Test
    void throttlesRedditRequests() throws Exception {
        RedditRssClient slow = new RedditRssClient(new HttpFetcher("Test", Duration.ofSeconds(5), Duration.ZERO, 1, Duration.ofMillis(1)),
                wm.baseUrl(), Duration.ofMillis(150));
        stubListing(Fixtures.bytes("reddit-italy-top-day.xml"));
        long start = System.nanoTime();
        slow.listing("italy", "day", 1);
        slow.listing("italy", "day", 1);
        slow.listing("italy", "day", 1);
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() >= 290, "three requests need at least two delays");
    }
}
