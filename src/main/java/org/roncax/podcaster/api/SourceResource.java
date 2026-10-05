package org.roncax.podcaster.api;

import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestPath;
import org.roncax.podcaster.domain.Source;

@Path("/api/sources")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SourceResource {
    @Inject ShowService shows;

    @PUT
    @Path("/{id}")
    public Source update(@RestPath long id, @Valid SourceRequest request) {
        return shows.updateSource(id, request);
    }

    @DELETE
    @Path("/{id}")
    public void delete(@RestPath long id) {
        shows.deleteSource(id);
    }

    @POST
    @Path("/{id}/test")
    public SourceTestResult test(@RestPath long id) {
        return shows.testSource(id);
    }
}
