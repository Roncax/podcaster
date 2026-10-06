# Reddit Connector Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A credential-free `reddit` source connector that ingests a subreddit's top posts of the day (link posts with article text, substantial text posts) plus best-effort top comments, storing the Reddit thread link with each item.

**Architecture:** New package `org.roncax.podcaster.ingestion.reddit`. A `RedditClient` interface hides access; `RedditRssClient` reads Reddit's public Atom feeds through `HttpFetcher`, with its own request throttle. `RedditSourceConnector` classifies posts, extracts linked articles, appends comments and returns `RawItem`s with a new `discussionUrl`. Connector-specific config validation moves into `SourceConnector.validate`.

**Tech Stack:** Java 25, Quarkus 3.40.1, Rome 2.1.0 (Atom), jsoup, Flyway, WireMock + embedded PostgreSQL in tests.

**Spec:** `docs/superpowers/specs/2026-10-06-reddit-connector-design.md` (parent: `docs/superpowers/specs/2026-10-06-podcaster-design.md`)

## Global Constraints

- Branch `feature/reddit-connector`. Run Maven with JDK 25: `export JAVA_HOME=$HOME/.jdks/temurin-25 PATH=$HOME/.jdks/temurin-25/bin:$HOME/.local/bin:$PATH`.
- Connector type is exactly `reddit`. Config keys: `subreddit` (required, `[A-Za-z0-9_]{2,21}`), `window` (`hour|day|week|month`, default `day`), `maxPosts` (1–25, default 10), `commentPosts` (0–maxPosts, default 5), `topComments` (1–10, default 3).
- Text posts and media posts need ≥ 200 chars of self-text; summary ≤ 500 chars; each comment ≤ 500 chars; comments block header is exactly `Reddit discussion (top comments):`.
- Reddit requests are throttled by `podcaster.reddit.request-delay` (default `6s`, `0s` in tests); base URL `podcaster.reddit.base-url` (default `https://www.reddit.com`).
- Comments: one attempt, no retries; any failure disables comments for the rest of that fetch.
- No real network in tests: every Reddit and article URL used by tests points to WireMock (the saved r/italy fixture is only parsed, never followed).
- All `@QuarkusTest` classes carry `@WithTestResource(WireMockResource.class)` and `@WithTestResource(org.roncax.podcaster.support.PostgresResource.class)`.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01LbcoMupq1VXnfzdqroFmyp`.

## Review Focus

1. **Reddit answers 200 with an HTML page** (consent wall, "blocked", login page) instead of Atom → the source fails with "Subreddit r/<sub> not found or not public", not a parser stack trace. Test: Task 2 `RedditRssClientTest.htmlInsteadOfAtomIsNotFound`.
2. **Thread URLs with accented characters** (`…/le_proposte_del_pd_per_la_sanità…`) → the comments request must be a valid ASCII URL; it is built from the post id (`/comments/<id>/.rss`), not the thread URL. Test: Task 2 `RedditRssClientTest.commentsUseIdBasedUrl`.
3. **A subreddit whose top posts are all images or memes** → zero items and no failure. Test: Task 3 `RedditSourceConnectorTest.onlyMediaPostsGiveNoItems`.
4. **Comment HTML with entities, links and huge length** → plain text, entities decoded, truncated to 500 chars. Test: Task 2 `RedditRssClientTest.commentsArePlainTextAndTruncated`.
5. **A crosspost** (`[link]` points to another reddit.com thread) → treated as a text post, never fetched as an "article". Test: Task 2 `RedditRssClientTest.crosspostIsTextPost`.

**Deviation from the spec, decided here:** `RedditClient.topComments` takes the **post id** (from the Atom `<id>` `t3_<id>`) instead of the thread URL, because thread URLs can contain non-ASCII characters (Review Focus 2). `RedditPost` therefore also carries `id`.

---

## File Structure

```
src/main/resources/db/migration/V3__item_discussion_url.sql
src/main/java/org/roncax/podcaster/ingestion/reddit/
  RedditPost.java              record (id, threadUrl, title, author, publishedAt, linkUrl, selfText, media)
  RedditClient.java            interface (listing, topComments)
  RedditRssClient.java         Atom implementation + throttle
  RedditSourceConnector.java   SourceConnector "reddit"
Modify:
  http/HttpFetcher.java                 get(url, attempts) overload
  ingestion/RawItem.java                + discussionUrl (6-arg constructor kept)
  ingestion/SourceConnector.java        + default validate(SourceConfig)
  ingestion/RssSourceConnector.java     validate(): url required
  ingestion/IngestionService.java       copy discussionUrl
  domain/Item.java                      + discussionUrl
  api/ShowService.java                  validateSource uses connector.validate
  config/PodcasterConfig.java           + Reddit section
  resources/application.yml             podcaster.reddit.*
