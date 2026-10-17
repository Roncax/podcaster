package org.roncax.podcaster.api;

import io.quarkus.narayana.jta.QuarkusTransaction;
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
import org.roncax.podcaster.domain.Show;
import org.roncax.podcaster.prompts.*;

@Path("/api/shows/{id}/prompts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ShowPromptResource {
    @Inject PromptRegistry registry;

    @GET
    public List<PromptSummary> list(@RestPath long id) {
        requireShow(id);
        return Arrays.stream(PromptKey.values()).map(k -> summary(id, k)).toList();
    }

    @GET
    @Path("/{key}/versions")
    public List<VersionSummary> versions(@RestPath long id, @RestPath String key) {
        requireShow(id);
        PromptKey k = PromptResource.key(key);
        return registry.versions(id, k).stream()
                .map(v -> new VersionSummary(v.version, v.note, v.createdAt, registry.labelsOf(id, k, v.version))).toList();
    }

    @GET
    @Path("/{key}/versions/{n}")
    public VersionDetail version(@RestPath long id, @RestPath String key, @RestPath int n) {
        PromptKey k = PromptResource.key(key);
        return registry.version(id, k, n).map(v -> detail(id, k, v)).orElseThrow(NotFoundException::new);
    }

    @POST
    @Path("/{key}/versions")
    public RestResponse<VersionDetail> create(@RestPath long id, @RestPath String key, @Valid NewVersion request) {
        PromptKey k = PromptResource.key(key);
        PromptVersion v = registry.createVersion(id, k, request.body(), request.note());
        return RestResponse.status(Response.Status.CREATED, detail(id, k, v));
    }

    @PUT
    @Path("/{key}/labels/{label}")
    public PromptSummary setLabel(@RestPath long id, @RestPath String key, @RestPath String label, @Valid VersionRef request) {
        PromptKey k = PromptResource.key(key);
        PromptLabel l = PromptLabel.fromDb(label).orElseThrow(NotFoundException::new);
        registry.setLabel(id, k, l, request.version());
        return summary(id, k);
    }

    @POST
    @Path("/{key}/reset")
    public RestResponse<VersionDetail> reset(@RestPath long id, @RestPath String key) {
        PromptKey k = PromptResource.key(key);
        return RestResponse.status(Response.Status.CREATED, detail(id, k, registry.resetToDefault(id, k)));
    }

    private PromptSummary summary(long id, PromptKey k) {
        Map<PromptLabel, Integer> labels = registry.labels(id, k);
        return new PromptSummary(k.dbKey(), k.description(), labels.get(PromptLabel.PRODUCTION), labels.get(PromptLabel.DRAFT));
    }

    private VersionDetail detail(long id, PromptKey k, PromptVersion v) {
        return new VersionDetail(v.version, v.note, v.createdAt, registry.labelsOf(id, k, v.version), v.body);
    }

    private static void requireShow(long id) {
        if (QuarkusTransaction.requiringNew().call(() -> Show.findById(id)) == null) throw new NotFoundException();
    }
}
