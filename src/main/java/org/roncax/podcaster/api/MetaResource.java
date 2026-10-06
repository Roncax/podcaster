package org.roncax.podcaster.api;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.ServiceUnavailableException;
import jakarta.ws.rs.core.MediaType;
import java.util.Set;
import org.roncax.podcaster.ingestion.ConnectorRegistry;
import org.roncax.podcaster.llm.ChatModelRegistry;
import org.roncax.podcaster.tts.TtsEngine;

@Path("/api/meta")
@Produces(MediaType.APPLICATION_JSON)
public class MetaResource {
    @Inject ChatModelRegistry models;
    @Inject ConnectorRegistry connectors;
    @Inject TtsEngine tts;

    @GET
    @Path("/models")
    public Set<String> models() { return models.availableNames(); }

    @GET
    @Path("/connectors")
    public Set<String> connectors() { return connectors.types(); }

    @GET
    @Path("/voices")
    public Set<String> voices() {
        try {
            return tts.voices();
        } catch (Exception e) {
            throw new ServiceUnavailableException("Piper unreachable: " + e.getMessage());
        }
    }
}