Tests:
  support/RedditFeeds.java              builds Atom listing/comment feeds for WireMock
  support/WireMockResource.java         + podcaster.reddit.base-url
  http/HttpFetcherTest.java             + single-attempt test
  ingestion/RssSourceConnectorTest.java + validate test
  ingestion/reddit/RedditRssClientTest.java
  ingestion/reddit/RedditSourceConnectorTest.java
  ingestion/reddit/RedditIngestionTest.java
fixtures/reddit-italy-top-day.xml (already committed)
```

---

### Task 1: Foundations — single-attempt fetch, discussion URL, connector validation

**Files:**
- Create: `src/main/resources/db/migration/V3__item_discussion_url.sql`
- Modify: `src/main/java/org/roncax/podcaster/http/HttpFetcher.java`, `ingestion/RawItem.java`, `ingestion/SourceConnector.java`, `ingestion/RssSourceConnector.java`, `ingestion/IngestionService.java`, `domain/Item.java`, `api/ShowService.java`
- Test: `src/test/java/org/roncax/podcaster/http/HttpFetcherTest.java` (add test), `src/test/java/org/roncax/podcaster/ingestion/RssSourceConnectorTest.java` (add test)

**Interfaces:**
- Produces:
  - `HttpFetcher#get(String url, int attempts) → byte[]` (throws `FetchException`); `get(String url)` = `get(url, configuredAttempts)`.
  - `record RawItem(String url, String title, String author, Instant publishedAt, String summary, String fullText, String discussionUrl)` plus the 6-argument constructor (`discussionUrl = null`).
  - `SourceConnector#validate(SourceConfig) → List<String>` (default empty). `RssSourceConnector.validate` → `["rss sources need config.url"]` when `url` is blank.
  - `Item.discussionUrl` (`String`), column `items.discussion_url`.
  - `ShowService` validates sources through `connector.validate(new SourceConfig(config))`.

- [ ] **Step 1: Write the failing tests**

Add to `HttpFetcherTest`:

```java
    @Test
    void singleAttemptDoesNotRetry() {
        wm.stubFor(get("/once").willReturn(aResponse().withStatus(429)));
        assertThrows(FetchException.class, () -> fetcher.get(wm.baseUrl() + "/once", 1));
        wm.verify(1, getRequestedFor(urlEqualTo("/once")));
    }
```

Add to `RssSourceConnectorTest`:

```java
    @Test
    void validateRequiresUrl() {
        assertEquals(List.of("rss sources need config.url"), connector.validate(new SourceConfig(Map.of())));
        assertTrue(connector.validate(new SourceConfig(Map.of("url", "https://x/feed"))).isEmpty());
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -q test -Dtest='HttpFetcherTest,RssSourceConnectorTest'`
Expected: compilation FAIL (`get(String,int)` and `validate` not defined).

- [ ] **Step 3: Implement**

`HttpFetcher`: rename the body of `get(String url)` into a new method and delegate:

```java
    public byte[] get(String url) throws FetchException {
        return get(url, attempts);
    }

    public byte[] get(String url, int attempts) throws FetchException {
        // body of the former get(String url), unchanged except that
        // Retries.withBackoff(attempts, retryDelay, ...) now uses this parameter
    }
```

(The existing call `Retries.withBackoff(attempts, retryDelay, () -> {` already reads `attempts`; the parameter now shadows the field, which is the intent.)

`RawItem`:

```java
package org.roncax.podcaster.ingestion;

import java.time.Instant;

/** One fetched news item. {@code discussionUrl} is an optional link to a discussion (e.g. the Reddit thread). */
public record RawItem(String url, String title, String author, Instant publishedAt, String summary, String fullText,
                      String discussionUrl) {

    public RawItem(String url, String title, String author, Instant publishedAt, String summary, String fullText) {
        this(url, title, author, publishedAt, summary, fullText, null);
    }
}
```

`SourceConnector` — add:

```java
    /** Connector-specific config errors; empty when valid. Checked when a source is created or updated. */
    default java.util.List<String> validate(SourceConfig config) { return java.util.List.of(); }
```

`RssSourceConnector` — add:

```java
    @Override
    public java.util.List<String> validate(SourceConfig config) {
        return config.get("url").isPresent() ? java.util.List.of() : java.util.List.of("rss sources need config.url");
    }
```

`ShowService.validateSource` — replace the method body:

```java
    private void validateSource(SourceRequest r) {
        List<String> errors = new ArrayList<>();
        Optional<SourceConnector> connector = connectors.find(r.connectorType());
        if (connector.isEmpty()) {
            errors.add("Unknown connectorType '" + r.connectorType() + "'; available: " + connectors.types());
        } else {
            errors.addAll(connector.get().validate(new SourceConfig(r.config())));
        }
        throwIfInvalid(errors);
    }
```

