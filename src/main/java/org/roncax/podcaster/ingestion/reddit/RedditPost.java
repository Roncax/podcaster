package org.roncax.podcaster.ingestion.reddit;

import java.time.Instant;

/** A post from a subreddit listing. {@code linkUrl} is set only for posts linking to an external article. */
public record RedditPost(String id, String threadUrl, String title, String author, Instant publishedAt,
                         String linkUrl, String selfText, boolean media) {}
