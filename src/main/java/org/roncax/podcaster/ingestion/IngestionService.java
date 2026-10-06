package org.roncax.podcaster.ingestion;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jboss.logging.Logger;
import org.roncax.podcaster.domain.Item;
import org.roncax.podcaster.domain.Source;
import org.roncax.podcaster.extraction.ContentExtractionService;
import org.roncax.podcaster.util.Exceptions;

@ApplicationScoped
public class IngestionService {
    private static final Logger LOG = Logger.getLogger(IngestionService.class);
    private static final int MAX_URL = 2000;
    private static final int MAX_TITLE = 1000;

    @Inject ConnectorRegistry registry;
    @Inject ContentExtractionService extraction;

    public IngestionReport ingest(long showId, Instant since) {
        List<Source> sources = QuarkusTransaction.requiringNew()
                .call(() -> Source.<Source>list("showId = ?1 and enabled = true order by id", showId));
        int added = 0;
        List<String> errors = new ArrayList<>();
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
        }
        return new IngestionReport(sources.size(), added, errors);
    }

    private int ingestSource(long showId, Source source, Instant since) throws Exception {
        SourceConnector connector = registry.find(source.connectorType)
                .orElseThrow(() -> new IllegalStateException("Unknown connector type '" + source.connectorType + "'"));
        List<RawItem> raws = connector.fetch(new SourceConfig(source.config), since, url -> exists(showId, url));
        int added = 0;
        for (RawItem raw : raws) {
            if (raw.url().length() > MAX_URL || exists(showId, raw.url())) continue;
            String fullText = raw.fullText();
            if (fullText == null && source.fetchFullText && !connector.providesFullText()) {
                try {
                    fullText = extraction.extract(raw.url()).orElse(null);
                } catch (Exception e) {
                    LOG.debugf("Extraction failed for %s: %s", raw.url(), e.getMessage());
                }
            }
            if (save(showId, source.id, raw, fullText)) added++;
        }
        return added;
    }

    private boolean exists(long showId, String url) {
        return QuarkusTransaction.requiringNew()
                .call(() -> Item.count("showId = ?1 and url = ?2", showId, url) > 0);
    }

    private boolean save(long showId, long sourceId, RawItem raw, String fullText) {
        String hash = ContentHasher.hash(raw.title(), fullText != null ? fullText : raw.summary());
        return QuarkusTransaction.requiringNew().call(() -> {
            if (Item.count("showId = ?1 and contentHash = ?2", showId, hash) > 0) return false;
            Item item = new Item();
            item.showId = showId;
            item.sourceId = sourceId;
            item.url = raw.url();
            item.contentHash = hash;
            item.title = raw.title().length() > MAX_TITLE ? raw.title().substring(0, MAX_TITLE) : raw.title();
            item.author = raw.author();
            item.publishedAt = raw.publishedAt();
            item.fetchedAt = Instant.now();
            item.summary = raw.summary();
            item.fullText = fullText;
            item.discussionUrl = raw.discussionUrl();
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
