package org.roncax.podcaster.ingestion;

/** A fetched item after link filtering and full-text extraction; not yet stored. */
public record CollectedItem(RawItem raw, String fullText, String contentHash) {}
