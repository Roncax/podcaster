package org.roncax.podcaster.api;

import io.quarkus.panache.common.Sort;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestResponse;
import org.roncax.podcaster.domain.RunTrigger;
import org.roncax.podcaster.domain.Show;
import org.roncax.podcaster.domain.Source;
import org.roncax.podcaster.runs.RunLauncher;

@Path("/api/shows")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ShowResource {
    @Inject ShowService shows;
    @Inject RunLauncher launcher;

    @GET
    public List<Show> list() {
        return Show.listAll(Sort.by("name"));
    }

    @POST
    public RestResponse<Show> create(@Valid ShowRequest request) {
        return RestResponse.status(Response.Status.CREATED, shows.create(request));
    }

    @GET
    @Path("/{id}")
    public Show get(@RestPath long id) {
        return Show.<Show>findByIdOptional(id).orElseThrow(NotFoundException::new);
    }

    @PUT
    @Path("/{id}")
    public Show update(@RestPath long id, @Valid ShowRequest request) {
        return shows.update(id, request);
    }

    @DELETE
    @Path("/{id}")
    public void delete(@RestPath long id) {
        shows.delete(id);
    }

    @GET
    @Path("/{id}/sources")
    public List<Source> sources(@RestPath long id) {
        return Source.list("showId = ?1 order by id", id);
    }

    @POST
    @Path("/{id}/sources")
    public RestResponse<Source> addSource(@RestPath long id, @Valid SourceRequest request) {
        return RestResponse.status(Response.Status.CREATED, shows.addSource(id, request));
    }

    @POST
    @Path("/{id}/runs")
    public RestResponse<RunCreated> run(@RestPath long id) {
        return RestResponse.status(Response.Status.ACCEPTED, new RunCreated(launcher.launch(id, RunTrigger.MANUAL)));
    }
}
