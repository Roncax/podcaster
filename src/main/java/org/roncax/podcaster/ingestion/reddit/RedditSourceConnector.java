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