(Remove the now-unused `RssSourceConnector` import if the compiler flags it; `java.util.*` already covers `Optional`.)

`V3__item_discussion_url.sql`:

```sql
alter table items add column discussion_url varchar(2000);
```

`Item` — add after `fullText`:

```java
    public String discussionUrl;
```

`IngestionService.save` — after `item.fullText = fullText;` add:

```java
            item.discussionUrl = raw.discussionUrl();
```

- [ ] **Step 4: Run tests**

Run: `./mvnw -q test -Dtest='HttpFetcherTest,RssSourceConnectorTest,ApiTest,IngestionServiceTest'`
Expected: PASS (`ApiTest.rejectsUnknownConnector` still sees "config.url").

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Add single-attempt fetch, item discussion URL and per-connector config validation

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01LbcoMupq1VXnfzdqroFmyp"
```

---

### Task 2: Reddit RSS client

**Files:**
- Create: `src/main/java/org/roncax/podcaster/ingestion/reddit/{RedditPost,RedditClient,RedditRssClient}.java`
- Modify: `src/main/java/org/roncax/podcaster/config/PodcasterConfig.java`, `src/main/resources/application.yml`, `src/test/java/org/roncax/podcaster/support/WireMockResource.java`
- Create (test support): `src/test/java/org/roncax/podcaster/support/RedditFeeds.java`
- Test: `src/test/java/org/roncax/podcaster/ingestion/reddit/RedditRssClientTest.java`

**Interfaces:**
- Consumes: `HttpFetcher#get(String)`, `get(String,int)` (Task 1); `Fixtures.bytes`.
- Produces:
  - `record RedditPost(String id, String threadUrl, String title, String author, Instant publishedAt, String linkUrl, String selfText, boolean media)` — `id` without `t3_`; `author` without `/u/`; `linkUrl` = external article or null; `selfText` = plain text (paragraphs joined by blank lines) or null.
  - `interface RedditClient { List<RedditPost> listing(String subreddit, String window, int limit) throws Exception; List<String> topComments(String postId, int n) throws Exception; }`
  - `RedditRssClient` (`@ApplicationScoped`, public test constructor `(HttpFetcher, String baseUrl, Duration delay)`): listing URL `<base>/r/<sub>/top/.rss?t=<window>&limit=<limit>`; comments URL `<base>/comments/<id>/.rss?sort=top&limit=<n+1>`; listing 404/403/non-Atom → `FetchException("Subreddit r/<sub> not found or not public")`; comments use 1 attempt.
  - `PodcasterConfig.reddit()` → `Reddit { Duration requestDelay(); String baseUrl(); }`.
  - Test: `RedditFeeds.listing(String... entries)`, `RedditFeeds.entry(String id, String title, String link, String selfText, Instant published)`, `RedditFeeds.comments(String postId, String... commentHtml)`, `RedditFeeds.thread(String id) → String`.

- [ ] **Step 1: Add config**

`PodcasterConfig` — add `Reddit reddit();` next to `Telegram telegram();` and:

```java
    interface Reddit { Duration requestDelay(); String baseUrl(); }
```

`application.yml` — under `podcaster:` (after `telegram:` block):

```yaml
  reddit:
    request-delay: 6s
    base-url: https://www.reddit.com
```

and under `"%test": podcaster:` add:

```yaml
    reddit:
      request-delay: 0s
```

`WireMockResource.start()` — return a third entry:

```java
        return Map.of(
                "podcaster.tts.piper-url", server.baseUrl(),
                "podcaster.telegram.api-url", server.baseUrl(),
                "podcaster.reddit.base-url", server.baseUrl());
```

- [ ] **Step 2: Write `RedditFeeds` (test support)**

