package org.roncax.podcaster.extraction;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.roncax.podcaster.http.FetchException;
import org.roncax.podcaster.http.HttpFetcher;

@ApplicationScoped
public class ContentExtractionService {
    private final HttpFetcher fetcher;
    private final List<ContentExtractor> extractors;

    @Inject
    public ContentExtractionService(HttpFetcher fetcher, Instance<ContentExtractor> extractors) {
        this(fetcher, extractors.stream().toList());
    }

    public ContentExtractionService(HttpFetcher fetcher, List<ContentExtractor> extractors) {
        this.fetcher = fetcher;
        this.extractors = extractors.stream()
                .sorted(Comparator.comparingInt(ContentExtractor::priority).reversed())
                .toList();
    }

    public Optional<String> extract(String url) throws FetchException {
        return extract(url, fetcher.get(url));
    }

    /** For URLs from untrusted origins: non-public hosts and redirects to them are refused (BlockedUrlException). */
    public Optional<String> extractUntrusted(String url) throws FetchException {
        return extract(url, fetcher.getUntrusted(url));
    }

    public Optional<String> extract(String url, byte[] html) {
        URI uri;
        Document doc;
        try {
            uri = URI.create(url);
            doc = Jsoup.parse(new ByteArrayInputStream(html), null, url);
        } catch (IllegalArgumentException | IOException e) {
            return Optional.empty();
        }
        for (ContentExtractor extractor : extractors) {
            if (!extractor.supports(uri)) continue;
            Optional<String> text = extractor.extract(doc).filter(t -> !t.isBlank());
            if (text.isPresent()) return text;
        }
        return Optional.empty();
    }
}
