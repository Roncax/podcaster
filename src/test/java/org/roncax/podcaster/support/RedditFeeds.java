package org.roncax.podcaster.support;

import java.time.Instant;

/** Builds Reddit-style Atom feeds (same shape as reddit.com/r/<sub>/top/.rss) for WireMock. */
public final class RedditFeeds {
    private RedditFeeds() {}

    public static String thread(String id) {
        return "https://www.reddit.com/r/italy/comments/" + id + "/slug/";
    }

    /** {@code link}: external URL for link posts, {@code thread(id)} for text posts, an i.redd.it URL for images. */
    public static String entry(String id, String title, String link, String selfText, Instant published) {
        String md = selfText == null ? "" : "<!-- SC_OFF --><div class=\"md\"><p>" + selfText + "</p></div><!-- SC_ON -->";
        String html = "<table><tr><td>" + md + " submitted by <a href=\"https://www.reddit.com/user/tester\"> /u/tester </a> <br/>"
                + "<span><a href=\"" + link + "\">[link]</a></span> <span><a href=\"" + thread(id) + "\">[comments]</a></span></td></tr></table>";
        return "<entry><author><name>/u/tester</name></author><id>t3_" + id + "</id>"
                + "<link href=\"" + thread(id) + "\" /><updated>" + published + "</updated><published>" + published + "</published>"
                + "<title>" + escape(title) + "</title><content type=\"html\">" + escape(html) + "</content></entry>";
    }

    public static String listing(String... entries) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><feed xmlns=\"http://www.w3.org/2005/Atom\">"
                + "<id>/r/italy/top/.rss</id><title>top</title><updated>2026-10-06T18:00:00+00:00</updated>"
                + String.join("", entries) + "</feed>";
    }

    /** First entry is the post itself (t3_), then one t1_ entry per comment. */
    public static String comments(String postId, String... commentHtml) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><feed xmlns=\"http://www.w3.org/2005/Atom\">"
                + "<id>/comments/" + postId + "</id><title>c</title><updated>2026-10-06T18:00:00+00:00</updated>"
                + "<entry><id>t3_" + postId + "</id><title>post</title><updated>2026-10-06T18:00:00+00:00</updated>"
                + "<link href=\"" + thread(postId) + "\" /><content type=\"html\">" + escape("<div class=\"md\"><p>post body</p></div>") + "</content></entry>");
        for (int i = 0; i < commentHtml.length; i++) {
            sb.append("<entry><id>t1_c").append(i).append("</id><title>comment</title><updated>2026-10-06T18:00:00+00:00</updated>")
              .append("<link href=\"").append(thread(postId)).append("c").append(i).append("/\" />")
              .append("<content type=\"html\">").append(escape("<!-- SC_OFF --><div class=\"md\">" + commentHtml[i] + "</div>")).append("</content></entry>");
        }
        return sb.append("</feed>").toString();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