```java
package org.roncax.podcaster.support;

import java.time.Instant;

/** Builds Reddit-style Atom feeds (same shape as reddit.com/r/<sub>/top/.rss) for WireMock. */
public final class RedditFeeds {
    private RedditFeeds() {}

    public static String thread(String id) {
        return "https://www.reddit.com/r/italy/comments/" + id + "/slug/";
    }

    /** {@code link}: external URL for link posts, {@code thread(id)} for text posts, an i.redd.it URL for images. */
    public static String entry(String id, String title, String link, String selfText, Instant published) {
        String md = selfText == null ? "" : "<!-- SC_OFF --><div class=\"md\"><p>" + selfText + "</p></div><!-- SC_ON -->";
        String html = "<table><tr><td>" + md + " submitted by <a href=\"https://www.reddit.com/user/tester\"> /u/tester </a> <br/>"
                + "<span><a href=\"" + link + "\">[link]</a></span> <span><a href=\"" + thread(id) + "\">[comments]</a></span></td></tr></table>";
        return "<entry><author><name>/u/tester</name></author><id>t3_" + id + "</id>"
                + "<link href=\"" + thread(id) + "\" /><updated>" + published + "</updated><published>" + published + "</published>"
                + "<title>" + escape(title) + "</title><content type=\"html\">" + escape(html) + "</content></entry>";
    }

    public static String listing(String... entries) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><feed xmlns=\"http://www.w3.org/2005/Atom\">"
                + "<id>/r/italy/top/.rss</id><title>top</title><updated>2026-10-06T18:00:00+00:00</updated>"
                + String.join("", entries) + "</feed>";
    }

    /** First entry is the post itself (t3_), then one t1_ entry per comment. */
    public static String comments(String postId, String... commentHtml) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><feed xmlns=\"http://www.w3.org/2005/Atom\">"
                + "<id>/comments/" + postId + "</id><title>c</title><updated>2026-10-06T18:00:00+00:00</updated>"
                + "<entry><id>t3_" + postId + "</id><title>post</title><updated>2026-10-06T18:00:00+00:00</updated>"
                + "<link href=\"" + thread(postId) + "\" /><content type=\"html\">" + escape("<div class=\"md\"><p>post body</p></div>") + "</content></entry>");
        for (int i = 0; i < commentHtml.length; i++) {
            sb.append("<entry><id>t1_c").append(i).append("</id><title>comment</title><updated>2026-10-06T18:00:00+00:00</updated>")
              .append("<link href=\"").append(thread(postId)).append("c").append(i).append("/\" />")
              .append("<content type=\"html\">").append(escape("<!-- SC_OFF --><div class=\"md\">" + commentHtml[i] + "</div>")).append("</content></entry>");
        }
        return sb.append("</feed>").toString();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
```

- [ ] **Step 3: Write the failing test**

```java
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
```

- [ ] **Step 4: Run to verify failure**

Run: `./mvnw -q test -Dtest=RedditRssClientTest`
Expected: compilation FAIL (`RedditRssClient` not found).

- [ ] **Step 5: Implement `RedditPost`, `RedditClient`, `RedditRssClient`**

```java
package org.roncax.podcaster.ingestion.reddit;

import java.time.Instant;

/** A post from a subreddit listing. {@code linkUrl} is set only for posts linking to an external article. */
public record RedditPost(String id, String threadUrl, String title, String author, Instant publishedAt,
                         String linkUrl, String selfText, boolean media) {}
```

```java
package org.roncax.podcaster.ingestion.reddit;

import java.util.List;

/** Access to Reddit. Implemented over public Atom feeds today; an OAuth client can implement it later. */
public interface RedditClient {
    /** Top posts of the subreddit for the window, in score order, at most {@code limit}. */
    List<RedditPost> listing(String subreddit, String window, int limit) throws Exception;

    /** Up to {@code n} top comments of the post as plain text. May throw when rate-limited. */
    List<String> topComments(String postId, int n) throws Exception;
}
```

