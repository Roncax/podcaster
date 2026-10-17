package org.roncax.podcaster.api;

import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Arrays;
import java.util.List;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestResponse;
import org.roncax.podcaster.api.PromptViews.*;
import org.roncax.podcaster.prompts.*;

@Path("/api/prompts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PromptResource {
    @Inject PromptRegistry registry;
    @Inject PromptDryRun dryRun;

    @GET
    public List<DefaultPromptSummary> list() {
        return Arrays.stream(PromptKey.values())
                .map(k -> new DefaultPromptSummary(k.dbKey(), k.description(), registry.latestDefault(k).version)).toList();
    }

    @GET
    @Path("/{key}/versions")
    public List<VersionSummary> versions(@RestPath String key) {
        return registry.defaultVersions(key(key)).stream()
                .map(v -> new VersionSummary(v.version, v.note, v.createdAt, List.of()))
                .toList();
    }

    @GET
    @Path("/{key}/versions/{n}")
    public VersionDetail version(@RestPath String key, @RestPath int n) {
        return registry.defaultVersion(key(key), n).map(PromptResource::detail).orElseThrow(NotFoundException::new);
    }

    @POST
    @Path("/{key}/versions")
    public RestResponse<VersionDetail> create(@RestPath String key, @Valid NewVersion request) {
        PromptVersion v = registry.createDefaultVersion(key(key), request.body(), request.note());
        return RestResponse.status(Response.Status.CREATED, detail(v));
    }

    @POST
    @Path("/dry-run")
    public PromptDryRun.Result dryRun(@Valid DryRunRequest request) {
        return dryRun.run(request.showId());
    }

    private static VersionDetail detail(PromptVersion v) {
        return new VersionDetail(v.version, v.note, v.createdAt, List.of(), v.body);
    }

    static PromptKey key(String key) {
        return PromptKey.fromDb(key).orElseThrow(NotFoundException::new);
    }
}
