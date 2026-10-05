package org.roncax.podcaster.api;

import io.quarkus.panache.common.Sort;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.nio.file.Files;
import java.util.List;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestQuery;
import org.roncax.podcaster.domain.Episode;
import org.roncax.podcaster.publishing.AudioStorage;

@Path("/api/episodes")
@Produces(MediaType.APPLICATION_JSON)
public class EpisodeResource {
    private static final int LIMIT = 100;

    @Inject AudioStorage storage;

    @GET
    public List<Episode> list(@RestQuery Long showId) {
        Sort sort = Sort.descending("createdAt");
        return (showId == null ? Episode.<Episode>findAll(sort) : Episode.<Episode>find("showId", sort, showId))
                .page(0, LIMIT).list();
    }

    @GET
    @Path("/{id}")
    public Episode get(@RestPath long id) {
        return Episode.<Episode>findByIdOptional(id).orElseThrow(NotFoundException::new);
    }

    @GET
    @Path("/{id}/audio")
    @Produces("audio/mpeg")
    public Response audio(@RestPath long id) {
        Episode episode = Episode.<Episode>findByIdOptional(id).orElseThrow(NotFoundException::new);
        if (episode.audioPath == null || !storage.exists(episode.audioPath)) throw new NotFoundException();
        java.nio.file.Path file = storage.resolve(episode.audioPath);
        return Response.ok(file.toFile(), "audio/mpeg")
                .header("Content-Disposition", "attachment; filename=\"" + file.getFileName() + "\"")
                .build();
    }
}