```java
package org.roncax.podcaster.ingestion.reddit;

import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.roncax.podcaster.config.PodcasterConfig;
import org.roncax.podcaster.http.FetchException;
import org.roncax.podcaster.http.HttpFetcher;

@ApplicationScoped
public class RedditRssClient implements RedditClient {
    private static final Set<String> MEDIA_HOSTS = Set.of("i.redd.it", "v.redd.it", "preview.redd.it", "i.imgur.com", "imgur.com");
    private static final int MAX_COMMENT_CHARS = 500;

    private final HttpFetcher fetcher;
    private final String baseUrl;
    private final Duration delay;
    private Instant nextAllowed = Instant.EPOCH;

    @Inject
    public RedditRssClient(HttpFetcher fetcher, PodcasterConfig config) {
        this(fetcher, config.reddit().baseUrl(), config.reddit().requestDelay());
    }

    public RedditRssClient(HttpFetcher fetcher, String baseUrl, Duration delay) {
        this.fetcher = fetcher;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.delay = delay;
    }

    @Override
    public List<RedditPost> listing(String subreddit, String window, int limit) throws Exception {
        String notFound = "Subreddit r/" + subreddit + " not found or not public";
        byte[] body;
        try {
            throttle();
            body = fetcher.get(baseUrl + "/r/" + subreddit + "/top/.rss?t=" + window + "&limit=" + limit);
        } catch (FetchException e) {
            if (e.getMessage() != null && (e.getMessage().contains("HTTP 404") || e.getMessage().contains("HTTP 403"))) {
                throw new FetchException(notFound, e);
            }
            throw e;
        }
        SyndFeed feed;
        try {
            feed = parse(body);
        } catch (Exception e) {
            throw new FetchException(notFound, e);
        }
        List<RedditPost> posts = new ArrayList<>();
        for (SyndEntry entry : feed.getEntries()) {
            if (posts.size() >= limit) break;
            posts.add(toPost(entry));
        }
        return posts;
    }

    @Override
    public List<String> topComments(String postId, int n) throws Exception {
        throttle();
        byte[] body = fetcher.get(baseUrl + "/comments/" + postId + "/.rss?sort=top&limit=" + (n + 1), 1);
        List<String> comments = new ArrayList<>();
        for (SyndEntry entry : parse(body).getEntries()) {
            if (comments.size() >= n) break;
            if (entry.getUri() == null || !entry.getUri().startsWith("t1_")) continue;
            String text = mdText(Jsoup.parse(contentHtml(entry)), " ");
            if (text == null) continue;
            comments.add(text.length() > MAX_COMMENT_CHARS ? text.substring(0, MAX_COMMENT_CHARS) : text);
        }
        return comments;
    }

    private RedditPost toPost(SyndEntry entry) {
        String id = entry.getUri() != null && entry.getUri().startsWith("t3_") ? entry.getUri().substring(3) : entry.getUri();
        Date date = entry.getPublishedDate() != null ? entry.getPublishedDate() : entry.getUpdatedDate();
        String author = entry.getAuthor() == null ? null : entry.getAuthor().replaceFirst("^/u/", "").trim();
        Document content = Jsoup.parse(contentHtml(entry));
        String selfText = mdText(content, "\n\n");
        Element linkAnchor = content.select("a").stream().filter(a -> a.text().trim().equals("[link]")).findFirst().orElse(null);
        String target = linkAnchor == null ? null : linkAnchor.attr("href");
        boolean media = false;
        String linkUrl = null;
        if (target != null && !target.isBlank()) {
            URI uri;
            try {
                uri = URI.create(target.trim());
            } catch (IllegalArgumentException e) {
                uri = null;
            }
            String host = uri == null || uri.getHost() == null ? "" : uri.getHost().toLowerCase();
            String path = uri == null || uri.getPath() == null ? "" : uri.getPath();
            if (MEDIA_HOSTS.contains(host) || path.startsWith("/gallery/")) {
                media = true;
            } else if (!(host.endsWith("reddit.com") || host.endsWith("redd.it")) && !host.isEmpty()) {
                linkUrl = target.trim();
            }
        }
        return new RedditPost(id, entry.getLink(), entry.getTitle() == null ? "" : entry.getTitle().trim(), author,
                date == null ? null : date.toInstant(), linkUrl, selfText, media);
    }

    private static String contentHtml(SyndEntry entry) {
        for (SyndContent c : entry.getContents()) {
            if (c.getValue() != null) return c.getValue();
        }
        return "";
    }

    /** Plain text of the post/comment body ({@code div.md}), paragraphs joined by {@code separator}; null if empty. */
    private static String mdText(Document doc, String separator) {
        Element md = doc.selectFirst("div.md");
        if (md == null) return null;
        List<String> paragraphs = new ArrayList<>();
        for (Element p : md.select("p, li")) {
            String t = p.text().replaceAll("\\s+", " ").trim();
            if (!t.isEmpty()) paragraphs.add(t);
        }
        if (paragraphs.isEmpty()) {
            String t = md.text().replaceAll("\\s+", " ").trim();
            return t.isEmpty() ? null : t;
        }
        return String.join(separator, paragraphs);
    }

    private static SyndFeed parse(byte[] body) throws Exception {
        try (XmlReader reader = new XmlReader(new ByteArrayInputStream(body))) {
            return new SyndFeedInput().build(reader);
        }
    }

    private void throttle() throws InterruptedException {
        if (delay.isZero()) return;
        Instant wait;
        synchronized (this) {
            Instant now = Instant.now();
            Instant slot = nextAllowed.isAfter(now) ? nextAllowed : now;
            nextAllowed = slot.plus(delay);
            wait = slot;
        }
        long millis = Duration.between(Instant.now(), wait).toMillis();
        if (millis > 0) Thread.sleep(millis);
    }
}
```

Note on `throttlesRedditRequests`: request slots are reserved at t, t+150 ms, t+300 ms, so three calls take at least 300 ms regardless of how long each request itself takes.

- [ ] **Step 6: Run tests**

