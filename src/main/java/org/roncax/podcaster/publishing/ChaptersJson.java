package org.roncax.podcaster.publishing;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.roncax.podcaster.domain.Chapter;
import org.roncax.podcaster.domain.Episode;
import org.roncax.podcaster.domain.Item;
import org.roncax.podcaster.http.UrlGuard;

/** Podcasting 2.0 JSON chapters (https://github.com/Podcastindex-org/podcast-namespace/blob/main/chapters/jsonChapters.md). */
public record ChaptersJson(String version, List<Entry> chapters) {
    public static final String MEDIA_TYPE = "application/json+chapters";

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Entry(double startTime, String title, String url) {}

    /** Only episodes with recorded chapter times get a chapters file; older ones have none. */
    public static boolean available(Episode e) {
        return e.chapters != null && !e.chapters.isEmpty();
    }

    /** Each chapter links to its first http(s) source article; non-http links are never published. */
    public static ChaptersJson of(List<Chapter> chapters, Map<Long, Item> items) {
        List<Entry> entries = new ArrayList<>();
        for (Chapter c : chapters) {
            String url = c.itemIds().stream().map(items::get)
                    .filter(i -> i != null && UrlGuard.isHttp(i.url))
                    .map(i -> i.url).findFirst().orElse(null);
            entries.add(new Entry(Math.round(Math.max(0, c.startSeconds()) * 100) / 100.0, c.title(), url));
        }
        return new ChaptersJson("1.2.0", entries);
    }
}
