package org.roncax.podcaster.admin;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.RestHeader;
import org.jboss.resteasy.reactive.RestPath;
import org.roncax.podcaster.api.InvalidRequestException;
import org.roncax.podcaster.api.TestResource;
import org.roncax.podcaster.api.TestViews;
import org.roncax.podcaster.domain.Show;
import org.roncax.podcaster.domain.OutlineSegment;
import org.roncax.podcaster.prompts.NoCandidatesException;
import org.roncax.podcaster.prompts.PromptKey;
import org.roncax.podcaster.prompts.PromptResolver;
import org.roncax.podcaster.testing.*;

/** Admin Test tab: chains source fetch, prompt dry-run and a voice clip without storing anything. */
@Path("/admin/shows/{id}/test")
@Produces(MediaType.TEXT_HTML)
public class TestPages {
    @CheckedTemplate
    static class Templates {
        static native TemplateInstance panel(long showId, TestViews.Session session, boolean running, List<String> errors,
                                             Set<String> voices, Form form, int maxChars, List<String> promptKeys, List<String> stories);
        static native TemplateInstance status(long showId, boolean running);
    }

    /** Form values to render: the last submitted ones, else the show defaults. */
    public record Form(String mode, String items, String text, String voiceId, String lengthScale,
                       String singleKey, String singleMode, String singleItems, int story) {}

    @Inject TestSessionStore store;
    @Inject TestRunner runner;
    @Inject SourceDryRun sources;
    @Inject PromptDryRun prompts;
    @Inject VoiceDryRun voice;
    @Inject SinglePromptRun single;
    @Inject AdminSupport support;

    @GET
    public TemplateInstance panel(@RestPath long id) {
        return panel(id, List.of(), null);
    }

    /**
     * Small poller shown while a step runs, so the panel (form input, open details, playing audio) is not re-swapped
     * every 2 s. Once nothing runs it stops polling and fires {@code test-done}, which refreshes the panel once.
     */
    @GET
    @Path("/status")
    public Response status(@RestPath long id) {
        TestResource.requireShow(id);
        boolean running = running(id);
        Response.ResponseBuilder ok = Response.ok(Templates.status(id, running));
        return running ? ok.build() : ok.header("HX-Trigger", "test-done").build();
    }

    @POST
    @Path("/sources")
    public Response fetch(@RestPath long id, @RestHeader("HX-Request") String htmx) {
        TestResource.requireShow(id);
        return start(id, htmx, () -> runner.start(id, TestSession.Step.SOURCES, s -> s.fetched(sources.run(id))));
    }

    @POST
    @Path("/prompts")
    public Response dryRun(@RestPath long id, @RestForm String mode, @RestForm String items, @RestHeader("HX-Request") String htmx) {
        TestResource.requireShow(id);
        store.session(id).promptInput(new TestSession.PromptInput(mode, items));
        return start(id, htmx, () -> {
            PromptResolver.Mode m = "production".equals(mode) ? PromptResolver.Mode.PRODUCTION : PromptResolver.Mode.DRAFT;
            PromptDryRun.ItemsSource src = "database".equals(items) ? PromptDryRun.ItemsSource.DATABASE : PromptDryRun.ItemsSource.SESSION;
            PromptDryRun.Prepared prepared = prompts.prepare(id, m, src, store.session(id));
            runner.start(id, TestSession.Step.PROMPTS, s -> s.dryRun(prompts.execute(prepared), prepared.candidates()));
        });
    }

    @POST
    @Path("/prompt")
    public Response singlePrompt(@RestPath long id, @RestForm String key, @RestForm String mode, @RestForm String items,
                                 @RestForm String story, @RestHeader("HX-Request") String htmx) {
        TestResource.requireShow(id);
        store.session(id).singleInput(new TestSession.SingleInput(key, mode, items, story));
        return start(id, htmx, () -> {
            PromptKey k = PromptKey.fromDb(key).orElseThrow(() -> new InvalidRequestException(List.of("Unknown prompt '" + key + "'")));
            PromptResolver.Mode m = "production".equals(mode) ? PromptResolver.Mode.PRODUCTION : PromptResolver.Mode.DRAFT;
            PromptDryRun.ItemsSource src = "database".equals(items) ? PromptDryRun.ItemsSource.DATABASE : PromptDryRun.ItemsSource.SESSION;
            Integer index;
            try {
                index = story == null || story.isBlank() ? null : Integer.valueOf(story.trim());
            } catch (NumberFormatException e) {
                throw new InvalidRequestException(List.of("Story must be a number"));
            }
            SinglePromptRun.Prepared prepared = single.prepare(id, k, m, src, index, store.session(id));
            runner.start(id, TestSession.Step.PROMPT_ONE, s -> s.singlePrompt(single.execute(prepared)));
        });
    }