Run: `./mvnw -q test -Dtest=RedditRssClientTest`
Expected: PASS. If `parsesRealSubredditFeed` disagrees on the counts, print each post's `(linkUrl, media, selfText != null)` and compare with the fixture's `[link]` anchors before changing the classifier (the expected counts were measured from the fixture: 4 link, 2 `i.redd.it`, 6 text).

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "Add Reddit RSS client with post classification, best-effort comments and throttling

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01LbcoMupq1VXnfzdqroFmyp"
```

---

### Task 3: Reddit source connector, ingestion and API integration

**Files:**
- Create: `src/main/java/org/roncax/podcaster/ingestion/reddit/RedditSourceConnector.java`
- Test: `src/test/java/org/roncax/podcaster/ingestion/reddit/RedditSourceConnectorTest.java`, `src/test/java/org/roncax/podcaster/ingestion/reddit/RedditIngestionTest.java`
- Modify: `README.md` (Reddit source example)

**Interfaces:**
- Consumes: `RedditClient`, `RedditPost` (Task 2); `RawItem` 7-arg, `SourceConnector.validate`, `Item.discussionUrl` (Task 1); `ContentExtractionService#extract(String) → Optional<String>`.
- Produces: `RedditSourceConnector` (`@ApplicationScoped`, `type() = "reddit"`, `providesFullText() = true`, public constructor `(RedditClient, ContentExtractionService)`), item rules exactly as spec §3.3.

- [ ] **Step 1: Write the failing unit test**

```java
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
        @Override public Optional<String> extract(String url) { return url.contains("article") ? Optional.of("Article text of " + url) : Optional.empty(); }
    }

    FakeClient client = new FakeClient();
    RedditSourceConnector connector = new RedditSourceConnector(client, new FakeExtraction());

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
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -q test -Dtest=RedditSourceConnectorTest`
Expected: compilation FAIL (`RedditSourceConnector` not found).

- [ ] **Step 3: Implement `RedditSourceConnector`**

```java
package org.roncax.podcaster.ingestion.reddit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import org.jboss.logging.Logger;
import org.roncax.podcaster.extraction.ContentExtractionService;
import org.roncax.podcaster.ingestion.RawItem;
import org.roncax.podcaster.ingestion.SourceConfig;
import org.roncax.podcaster.ingestion.SourceConnector;

/** Top posts of a subreddit: link posts with their article text, substantial text posts, best-effort top comments. */
@ApplicationScoped
public class RedditSourceConnector implements SourceConnector {
    public static final String TYPE = "reddit";
    static final int MIN_TEXT_CHARS = 200;
    static final int MAX_SUMMARY_CHARS = 500;
    static final String COMMENTS_HEADER = "Reddit discussion (top comments):";
    private static final Pattern SUBREDDIT = Pattern.compile("[A-Za-z0-9_]{2,21}");
    private static final Set<String> WINDOWS = Set.of("hour", "day", "week", "month");
    private static final Logger LOG = Logger.getLogger(RedditSourceConnector.class);

    private record Settings(String subreddit, String window, int maxPosts, int commentPosts, int topComments) {}

    private final RedditClient client;
    private final ContentExtractionService extraction;

    @Inject
    public RedditSourceConnector(RedditClient client, ContentExtractionService extraction) {
        this.client = client;
        this.extraction = extraction;
    }

    @Override
    public String type() { return TYPE; }

    @Override
    public boolean providesFullText() { return true; }

    @Override
    public List<String> validate(SourceConfig config) {
        List<String> errors = new ArrayList<>();
        Optional<String> subreddit = config.get("subreddit");
        if (subreddit.isEmpty()) return List.of("reddit sources need config.subreddit (e.g. subreddit=italy)");
        if (!SUBREDDIT.matcher(subreddit.get().trim()).matches()) {
            errors.add("subreddit must be 2-21 letters, digits or underscores, without 'r/'");
        }
        String window = config.get("window").orElse("day").trim();
        if (!WINDOWS.contains(window)) errors.add("window must be one of hour, day, week, month");
        Integer maxPosts = intIn(config, "maxPosts", 10, 1, 25, errors);
        Integer commentPosts = intIn(config, "commentPosts", 5, 0, 25, errors);
        intIn(config, "topComments", 3, 1, 10, errors);
        if (maxPosts != null && commentPosts != null && commentPosts > maxPosts) {
            errors.add("commentPosts must not exceed maxPosts");
        }
        return errors;
    }

    @Override
    public List<RawItem> fetch(SourceConfig config, Instant since) throws Exception {
        List<String> errors = validate(config);
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("; ", errors));
        Settings s = settings(config);

        List<RawItem> items = new ArrayList<>();
        boolean commentsEnabled = s.commentPosts() > 0;
        int commentAttempts = 0;
        for (RedditPost post : client.listing(s.subreddit(), s.window(), s.maxPosts())) {
            if (post.publishedAt() != null && since != null && post.publishedAt().isBefore(since)) continue;
            String url;
            String text;
            if (post.linkUrl() != null) {
                url = post.linkUrl();
                text = extract(url).orElse(post.selfText());
            } else {
                if (post.selfText() == null || post.selfText().length() < MIN_TEXT_CHARS) continue;
                url = post.threadUrl();
                text = post.selfText();
            }
            if (commentsEnabled && commentAttempts < s.commentPosts()) {
                commentAttempts++;
                try {
                    List<String> comments = client.topComments(post.id(), s.topComments());
                    if (!comments.isEmpty()) {
                        StringBuilder sb = new StringBuilder(text == null ? "" : text.strip());
                        sb.append("\n\n").append(COMMENTS_HEADER);
                        for (String c : comments) sb.append("\n- ").append(c);
                        text = sb.toString();
                    }
                } catch (Exception e) {
                    LOG.infof("Reddit comments unavailable for r/%s (%s); continuing without comments", s.subreddit(), e.getMessage());
                    commentsEnabled = false;
                }
            }
            String summary = post.selfText() == null ? null
                    : post.selfText().length() > MAX_SUMMARY_CHARS ? post.selfText().substring(0, MAX_SUMMARY_CHARS) : post.selfText();
            items.add(new RawItem(url, post.title(), post.author(), post.publishedAt(), summary,
                    text == null || text.isBlank() ? null : text.strip(), post.threadUrl()));
        }
        return items;
    }

    private Optional<String> extract(String url) {
        try {
            return extraction.extract(url);
        } catch (Exception e) {
            LOG.debugf("Article extraction failed for %s: %s", url, e.getMessage());
            return Optional.empty();
        }
    }

    private static Settings settings(SourceConfig config) {
        return new Settings(config.require("subreddit").trim(), config.get("window").orElse("day").trim(),
                Integer.parseInt(config.get("maxPosts").orElse("10").trim()),
                Integer.parseInt(config.get("commentPosts").orElse("5").trim()),
                Integer.parseInt(config.get("topComments").orElse("3").trim()));
    }

    private static Integer intIn(SourceConfig config, String key, int def, int min, int max, List<String> errors) {
        String raw = config.get(key).orElse(String.valueOf(def)).trim();
        try {
            int v = Integer.parseInt(raw);
            if (v < min || v > max) {
                errors.add(key + " must be between " + min + " and " + max);
                return null;
            }
            return v;
        } catch (NumberFormatException e) {
            errors.add(key + " must be a number");
            return null;
        }
    }
}
```

