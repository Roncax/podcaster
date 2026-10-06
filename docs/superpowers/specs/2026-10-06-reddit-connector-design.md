# Reddit Connector — Design Spec

Date: 2026-10-06
Status: Approved design, pending implementation plan
Branch: `feature/reddit-connector`
Parent spec: `docs/superpowers/specs/2026-10-06-podcaster-design.md` (§3.1.1 "Deferred: reddit connector")

## 1. Purpose

Add a `reddit` source connector so a Show can use a subreddit's most popular posts of the day as news. Link posts contribute the linked article's text; text posts contribute their own text; the top posts also contribute a few top comments so episodes can mention community reactions.

### Success criteria

- A source `reddit` with `subreddit=italy` ingests the day's top link posts and substantial text posts of r/italy, with full text, without any Reddit credentials.
- Image/video-only posts and short text posts are never ingested.
- Comments are added when Reddit allows; when rate-limited, posts are still ingested (no source failure because of comments).
- An article posted on Reddit and published by another source of the same show (e.g. Il Post RSS) is stored once.
- The Reddit thread link is stored with each item for later display.
- Invalid configuration is rejected when the source is created.

## 2. Context and constraints

Verified 2026-10-06 from the dev machine, with sources:

- Since Reddit's Responsible Builder Policy (November 2025), creating an OAuth app requires Reddit's approval, which is rarely granted for personal scripts.
- Anonymous `.json` endpoints return 403.
- Public Atom feeds work: `https://www.reddit.com/r/<sub>/top/.rss?t=day` returns entries with title, author, published date, thread link and HTML content. The content holds a `[link]` anchor (external article, `i.redd.it` image, or the thread itself for text posts) and, for posts with text, a `<div class="md">` block.
- Per-post comment feeds (`<thread>/.rss?sort=top&limit=N`) exist but return 429 after a few unauthenticated requests.
- RSS exposes no scores. Feed order of `top` is by score.

