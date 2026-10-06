package org.roncax.podcaster.ingestion;

import java.time.Instant;

public record RawItem(String url, String title, String author, Instant publishedAt, String summary, String fullText) {}
