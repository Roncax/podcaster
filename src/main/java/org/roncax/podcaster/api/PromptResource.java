package org.roncax.podcaster.api;

import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
    public List<PromptSummary> list() {
        return Arrays.stream(PromptKey.values()).map(this::summary).toList();
    }

    @GET
    @Path("/{key}/versions")
    public List<VersionSummary> versions(@RestPath String key) {
        PromptKey k = key(key);
        return registry.versions(k).stream()
                .map(v -> new VersionSummary(v.version, v.note, v.createdAt, registry.labelsOf(k, v.version)))
                .toList();
    }

    @GET
    @Path("/{key}/versions/{n}")
    public VersionDetail version(@RestPath String key, @RestPath int n) {
        PromptKey k = key(key);
        return registry.version(k, n).map(v -> detail(k, v)).orElseThrow(NotFoundException::new);
    }

    @POST
    @Path("/{key}/versions")
    public RestResponse<VersionDetail> create(@RestPath String key, @Valid NewVersion request) {
        PromptKey k = key(key);
        PromptVersion v = registry.createVersion(k, request.body(), request.note());
        return RestResponse.status(Response.Status.CREATED, detail(k, v));
    }

    @PUT
    @Path("/{key}/labels/{label}")
    public PromptSummary setLabel(@RestPath String key, @RestPath String label, @Valid VersionRef request) {
        PromptKey k = key(key);
        PromptLabel l = PromptLabel.fromDb(label).orElseThrow(NotFoundException::new);
        registry.setLabel(k, l, request.version());
        return summary(k);
    }

    @POST
    @Path("/dry-run")
    public PromptDryRun.Result dryRun(@Valid DryRunRequest request) {
        return dryRun.run(request.showId());
    }

    private PromptSummary summary(PromptKey k) {
        Map<PromptLabel, Integer> labels = registry.labels(k);
        return new PromptSummary(k.dbKey(), k.description(), labels.get(PromptLabel.PRODUCTION), labels.get(PromptLabel.DRAFT));
    }

    private VersionDetail detail(PromptKey k, PromptVersion v) {
        return new VersionDetail(v.version, v.note, v.createdAt, registry.labelsOf(k, v.version), v.body);
    }

    static PromptKey key(String key) {
        return PromptKey.fromDb(key).orElseThrow(NotFoundException::new);
    }
}