- [ ] **Step 4: Run the unit test**

Run: `./mvnw -q test -Dtest=RedditSourceConnectorTest`
Expected: PASS.

- [ ] **Step 5: Write the failing integration test**

```java
package org.roncax.podcaster.ingestion.reddit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.domain.Item;
import org.roncax.podcaster.domain.Show;
import org.roncax.podcaster.domain.Source;
import org.roncax.podcaster.ingestion.IngestionService;
import org.roncax.podcaster.support.*;

@QuarkusTest
@WithTestResource(WireMockResource.class)
@WithTestResource(org.roncax.podcaster.support.PostgresResource.class)
class RedditIngestionTest {
    @InjectWireMock WireMockServer wm;
    @Inject IngestionService ingestion;

    @BeforeEach
    void setup() {
        TestData.cleanDb();
        WireMockResource.installDefaults(wm);
        FakeChatModelRegistry.install(new FakeChatModel().responder(FakeResponses::pipeline));
    }

    private Source redditSource(long showId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Source s = new Source();
            s.showId = showId;
            s.connectorType = "reddit";
            s.config = new java.util.HashMap<>(Map.of("subreddit", "italy", "topComments", "2"));
            s.persist();
            return s;
        });
    }

    @Test
    void ingestsRedditAndDedupesAgainstRss() {
        Show show = TestData.show("reddit");
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String article = wm.baseUrl() + "/article-shared";
        wm.stubFor(get("/article-shared").willReturn(aResponse().withStatus(200).withBody(Fixtures.bytes("ilpost-article.html"))));
        wm.stubFor(get(urlPathEqualTo("/r/italy/top/.rss")).willReturn(okXml(RedditFeeds.listing(
                RedditFeeds.entry("p1", "Sciopero ATM", article, null, now),
                RedditFeeds.entry("p2", "Discussione", RedditFeeds.thread("p2"), "Opinione ".repeat(40), now),
                RedditFeeds.entry("p3", "Meme", "https://i.redd.it/meme.jpg", null, now)))));
        wm.stubFor(get(urlPathEqualTo("/comments/p1/.rss")).willReturn(okXml(RedditFeeds.comments("p1", "<p>Che disastro</p>", "<p>Ci risiamo</p>"))));
        wm.stubFor(get(urlPathEqualTo("/comments/p2/.rss")).willReturn(aResponse().withStatus(429)));
        String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(now.atOffset(ZoneOffset.UTC));
        wm.stubFor(get("/rssfeed").willReturn(okXml("<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>t</title><link>http://x</link><description>d</description>"
                + "<item><title>Sciopero ATM</title><link>" + article + "</link><pubDate>" + date + "</pubDate></item></channel></rss>")));
        redditSource(show.id);
        TestData.source(show.id, wm.baseUrl() + "/rssfeed");

        var report = ingestion.ingest(show.id, now.minus(1, ChronoUnit.HOURS));

        assertTrue(report.errors().isEmpty(), report.errors().toString());
        List<Item> items = QuarkusTransaction.requiringNew().call(() -> Item.<Item>list("showId", show.id));
        assertEquals(2, items.size(), "shared article stored once, meme skipped");
        Item shared = items.stream().filter(i -> i.url.equals(article)).findFirst().orElseThrow();
        assertEquals(RedditFeeds.thread("p1"), shared.discussionUrl);
        assertTrue(shared.fullText.contains("sciopero"));
        assertTrue(shared.fullText.endsWith("Reddit discussion (top comments):\n- Che disastro\n- Ci risiamo"), shared.fullText);
        Item discussion = items.stream().filter(i -> i.url.equals(RedditFeeds.thread("p2"))).findFirst().orElseThrow();
        assertFalse(discussion.fullText.contains("Reddit discussion"));
    }

    @Test
    void apiValidatesRedditConfigAndTestsSource() {
        Show show = TestData.show("reddit-api");
        var api = given().header("X-API-Key", "test-api-key-0123456789").contentType(ContentType.JSON);
        api.body(Map.of("connectorType", "reddit", "config", Map.of("subreddit", "r/italy")))
                .post("/api/shows/" + show.id + "/sources").then().statusCode(400)
                .body("details", hasItem(containsString("subreddit must be")));

        wm.stubFor(get(urlPathEqualTo("/r/italy/top/.rss")).willReturn(okXml(RedditFeeds.listing(
                RedditFeeds.entry("t1", "Discussione", RedditFeeds.thread("t1"), "Opinione ".repeat(40), Instant.now())))));
        wm.stubFor(get(urlPathEqualTo("/comments/t1/.rss")).willReturn(okXml(RedditFeeds.comments("t1", "<p>Bene</p>"))));
        long sourceId = given().header("X-API-Key", "test-api-key-0123456789").contentType(ContentType.JSON)
                .body(Map.of("connectorType", "reddit", "config", Map.of("subreddit", "italy")))
                .post("/api/shows/" + show.id + "/sources").then().statusCode(201).extract().jsonPath().getLong("id");

        given().header("X-API-Key", "test-api-key-0123456789").post("/api/sources/" + sourceId + "/test").then().statusCode(200)
                .body("items.size()", is(1))
                .body("items[0].discussionUrl", is(RedditFeeds.thread("t1")))
                .body("sampleText", containsString("Reddit discussion (top comments):\n- Bene"));
    }
}
```

