package org.roncax.podcaster.api;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestResponse;
import org.roncax.podcaster.domain.Show;
import org.roncax.podcaster.prompts.PromptKey;
import org.roncax.podcaster.prompts.PromptResolver;
import org.roncax.podcaster.testing.*;

@Path("/api/shows/{id}/test")
@Produces(MediaType.APPLICATION_JSON)
public class TestResource {
    @Inject TestSessionStore store;
    @Inject TestRunner runner;
    @Inject SourceDryRun sources;
    @Inject PromptDryRun prompts;
    @Inject VoiceDryRun voice;
    @Inject SinglePromptRun single;

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

    public record PromptStepRequest(String mode, String items) {}

    @POST
    @Path("/prompts")
    @Consumes(MediaType.APPLICATION_JSON)
    public RestResponse<TestViews.Session> dryRunPrompts(@RestPath long id, PromptStepRequest request) {
        requireShow(id);
        PromptResolver.Mode mode = parse(PromptResolver.Mode.class, request == null ? null : request.mode(), PromptResolver.Mode.DRAFT, "mode");
        PromptDryRun.ItemsSource items = parse(PromptDryRun.ItemsSource.class, request == null ? null : request.items(),
                PromptDryRun.ItemsSource.SESSION, "items");
        PromptDryRun.Prepared prepared = prompts.prepare(id, mode, items, store.session(id));
        runner.start(id, TestSession.Step.PROMPTS, s -> s.dryRun(prompts.execute(prepared), prepared.candidates()));
        return RestResponse.status(Response.Status.ACCEPTED, get(id));
    }

    public record SinglePromptRequest(String mode, String items, Integer story) {}

    @POST
    @Path("/prompt/{key}")
    @Consumes(MediaType.APPLICATION_JSON)
    public RestResponse<TestViews.Session> singlePrompt(@RestPath long id, @RestPath String key, SinglePromptRequest request) {
        requireShow(id);
        PromptKey k = PromptKey.fromDb(key).orElseThrow(() -> new InvalidRequestException(List.of("Unknown prompt '" + key + "'")));
        PromptResolver.Mode mode = parse(PromptResolver.Mode.class, request == null ? null : request.mode(), PromptResolver.Mode.DRAFT, "mode");
        PromptDryRun.ItemsSource items = parse(PromptDryRun.ItemsSource.class, request == null ? null : request.items(),
                PromptDryRun.ItemsSource.SESSION, "items");
        SinglePromptRun.Prepared prepared = single.prepare(id, k, mode, items, request == null ? null : request.story(), store.session(id));
        runner.start(id, TestSession.Step.PROMPT_ONE, s -> s.singlePrompt(single.execute(prepared)));
        return RestResponse.status(Response.Status.ACCEPTED, get(id));
    }

    @POST
    @Path("/voice")
    @Consumes(MediaType.APPLICATION_JSON)
    public RestResponse<TestViews.Session> voice(@RestPath long id, VoiceDryRun.Request request) {
        requireShow(id);
        VoiceDryRun.Prepared prepared = voice.prepare(id, request, store.session(id));
        runner.start(id, TestSession.Step.VOICE, s -> s.clip(voice.execute(prepared)));
        return RestResponse.status(Response.Status.ACCEPTED, get(id));
    }

    @GET
    @Path("/voice.wav")
    @Produces("audio/wav")
    public Response clip(@RestPath long id) {
        return wav(store, id);
    }

    /** The last clip, never cached (each new clip replaces it at the same URL). */
    public static Response wav(TestSessionStore store, long id) {
        byte[] wav = store.find(id).map(TestSession::clip).map(TestSession.Clip::wav).orElseThrow(NotFoundException::new);
        return Response.ok(wav, "audio/wav").header("Cache-Control", "no-store").build();
    }

    static <E extends Enum<E>> E parse(Class<E> type, String value, E fallback, String field) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException(List.of("Unknown " + field + " '" + value + "'"));
        }
    }

    static String audioUrl(long id) { return "/api/shows/" + id + "/test/voice.wav"; }

    public static void requireShow(long id) {
        if (QuarkusTransaction.requiringNew().call(() -> Show.findById(id)) == null) throw new NotFoundException();
    }
}
