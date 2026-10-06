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