- [ ] **Step 6: Run the integration test**

Run: `./mvnw -q test -Dtest=RedditIngestionTest`
Expected: PASS. (`ShowService.testSource` returns the connector's `fullText` as `sampleText` because `providesFullText()` is true.)

- [ ] **Step 7: Document the source in the README**

Add after the "Example sources" line in "Quick start":

```markdown
Reddit (no credentials needed): connector `reddit` with config `subreddit=italy` (optional `window=day|week`, `maxPosts`, `commentPosts`, `topComments`). Link posts use the linked article's text, substantial text posts their own text; top comments are added when Reddit allows (requests are spaced 6 s apart to respect rate limits).
```

- [ ] **Step 8: Run the full suite and commit**

Run: `./mvnw -q test`
Expected: PASS.

```bash
git add -A
git commit -m "Add Reddit source connector with article extraction, comments and config validation

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01LbcoMupq1VXnfzdqroFmyp"
```

- [ ] **Step 9: Smoke-test against real Reddit in the running container (manual, rate-limit aware)**

```bash
docker compose up -d --build podcaster
for i in $(seq 1 40); do curl -fs localhost:8080/q/health >/dev/null && break; sleep 3; done
KEY=$(grep ^PODCASTER_API_KEY .env | cut -d= -f2)
SRC=$(curl -s -H "X-API-Key: $KEY" -H "Content-Type: application/json" \
  -d '{"connectorType":"reddit","config":{"subreddit":"italy","maxPosts":"5","commentPosts":"1","topComments":"2"}}' \
  localhost:8080/api/shows/1/sources | python3 -c "import sys,json;print(json.load(sys.stdin)['id'])")
curl -s -X POST -H "X-API-Key: $KEY" localhost:8080/api/sources/$SRC/test | python3 -m json.tool | head -40
```

Expected: a handful of r/italy items (link posts with article URLs, long text posts with thread URLs, each with `discussionUrl`); comments may be absent if Reddit rate-limits. Ask the user before keeping this source on the show (it adds Reddit content to their real episodes); delete it otherwise with `curl -X DELETE -H "X-API-Key: $KEY" localhost:8080/api/sources/$SRC`.