    @POST
    @Path("/voice")
    public Response synthesize(@RestPath long id, @RestForm String text, @RestForm String voiceId, @RestForm String lengthScale,
                               @RestHeader("HX-Request") String htmx) {
        TestResource.requireShow(id);
        store.session(id).voiceInput(new TestSession.VoiceInput(text, voiceId, lengthScale));
        return start(id, htmx, () -> {
            Double scale = lengthScale == null || lengthScale.isBlank() ? null : Double.valueOf(lengthScale.trim());
            VoiceDryRun.Prepared prepared = voice.prepare(id, new VoiceDryRun.Request(text, null, voiceId, scale), store.session(id));
            runner.start(id, TestSession.Step.VOICE, s -> s.clip(voice.execute(prepared)));
        });
    }

    @GET
    @Path("/voice.wav")
    @Produces("audio/wav")
    public Response clip(@RestPath long id) {
        return TestResource.wav(store, id);
    }

    private Response start(long id, String htmx, Runnable action) {
        List<String> errors = List.of();
        try {
            action.run();
        } catch (InvalidRequestException e) {
            errors = e.errors();
        } catch (NoCandidatesException | MissingTestInputException | TestStepBusyException e) {
            errors = List.of(e.getMessage());
        } catch (NumberFormatException e) {
            errors = List.of("Length scale must be a number");
        }
        if (htmx == null && errors.isEmpty()) return Response.seeOther(URI.create("/admin/shows/" + id + "?tab=test")).build();
        return Response.ok(panel(id, errors, null)).build();
    }

    /** {@code prompt} (from a prompt page link) preselects the single-prompt key over the last submitted one. */
    TemplateInstance panel(long id, List<String> errors, String prompt) {
        Show show = QuarkusTransaction.requiringNew().call(() -> Show.<Show>findByIdOptional(id).orElseThrow(NotFoundException::new));
        Optional<TestSession> found = store.find(id);
        TestViews.Session session = found.map(s -> TestViews.of(s, "/admin/shows/" + id + "/test/voice.wav"))
                .orElseGet(() -> TestViews.empty(id));
        List<String> stories = session.dryRun() == null ? List.of()
                : session.dryRun().outline().segments().stream().map(OutlineSegment::headline).toList();
        List<String> keys = SinglePromptRun.KEYS.stream().map(PromptKey::dbKey).toList();
        return Templates.panel(id, session, running(id), errors, support.voices(),
                form(show, found, !session.items().isEmpty(), prompt), VoiceDryRun.MAX_CHARS, keys, stories);
    }

    private boolean running(long id) {
        return store.find(id).map(TestSession::running).orElse(false);
    }

    static Form form(Show show, Optional<TestSession> session, boolean hasItems, String prompt) {
        TestSession.PromptInput p = session.map(TestSession::promptInput).orElse(null);
        TestSession.VoiceInput v = session.map(TestSession::voiceInput).orElse(null);
        TestSession.SingleInput o = session.map(TestSession::singleInput).orElse(null);
        String key = prompt != null ? prompt : o != null && o.key() != null ? o.key() : "rank";
        String singleMode = o != null && "production".equals(o.mode()) ? "production" : "draft";
        String singleItems = !hasItems || (o != null && "database".equals(o.items())) ? "database" : "session";
        int story = 0;
        try {
            if (o != null && o.story() != null && !o.story().isBlank()) story = Integer.parseInt(o.story().trim());
        } catch (NumberFormatException ignored) {
            // keep the first story selected
        }
        String mode = p != null && "production".equals(p.mode()) ? "production" : "draft";
        String items = !hasItems || (p != null && "database".equals(p.items())) ? "database" : "session";
        String text = v == null || v.text() == null ? "" : v.text();
        String voiceId = v == null || v.voiceId() == null || v.voiceId().isBlank() ? show.voiceId : v.voiceId();
        String scale = v == null || v.lengthScale() == null || v.lengthScale().isBlank() ? String.valueOf(show.lengthScale) : v.lengthScale();
        return new Form(mode, items, text, voiceId, scale, key, singleMode, singleItems, story);
    }
}
