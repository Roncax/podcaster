package org.roncax.podcaster.ingestion;

import java.time.Instant;
import java.util.List;

/** A pluggable news source. Implementations are CDI beans discovered by {@link ConnectorRegistry}. */
public interface SourceConnector {
    /** Connector type stored in {@code sources.connector_type}, e.g. "rss" or "site:example". */
    String type();

    /** Fetch items published at or after {@code since}. Undated items are returned too. */
    List<RawItem> fetch(SourceConfig config, Instant since) throws Exception;

    /**
     * Like {@link #fetch(SourceConfig, Instant)}, but may skip expensive work for items whose URL is already stored.
     * Connectors that do per-item fetching (extraction, comments) override this.
     */
    default List<RawItem> fetch(SourceConfig config, Instant since, java.util.function.Predicate<String> isKnownUrl) throws Exception {
        return fetch(config, since);
    }

    /** True when {@link RawItem#fullText()} is already the article text, so no extraction is needed. */
    default boolean providesFullText() { return false; }

    /** Connector-specific config errors; empty when valid. Checked when a source is created or updated. */
    default java.util.List<String> validate(SourceConfig config) { return java.util.List.of(); }
}
