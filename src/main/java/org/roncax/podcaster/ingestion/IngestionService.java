package org.roncax.podcaster.ingestion;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.jboss.logging.Logger;
import org.roncax.podcaster.domain.Item;
import org.roncax.podcaster.domain.Source;
import org.roncax.podcaster.extraction.ContentExtractionService;
import org.roncax.podcaster.util.Exceptions;

@ApplicationScoped
public class IngestionService {
    private static final Logger LOG = Logger.getLogger(IngestionService.class);
    private static final int MAX_URL = 2000;
    public static final int MAX_TITLE = 1000;

    @Inject ConnectorRegistry registry;
    @Inject ContentExtractionService extraction;

    public interface Progress { void update(int done, int total, int newItems); }

    public IngestionReport ingest(long showId, Instant since) {
        return ingest(showId, since, (d, t, n) -> {});
    }

    public IngestionReport ingest(long showId, Instant since, Progress progress) {
        List<Source> sources = QuarkusTransaction.requiringNew()
                .call(() -> Source.<Source>list("showId = ?1 and enabled = true order by id", showId));
        int added = 0;
        List<String> errors = new ArrayList<>();
        int done = 0;
        for (Source source : sources) {
            try {
                added += ingestSource(showId, source, since);
                markSource(source.id, null);
            } catch (Exception e) {
                String message = Exceptions.rootMessage(e);
                LOG.warnf("Source %s failed: %s", source.label(), message);
                errors.add(source.label() + ": " + message);
                markSource(source.id, message);
            }
            progress.update(++done, sources.size(), added);
        }
        return new IngestionReport(sources.size(), added, errors);
    }

    private int ingestSource(long showId, Source source, Instant since) throws Exception {
        int added = 0;
        for (CollectedItem item : collect(source, since, url -> exists(showId, url))) {
            if (save(showId, source.id, item)) added++;
        }
        return added;
    }

    /** Fetches, filters and extracts a source's items. Stores nothing; {@code seen} URLs are skipped. */
    public List<CollectedItem> collect(Source source, Instant since, Predicate<String> seen) throws Exception {
        SourceConnector connector = registry.find(source.connectorType)
                .orElseThrow(() -> new IllegalStateException("Unknown connector type '" + source.connectorType + "'"));
        List<RawItem> raws = connector.fetch(new SourceConfig(source.config), since, seen);
        List<CollectedItem> out = new ArrayList<>();
        Set<String> collectedUrls = new HashSet<>();
        for (RawItem raw : raws) {
            if (!org.roncax.podcaster.admin.SafeHref.isHttp(raw.url())) {
                LOG.debugf("Skipping item with non-http link from %s", source.label());
                continue;
            }
            if (raw.url().length() > MAX_URL || seen.test(raw.url()) || !collectedUrls.add(raw.url())) continue;
            String fullText = raw.fullText();
            if (fullText == null && source.fetchFullText && !connector.providesFullText()) {
                try {
                    fullText = extraction.extract(raw.url()).orElse(null);
                } catch (Exception e) {
                    LOG.debugf("Extraction failed for %s: %s", raw.url(), e.getMessage());
                }
            }
            String hash = ContentHasher.hash(raw.title(), fullText != null ? fullText : raw.summary());
            out.add(new CollectedItem(raw, fullText, hash));
        }
        return out;
    }

    private boolean exists(long showId, String url) {
        return QuarkusTransaction.requiringNew()
                .call(() -> Item.count("showId = ?1 and url = ?2", showId, url) > 0);
    }

    /** Builds the (unpersisted) Item for a collected item; shared by ingestion and the test-tab dry run. */
    public static Item toItem(long showId, long sourceId, CollectedItem collected) {
        RawItem raw = collected.raw();
        Item item = new Item();
        item.showId = showId;
        item.sourceId = sourceId;
        item.url = raw.url();
        item.contentHash = collected.contentHash();
        item.title = raw.title().length() > MAX_TITLE ? raw.title().substring(0, MAX_TITLE) : raw.title();
        item.author = raw.author();
        item.publishedAt = raw.publishedAt();
        item.fetchedAt = Instant.now();
        item.summary = raw.summary();
        item.fullText = collected.fullText();
        item.discussionUrl = org.roncax.podcaster.admin.SafeHref.isHttp(raw.discussionUrl()) ? raw.discussionUrl() : null;
        return item;
    }

    private boolean save(long showId, long sourceId, CollectedItem collected) {
        return QuarkusTransaction.requiringNew().call(() -> {
            if (Item.count("showId = ?1 and contentHash = ?2", showId, collected.contentHash()) > 0) return false;
            Item item = toItem(showId, sourceId, collected);
            item.persist();
            return true;
        });
    }

    private void markSource(long sourceId, String error) {
        QuarkusTransaction.requiringNew().run(() -> {
            Source s = Source.findById(sourceId);
            if (s == null) return;
            s.lastFetchedAt = Instant.now();
            s.lastError = error;
        });
    }
}
