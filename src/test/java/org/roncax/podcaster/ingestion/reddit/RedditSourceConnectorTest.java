package org.roncax.podcaster.ingestion.reddit;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.extraction.ContentExtractionService;
import org.roncax.podcaster.http.FetchException;
import org.roncax.podcaster.http.HttpFetcher;
import org.roncax.podcaster.ingestion.RawItem;
import org.roncax.podcaster.ingestion.SourceConfig;

class RedditSourceConnectorTest {
    static final String LONG = "Testo lungo. ".repeat(20); // 260 chars
    static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");

    static class FakeClient implements RedditClient {
        List<RedditPost> posts = new ArrayList<>();
        List<String> commentCalls = new ArrayList<>();
        boolean failComments;

        @Override public List<RedditPost> listing(String subreddit, String window, int limit) {
            return posts.subList(0, Math.min(limit, posts.size()));
        }

        @Override public List<String> topComments(String postId, int n) throws Exception {
            commentCalls.add(postId);
            if (failComments) throw new FetchException("HTTP 429");
            return List.of("Commento A di " + postId, "Commento B").subList(0, Math.min(n, 2));
        }
    }

    static class FakeExtraction extends ContentExtractionService {
        FakeExtraction() { super(new HttpFetcher("t", Duration.ofSeconds(1), Duration.ZERO, 1, Duration.ZERO), List.of()); }
        List<String> extracted = new ArrayList<>();
        @Override public Optional<String> extractUntrusted(String url) throws FetchException {
            extracted.add(url);
            if (url.contains("internal")) throw new org.roncax.podcaster.http.BlockedUrlException("Refusing to fetch non-public URL " + url);
            return url.contains("article") ? Optional.of("Article text of " + url) : Optional.empty();
        }
    }

    FakeClient client = new FakeClient();
    FakeExtraction extraction = new FakeExtraction();
    RedditSourceConnector connector = new RedditSourceConnector(client, extraction);

    static RedditPost link(String id, String url) { return new RedditPost(id, "https://reddit/t/" + id, "Link " + id, "u", NOW, url, null, false); }
    static RedditPost text(String id, String body) { return new RedditPost(id, "https://reddit/t/" + id, "Text " + id, "u", NOW, null, body, false); }
    static RedditPost media(String id, String body) { return new RedditPost(id, "https://reddit/t/" + id, "Img " + id, "u", NOW, null, body, true); }

    private List<RawItem> fetch(Map<String, String> extra) throws Exception {
        Map<String, String> cfg = new HashMap<>(Map.of("subreddit", "italy"));
        cfg.putAll(extra);
        return connector.fetch(new SourceConfig(cfg), NOW.minus(Duration.ofDays(1)));
    }

    @Test
    void linkPostUsesArticleAndKeepsThreadLink() throws Exception {
        client.posts.add(link("a", "https://news.example/article-1"));
        RawItem item = fetch(Map.of("commentPosts", "0")).get(0);
        assertEquals("https://news.example/article-1", item.url());
        assertEquals("Article text of https://news.example/article-1", item.fullText());
        assertEquals("https://reddit/t/a", item.discussionUrl());
        assertTrue(connector.providesFullText());
    }

    @Test
    void textAndMediaPostsNeedEnoughText() throws Exception {
        client.posts.addAll(List.of(text("short", "Troppo corto"), text("long", LONG), media("img", null), media("imgtext", LONG)));
        List<RawItem> items = fetch(Map.of("commentPosts", "0"));
        assertEquals(List.of("https://reddit/t/long", "https://reddit/t/imgtext"), items.stream().map(RawItem::url).toList());
        assertEquals(LONG.trim(), items.get(0).fullText());
        assertTrue(items.get(0).summary().length() <= 500);
    }

    @Test
    void onlyMediaPostsGiveNoItems() throws Exception {
        client.posts.addAll(List.of(media("m1", null), media("m2", "meme")));
        assertTrue(fetch(Map.of()).isEmpty());
    }

    @Test
    void commentsForFirstItemsOnly() throws Exception {
        client.posts.addAll(List.of(media("skip", null), link("a", "https://x/article-a"), text("b", LONG), link("c", "https://x/article-c")));
        List<RawItem> items = fetch(Map.of("commentPosts", "2", "topComments", "1"));
        assertEquals(List.of("a", "b"), client.commentCalls);
        assertTrue(items.get(0).fullText().endsWith("\n\nReddit discussion (top comments):\n- Commento A di a"), items.get(0).fullText());
        assertFalse(items.get(2).fullText().contains("Reddit discussion"));
    }

    @Test
    void commentFailureDisablesCommentsButKeepsPosts() throws Exception {
        client.failComments = true;
        client.posts.addAll(List.of(link("a", "https://x/article-a"), link("b", "https://x/article-b")));
        List<RawItem> items = fetch(Map.of());
        assertEquals(2, items.size());
        assertEquals(List.of("a"), client.commentCalls);
    }

    @Test
    void linkPostWithoutExtractableTextFallsBackToSelfText() throws Exception {
        client.posts.add(new RedditPost("p", "https://reddit/t/p", "Paywalled", "u", NOW, "https://x/paywalled", "Riassunto del post", false));
        RawItem item = fetch(Map.of("commentPosts", "0")).get(0);
        assertEquals("Riassunto del post", item.fullText());
    }

    @Test
    void oldPostsAreDropped() throws Exception {
        client.posts.add(new RedditPost("old", "https://reddit/t/old", "Old", "u", NOW.minus(Duration.ofDays(3)), "https://x/article-old", null, false));
        assertTrue(fetch(Map.of()).isEmpty());
    }

    @Test
    void validatesConfig() {
        assertEquals(List.of("reddit sources need config.subreddit (e.g. subreddit=italy)"), connector.validate(new SourceConfig(Map.of())));
        List<String> errors = connector.validate(new SourceConfig(Map.of("subreddit", "r/italy!", "window", "year",
                "maxPosts", "50", "commentPosts", "x", "topComments", "0")));
        assertEquals(5, errors.size(), errors.toString());
        assertTrue(connector.validate(new SourceConfig(Map.of("subreddit", "italy", "maxPosts", "3", "commentPosts", "4"))).get(0)
                .contains("commentPosts"));
        assertTrue(connector.validate(new SourceConfig(Map.of("subreddit", "AskEurope", "window", "week"))).isEmpty());
    }

    @Test
    void linksToNonPublicHostsAreSkipped() throws Exception {
        client.posts.addAll(List.of(link("bad", "http://internal.lan/admin"), link("good", "https://x/article-ok")));
        List<RawItem> items = fetch(Map.of("commentPosts", "0"));
        assertEquals(List.of("https://x/article-ok"), items.stream().map(RawItem::url).toList());
    }

    @Test
    void knownUrlsSkipExtractionAndComments() throws Exception {
        client.posts.addAll(List.of(link("old", "https://x/article-old"), link("new", "https://x/article-new")));
        Map<String, String> cfg = new HashMap<>(Map.of("subreddit", "italy", "commentPosts", "1"));
        List<RawItem> items = connector.fetch(new SourceConfig(cfg), NOW.minus(Duration.ofDays(1)),
                url -> url.equals("https://x/article-old"));
        assertEquals(List.of("https://x/article-new"), items.stream().map(RawItem::url).toList());
        assertEquals(List.of("https://x/article-new"), extraction.extracted);
        assertEquals(List.of("new"), client.commentCalls);
    }
}
