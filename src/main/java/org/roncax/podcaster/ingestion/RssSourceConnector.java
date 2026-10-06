package org.roncax.podcaster.ingestion;

import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.jsoup.Jsoup;
import org.roncax.podcaster.http.HttpFetcher;

@ApplicationScoped
public class RssSourceConnector implements SourceConnector {
    public static final String TYPE = "rss";
    private static final int MIN_FULL_TEXT_CHARS = 500;

    private final HttpFetcher fetcher;

    @Inject
    public RssSourceConnector(HttpFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public String type() { return TYPE; }

    @Override
    public java.util.List<String> validate(SourceConfig config) {
        return config.get("url").isPresent() ? java.util.List.of() : java.util.List.of("rss sources need config.url");
    }

    @Override
    public List<RawItem> fetch(SourceConfig config, Instant since) throws Exception {
        String url = config.require("url");
        byte[] body = fetcher.get(url);
        SyndFeed feed;
        try (XmlReader reader = new XmlReader(new ByteArrayInputStream(body))) {
            feed = new SyndFeedInput().build(reader);
        }
        List<RawItem> items = new ArrayList<>();
        for (SyndEntry entry : feed.getEntries()) {
            String link = firstNonBlank(entry.getLink(), entry.getUri());
            if (link == null) continue;
            Date date = entry.getPublishedDate() != null ? entry.getPublishedDate() : entry.getUpdatedDate();
            Instant published = date == null ? null : date.toInstant();
            if (published != null && since != null && published.isBefore(since)) continue;
            String title = entry.getTitle() == null || entry.getTitle().isBlank() ? link : entry.getTitle().trim();
            String summary = entry.getDescription() == null ? null : htmlToText(entry.getDescription().getValue());
            items.add(new RawItem(link.trim(), title, blankToNull(entry.getAuthor()), published, summary, fullText(entry)));
        }
        return items;
    }

    private static String fullText(SyndEntry entry) {
        for (SyndContent content : entry.getContents()) {
            String text = htmlToText(content.getValue());
            if (text != null && text.length() >= MIN_FULL_TEXT_CHARS) return text;
        }
        return null;
    }

    static String htmlToText(String html) {
        if (html == null) return null;
        String text = Jsoup.parse(html).text().trim();
        return text.isEmpty() ? null : text;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return null;
    }
}
