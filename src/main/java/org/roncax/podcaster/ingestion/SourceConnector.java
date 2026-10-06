package org.roncax.podcaster.ingestion;

import java.time.Instant;
import java.util.List;

/** A pluggable news source. Implementations are CDI beans discovered by {@link ConnectorRegistry}. */
public interface SourceConnector {
    /** Connector type stored in {@code sources.connector_type}, e.g. "rss" or "site:example". */
    String type();

    /** Fetch items published at or after {@code since}. Undated items are returned too. */
    List<RawItem> fetch(SourceConfig config, Instant since) throws Exception;

    /** True when {@link RawItem#fullText()} is already the article text, so no extraction is needed. */
    default boolean providesFullText() { return false; }

    /** Connector-specific config errors; empty when valid. Checked when a source is created or updated. */
    default java.util.List<String> validate(SourceConfig config) { return java.util.List.of(); }
}
