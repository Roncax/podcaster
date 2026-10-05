package org.roncax.podcaster.api;

import io.quarkus.panache.common.Sort;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.*;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestQuery;
import org.jboss.resteasy.reactive.RestResponse;
import org.roncax.podcaster.domain.Run;
import org.roncax.podcaster.domain.RunStatus;
import org.roncax.podcaster.runs.RunLauncher;

@Path("/api/runs")
@Produces(MediaType.APPLICATION_JSON)
public class RunResource {
    private static final int LIMIT = 100;

    @Inject RunLauncher launcher;

    @GET
    public List<Run> list(@RestQuery Long showId, @RestQuery RunStatus status) {
        List<String> conditions = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        if (showId != null) { conditions.add("showId = :showId"); params.put("showId", showId); }
        if (status != null) { conditions.add("status = :status"); params.put("status", status); }
        Sort sort = Sort.descending("startedAt");
        return (conditions.isEmpty() ? Run.<Run>findAll(sort) : Run.<Run>find(String.join(" and ", conditions), sort, params))
                .page(0, LIMIT).list();
    }

    @GET
    @Path("/{id}")
    public Run get(@RestPath long id) {
        return Run.<Run>findByIdOptional(id).orElseThrow(NotFoundException::new);
    }

    @POST
    @Path("/{id}/retry")
    public RestResponse<RunCreated> retry(@RestPath long id) {
        try {
            launcher.retry(id);
        } catch (IllegalStateException e) {
            throw new ClientErrorException(e.getMessage(), Response.Status.CONFLICT);
        }
        return RestResponse.status(Response.Status.ACCEPTED, new RunCreated(id));
    }
}
