package org.roncax.podcaster.publishing;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import java.util.List;
import org.roncax.podcaster.config.PodcasterConfig;
import org.roncax.podcaster.domain.Episode;
import org.roncax.podcaster.domain.Show;

@Path("/feeds")
public class FeedResource {
    @Inject PodcastFeedRenderer renderer;
    @Inject PodcasterConfig config;

    @GET
    @Path("/{slug}.xml")
    @Produces("application/rss+xml; charset=UTF-8")
    public String feed(@PathParam("slug") String slug) {
        Show show = Show.findBySlug(slug).orElseThrow(NotFoundException::new);
        List<Episode> episodes = Episode.list(
                "showId = ?1 and publishedAt is not null and audioPath is not null order by publishedAt desc", show.id);
        return renderer.render(show, episodes, config.baseUrl());
    }
}
