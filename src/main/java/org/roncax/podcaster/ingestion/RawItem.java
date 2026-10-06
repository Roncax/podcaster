package org.roncax.podcaster.ingestion;

import java.time.Instant;

/** One fetched news item. {@code discussionUrl} is an optional link to a discussion (e.g. the Reddit thread). */
public record RawItem(String url, String title, String author, Instant publishedAt, String summary, String fullText,
                      String discussionUrl) {

    public RawItem(String url, String title, String author, Instant publishedAt, String summary, String fullText) {
        this(url, title, author, publishedAt, summary, fullText, null);
    }
}
