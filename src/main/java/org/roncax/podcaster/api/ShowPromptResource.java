package org.roncax.podcaster.api;

import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jboss.resteasy.reactive.RestPath;
import org.roncax.podcaster.api.PromptViews.VersionRef;
import org.roncax.podcaster.prompts.PromptKey;
import org.roncax.podcaster.prompts.PromptRegistry;

@Path("/api/shows/{id}/prompts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ShowPromptResource {
    @Inject PromptRegistry registry;

    @GET
    public Map<String, Integer> overrides(@RestPath long id) {
        Map<String, Integer> out = new LinkedHashMap<>();
        registry.overrides(id).forEach((k, v) -> out.put(k.dbKey(), v));
        return out;
    }

    @PUT
    @Path("/{key}")
    public Map<String, Integer> pin(@RestPath long id, @RestPath String key, @Valid VersionRef request) {
        registry.pin(id, PromptResource.key(key), request.version());
        return overrides(id);
    }

    @DELETE
    @Path("/{key}")
    public void unpin(@RestPath long id, @RestPath String key) {
        PromptKey k = PromptResource.key(key);
        registry.unpin(id, k);
    }
}
