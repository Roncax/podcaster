package org.roncax.podcaster.publishing;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.roncax.podcaster.config.PodcasterConfig;
import org.roncax.podcaster.domain.Episode;
import org.roncax.podcaster.domain.Item;
import org.roncax.podcaster.domain.Show;

@Path("/feeds")
public class FeedResource {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject PodcastFeedRenderer renderer;
    @Inject PodcasterConfig config;

    @GET
    @Path("/{token}/{slug}.xml")
    @Produces("application/rss+xml; charset=UTF-8")
    public String feed(@PathParam("token") String token, @PathParam("slug") String slug) {
        Show show = Show.findByFeedToken(token).filter(s -> s.slug.equals(slug)).orElseThrow(NotFoundException::new);
        List<Episode> episodes = Episode.list(
                "showId = ?1 and publishedAt is not null and audioPath is not null order by publishedAt desc", show.id);
        return renderer.render(show, episodes, config.baseUrl());
    }

    @GET
    @Path("/{token}/{slug}/chapters/{id}.json")
    @Produces(ChaptersJson.MEDIA_TYPE)
    public String chapters(@PathParam("token") String token, @PathParam("slug") String slug, @PathParam("id") long id)
            throws JsonProcessingException {
        Show show = Show.findByFeedToken(token).filter(s -> s.slug.equals(slug)).orElseThrow(NotFoundException::new);
        Episode episode = Episode.<Episode>findByIdOptional(id)
                .filter(e -> e.showId.equals(show.id) && e.publishedAt != null && ChaptersJson.available(e))
                .orElseThrow(NotFoundException::new);
        Set<Long> ids = episode.chapters.stream().flatMap(c -> c.itemIds().stream()).collect(Collectors.toSet());
        Map<Long, Item> items = ids.isEmpty() ? Map.of()
                : Item.<Item>list("id in ?1", ids).stream().collect(Collectors.toMap(i -> i.id, Function.identity()));
        return JSON.writeValueAsString(ChaptersJson.of(episode.chapters, items));
    }
}
