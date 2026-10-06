package org.roncax.podcaster.ingestion.reddit;

import java.util.List;

/** Access to Reddit. Implemented over public Atom feeds today; an OAuth client can implement it later. */
public interface RedditClient {
    /** Top posts of the subreddit for the window, in score order, at most {@code limit}. */
    List<RedditPost> listing(String subreddit, String window, int limit) throws Exception;

    /** Up to {@code n} top comments of the post as plain text. May throw when rate-limited. */
    List<String> topComments(String postId, int n) throws Exception;
}