Sources: [Reddit API approval 2026](https://postwire.io/platforms/reddit/approval/), [Reddit API shut down in 2026: what still works](https://fetchlayer.dev/blog/reddit-api-closed-2026), [Reddit Data API in 2026](https://www.redditapis.com/blogs/reddit-data-api-2026).

Decision: build on the public Atom feeds now. Put access behind a `RedditClient` interface so an OAuth client can be added later without changing the connector.

## 3. Design

### 3.1 Components (package `org.roncax.podcaster.ingestion.reddit`)

| Unit | Responsibility |
|---|---|
| `RedditPost` (record) | `threadUrl`, `title`, `author`, `publishedAt`, `linkUrl` (external article URL or null), `selfText` (plain text or null), `media` (boolean) |
| `RedditClient` (interface) | `listing(String subreddit, String window, int limit) → List<RedditPost>` (sort is always `top`); `topComments(String threadUrl, int n) → List<String>` |
| `RedditRssClient` | Atom implementation. Parses entries with Rome and the content HTML with Jsoup; enforces the Reddit request delay; best-effort comments (§3.4) |
| `RedditSourceConnector` | `type() = "reddit"`, config validation, post classification, text assembly, `providesFullText() = true` |

### 3.2 Configuration (source `config` map, strings)

| Key | Default | Rule |
|---|---|---|
| `subreddit` | required | `[A-Za-z0-9_]{2,21}`, without the `r/` prefix |
| `window` | `day` | one of `hour`, `day`, `week`, `month` |
| `maxPosts` | `10` | 1–25 |
| `commentPosts` | `5` | 0–`maxPosts` (0 disables comments) |
| `topComments` | `3` | 1–10 |

Application config: `podcaster.reddit.request-delay` (default `6s`; `0s` in tests) and `podcaster.reddit.base-url` (default `https://www.reddit.com`; WireMock in tests).

### 3.3 Post classification and item text

Each post is classified as follows:

- **Media:** `linkUrl` host is `i.redd.it`, `v.redd.it`, `preview.redd.it`, `i.imgur.com` or `imgur.com`, or the URL path starts with `/gallery/`.
- **Link:** `linkUrl` is any other host outside `reddit.com` / `redd.it`.
- **Text:** `[link]` points to the thread itself (no external link).

It then becomes an item, or is skipped:

| Kind | Item URL | Item text | Skip when |
|---|---|---|---|
| Link | article URL | article text extracted by `ContentExtractionService`; falls back to the post's self-text, then null | never (title-only is allowed, like RSS items without text) |
| Text | thread URL | self-text | self-text < 200 chars |
| Media | thread URL | self-text | self-text < 200 chars |

- For the first `commentPosts` posts **that become items**, in feed order, the connector appends:
  ```
  

  Reddit discussion (top comments):
  - <comment 1>
  - <comment 2>
  ```
  Each comment is plain text, whitespace-collapsed, and truncated to 500 chars.
- `publishedAt` = entry `published`, falling back to `updated`. Items older than `since` are dropped, like RSS.
- `RawItem.summary` = first 500 chars of the self-text, or null.

### 3.4 Pacing, rate limits and errors

- Every request to the Reddit base URL waits `podcaster.reddit.request-delay` after the previous one. This is a per-client throttle, independent of `HttpFetcher`'s generic per-host delay. Requests for linked articles go to their own hosts through `HttpFetcher` as usual.
- Listing: fetched through `HttpFetcher` (normal retries).
  - 404, or a non-Atom body: the source fails with `Subreddit r/<sub> not found or not public`.
  - Other failures: the source fails like any source (a Telegram warning when other sources of the show succeed).
- Comments: a single attempt, no retries. Any failure (429, 403, timeout, parse error) returns no comments and **disables comments for the rest of that `fetch` call**. The posts are still returned.

### 3.5 Model changes

- `RawItem` gains `discussionUrl` (nullable). A 6-argument constructor keeps existing call sites unchanged (`discussionUrl = null`).
- Flyway `V3__item_discussion_url.sql`: `alter table items add column discussion_url varchar(2000)`. `Item.discussionUrl`; `IngestionService` copies it from `RawItem`.
- `SourceConnector` gains `default List<String> validate(SourceConfig config) { return List.of(); }`. `RssSourceConnector` moves its `url` check there. `ShowService.validateSource` calls the selected connector's `validate`, replacing the hard-coded rss check.

### 3.6 Interfaces

No new endpoints or pages. A Reddit source is added like any source (admin "Add source" with connector `reddit` and config `subreddit=italy`, or `POST /api/shows/{id}/sources`). "Test" shows sample items with their assembled text.

## 4. Testing

Fixtures:
- `src/test/resources/fixtures/reddit-italy-top-day.xml`: real r/italy feed, 12 entries, captured 2026-10-06.
- A small hand-written comments Atom feed.
- Article HTML from the existing fixtures.

Cases:
- **Classification:** for the real feed, link posts become items with article URLs, `i.redd.it` posts with short text are skipped, discussion threads with ≥ 200 chars of text are kept, and dates are parsed.
- **Link post text:** the text comes from the extracted article (WireMock serves the article URL).
- **Comments:**
  - the first `commentPosts` items get a "Reddit discussion" block with `topComments` entries
  - a 429 on the first comment request stops further comment requests (WireMock verifies the request count) and all posts are still returned
- **Errors:** an unknown subreddit (404) fails with the "not found" message.
- **Config validation** through the API: a missing or invalid subreddit, a bad window, or out-of-range numbers return 400, and RSS sources still require `url`.
- **Ingestion:** the same article URL from an RSS source and a Reddit source of the same show is stored once, and Reddit items store `discussion_url`.
- **Throttle:** with a 50 ms delay, two consecutive client requests are at least 50 ms apart.

## 5. Out of scope

- The OAuth client, scores and `minScore` filtering. Add later as `RedditOAuthClient` behind `RedditClient` once credentials exist.
- Displaying discussion links in show notes. This is covered by the backlog item "Per-story source links in show notes".
- Multiple subreddits per source (add one source per subreddit) and flair filtering.
