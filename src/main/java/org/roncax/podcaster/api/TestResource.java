package org.roncax.podcaster.api;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestResponse;
import org.roncax.podcaster.domain.Show;
import org.roncax.podcaster.testing.*;

@Path("/api/shows/{id}/test")
@Produces(MediaType.APPLICATION_JSON)
public class TestResource {
    @Inject TestSessionStore store;
    @Inject TestRunner runner;
    @Inject SourceDryRun sources;

    @GET
    public TestViews.Session get(@RestPath long id) {
        requireShow(id);
        return store.find(id).map(s -> TestViews.of(s, audioUrl(id))).orElseGet(() -> TestViews.empty(id));
    }

    @POST
    @Path("/sources")
    @Consumes(MediaType.WILDCARD)
    public RestResponse<TestViews.Session> fetchSources(@RestPath long id) {
        requireShow(id);
        runner.start(id, TestSession.Step.SOURCES, s -> s.fetched(sources.run(id)));
        return RestResponse.status(Response.Status.ACCEPTED, get(id));
    }

    static String audioUrl(long id) { return "/api/shows/" + id + "/test/voice.wav"; }

    static void requireShow(long id) {
        if (QuarkusTransaction.requiringNew().call(() -> Show.findById(id)) == null) throw new NotFoundException();
    }
}
