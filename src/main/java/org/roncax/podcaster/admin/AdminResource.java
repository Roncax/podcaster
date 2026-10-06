package org.roncax.podcaster.admin;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.*;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.RestPath;
import org.roncax.podcaster.api.*;
import org.roncax.podcaster.config.PodcasterConfig;
import org.roncax.podcaster.domain.*;
import org.roncax.podcaster.ingestion.ConnectorRegistry;
import org.roncax.podcaster.llm.ChatModelRegistry;
import org.roncax.podcaster.runs.RunAlreadyActiveException;
import org.roncax.podcaster.runs.RunLauncher;
import org.roncax.podcaster.tts.TtsEngine;
import org.roncax.podcaster.prompts.*;
import org.roncax.podcaster.admin.PromptAdminViews.*;

@Path("/admin")
@Produces(MediaType.TEXT_HTML)
public class AdminResource {

    @CheckedTemplate
    static class Templates {
        static native TemplateInstance login(String error);
        static native TemplateInstance shows(List<Show> shows, ShowForm form, List<String> errors, Set<String> models, Set<String> voices);
        static native TemplateInstance show(Show show, ShowForm form, String formAction, List<Source> sources, List<Run> runs,
                                            List<Episode> episodes, List<String> errors, Set<String> models, Set<String> voices,
                                            Set<String> connectors, List<OverrideRow> promptOverrides);
        static native TemplateInstance prompts(List<PromptRow> rows);
        static native TemplateInstance prompt(String key, String description, Integer production, Integer draft,
                                              List<VersionRow> versions, int shownVersion, String shownBody,
                                              List<DiffRow> diff, String editorBody, String note, List<String> errors,
                                              List<Show> shows);
        static native TemplateInstance dryRun(PromptDryRun.Result result, String error);
        static native TemplateInstance runs(long showId, List<Run> runs);
        static native TemplateInstance sourceTest(SourceTestResult result);
        static native TemplateInstance episode(Show show, Episode episode);
    }

    @Inject PodcasterConfig config;
    @Inject ShowService shows;
    @Inject RunLauncher launcher;
    @Inject ChatModelRegistry models;
    @Inject ConnectorRegistry connectors;
    @Inject TtsEngine tts;
    @Inject Validator validator;
    @Inject PromptRegistry prompts;
    @Inject PromptDryRun dryRun;

    @GET
    public Response index() {
        return Response.seeOther(URI.create("/admin/shows")).build();
    }

    @GET
    @Path("/login")
    public TemplateInstance loginPage() {
        return Templates.login(null);
    }

    @POST
    @Path("/login")
    public Response login(@RestForm String key) {
        if (key == null || !key.equals(config.apiKey())) {
            return Response.ok(Templates.login("Wrong key")).build();
        }
        NewCookie cookie = new NewCookie.Builder(ApiKeyFilter.COOKIE).value(key).path("/")
                .httpOnly(true).sameSite(NewCookie.SameSite.STRICT).maxAge(60 * 60 * 24 * 30).build();
        return Response.seeOther(URI.create("/admin/shows")).cookie(cookie).build();
    }

    @POST
    @Path("/logout")
    public Response logout() {
        NewCookie cookie = new NewCookie.Builder(ApiKeyFilter.COOKIE).value("").path("/").maxAge(0).build();
        return Response.seeOther(URI.create("/admin/login")).cookie(cookie).build();
    }

    @GET
    @Path("/shows")
    public TemplateInstance listShows() {
        return Templates.shows(Show.listAll(), ShowForm.defaults(), List.of(), models.availableNames(), voices());
    }

    @POST
    @Path("/shows")
    public Response createShow(@BeanParam ShowForm form) {
        List<String> errors = ShowForm.newErrors();
        ShowRequest request = form.toRequest(errors);
        errors.addAll(beanErrors(request));
        errors.addAll(shows.validationErrors(request, null));
        if (errors.isEmpty()) {
            try {
                Show show = shows.create(request);
                return Response.seeOther(URI.create("/admin/shows/" + show.id)).build();
            } catch (InvalidRequestException e) {
                errors.addAll(e.errors());
            }
        }
        return Response.ok(Templates.shows(Show.listAll(), form, errors, models.availableNames(), voices())).build();
    }

    @GET
    @Path("/shows/{id}")
    public TemplateInstance showPage(@RestPath long id) {
        Show show = Show.<Show>findByIdOptional(id).orElseThrow(NotFoundException::new);
        return showTemplate(show, ShowForm.from(show), List.of());
    }

    @POST
    @Path("/shows/{id}")
    public Response updateShow(@RestPath long id, @BeanParam ShowForm form) {
        Show existing = Show.<Show>findByIdOptional(id).orElseThrow(NotFoundException::new);
        List<String> errors = ShowForm.newErrors();
        ShowRequest request = form.toRequest(errors);
        errors.addAll(beanErrors(request));
        errors.addAll(shows.validationErrors(request, id));
        if (errors.isEmpty()) {
            try {
                shows.update(id, request);
                return Response.seeOther(URI.create("/admin/shows/" + id)).build();
            } catch (InvalidRequestException e) {
                errors.addAll(e.errors());
            }
        }
        return Response.ok(showTemplate(existing, form, errors)).build();
    }

    @POST
    @Path("/shows/{id}/delete")
    public Response deleteShow(@RestPath long id) {
        shows.delete(id);
        return Response.seeOther(URI.create("/admin/shows")).build();
    }

    @POST
    @Path("/shows/{id}/sources")
    public Response addSource(@RestPath long id, @RestForm String connectorType, @RestForm String config,
                              @RestForm String fetchFullText) {
        try {
            shows.addSource(id, new SourceRequest(connectorType, parseConfig(config), fetchFullText != null, true));
            return Response.seeOther(URI.create("/admin/shows/" + id)).build();
        } catch (InvalidRequestException e) {
            Show show = Show.<Show>findByIdOptional(id).orElseThrow(NotFoundException::new);
            return Response.ok(showTemplate(show, ShowForm.from(show), e.errors())).build();
        }
    }

    @POST
    @Path("/sources/{id}/delete")
    public Response deleteSource(@RestPath long id) {
        Source source = Source.<Source>findByIdOptional(id).orElseThrow(NotFoundException::new);
        shows.deleteSource(id);
        return Response.seeOther(URI.create("/admin/shows/" + source.showId)).build();
    }

    @POST
    @Path("/sources/{id}/test")
    public TemplateInstance testSource(@RestPath long id) {
        return Templates.sourceTest(shows.testSource(id));
    }

    @POST
    @Path("/shows/{id}/run")
    public TemplateInstance runNow(@RestPath long id) {
        try {
            launcher.launch(id, RunTrigger.MANUAL);
        } catch (RunAlreadyActiveException ignored) {
            // the runs table already shows the active run
        }
        return Templates.runs(id, recentRuns(id));
    }

    @GET
    @Path("/shows/{id}/runs")
    public TemplateInstance runsFragment(@RestPath long id) {
        return Templates.runs(id, recentRuns(id));
    }

    @POST
    @Path("/runs/{id}/retry")
    public TemplateInstance retry(@RestPath long id) {
        Run run = Run.<Run>findByIdOptional(id).orElseThrow(NotFoundException::new);
        try {
            launcher.retry(id);
        } catch (IllegalStateException | RunAlreadyActiveException ignored) {
            // status is visible in the refreshed table
        }
        return Templates.runs(run.showId, recentRuns(run.showId));
    }

    @GET
    @Path("/episodes/{id}")
    public TemplateInstance episodePage(@RestPath long id) {
        Episode episode = Episode.<Episode>findByIdOptional(id).orElseThrow(NotFoundException::new);
        return Templates.episode(Show.findById(episode.showId), episode);
    }

    private TemplateInstance showTemplate(Show show, ShowForm form, List<String> errors) {
        List<Source> sources = Source.list("showId = ?1 order by id", show.id);
        List<Episode> episodes = Episode.find("showId = ?1 order by createdAt desc", show.id).page(0, 20).list();
        return Templates.show(show, form, "/admin/shows/" + show.id, sources, recentRuns(show.id), episodes, errors,
                models.availableNames(), voices(), connectors.types(), overrideRows(show.id));
    }

    private List<Run> recentRuns(long showId) {
        return QuarkusTransaction.requiringNew().call(() ->
                Run.<Run>find("showId = ?1 order by startedAt desc", showId).page(0, 15).list());
    }

    private Set<String> voices() {
        try {
            return tts.voices();
        } catch (Exception e) {
            return Set.of();
        }
    }

    private List<String> beanErrors(ShowRequest request) {
        List<String> errors = new ArrayList<>();
        for (ConstraintViolation<ShowRequest> v : validator.validate(request)) {
            errors.add(v.getPropertyPath() + " " + v.getMessage());
        }
        Collections.sort(errors);
        return errors;
    }

    static Map<String, String> parseConfig(String text) {
        Map<String, String> config = new LinkedHashMap<>();
        if (text == null) return config;
        for (String line : text.split("\\R")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            config.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        return config;
    }

    @GET
    @Path("/prompts")
    public TemplateInstance promptsPage() {
        List<PromptRow> rows = new ArrayList<>();
        for (PromptKey key : PromptKey.values()) {
            Map<PromptLabel, Integer> labels = prompts.labels(key);
            rows.add(new PromptRow(key.dbKey(), key.description(), labels.get(PromptLabel.PRODUCTION), labels.get(PromptLabel.DRAFT)));
        }
        return Templates.prompts(rows);
    }

    @GET
    @Path("/prompts/{key}")
    public TemplateInstance promptPage(@RestPath String key, @org.jboss.resteasy.reactive.RestQuery Integer v) {
        PromptKey k = promptKey(key);
        Map<PromptLabel, Integer> labels = prompts.labels(k);
        int shown = v != null ? v : labels.get(PromptLabel.DRAFT);
        String draftBody = prompts.version(k, labels.get(PromptLabel.DRAFT)).orElseThrow().body;
        return promptTemplate(k, shown, draftBody, null, List.of());
    }

    @POST
    @Path("/prompts/{key}/versions")
    public Response createPromptVersion(@RestPath String key, @RestForm String body, @RestForm String note) {
        PromptKey k = promptKey(key);
        try {
            PromptVersion created = prompts.createVersion(k, body, note);
            return Response.seeOther(URI.create("/admin/prompts/" + k.dbKey() + "?v=" + created.version)).build();
        } catch (InvalidPromptException e) {
            int shown = prompts.labels(k).get(PromptLabel.DRAFT);
            return Response.ok(promptTemplate(k, shown, body, note, e.errors())).build();
        }
    }

    @POST
    @Path("/prompts/{key}/labels/{label}")
    public Response setPromptLabel(@RestPath String key, @RestPath String label, @RestForm int version) {
        PromptKey k = promptKey(key);
        PromptLabel l = PromptLabel.fromDb(label).orElseThrow(NotFoundException::new);
        prompts.setLabel(k, l, version);
        return Response.seeOther(URI.create("/admin/prompts/" + k.dbKey() + "?v=" + version)).build();
    }

    @POST
    @Path("/prompts/dry-run")
    public TemplateInstance dryRunFragment(@RestForm long showId) {
        try {
            return Templates.dryRun(dryRun.run(showId), null);
        } catch (RuntimeException e) { // model errors, timeouts, broken drafts: show them instead of an empty swap
            return Templates.dryRun(null, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    @POST
    @Path("/shows/{id}/prompts")
    public Response saveShowPrompts(@RestPath long id, @RestForm String rank, @RestForm String segment,
                                    @RestForm String framing, @RestForm("json_repair") String jsonRepair) {
        Map<PromptKey, String> form = Map.of(PromptKey.RANK, nz(rank), PromptKey.SEGMENT, nz(segment),
                PromptKey.FRAMING, nz(framing), PromptKey.JSON_REPAIR, nz(jsonRepair));
        form.forEach((key, value) -> {
            if (value.isBlank()) prompts.unpin(id, key);
            else prompts.pin(id, key, Integer.parseInt(value.trim()));
        });
        return Response.seeOther(URI.create("/admin/shows/" + id)).build();
    }

    private TemplateInstance promptTemplate(PromptKey k, int shown, String editorBody, String note, List<String> errors) {
        Map<PromptLabel, Integer> labels = prompts.labels(k);
        Integer production = labels.get(PromptLabel.PRODUCTION);
        String shownBody = prompts.version(k, shown).orElseThrow(NotFoundException::new).body;
        String productionBody = prompts.version(k, production).orElseThrow().body;
        List<DiffRow> diff = LineDiff.diff(productionBody, shownBody).stream()
                .map(l -> new DiffRow(l.op() == '+' ? "diff-add" : l.op() == '-' ? "diff-del" : "diff-same",
                        String.valueOf(l.op()), l.text()))
                .toList();
        List<VersionRow> versions = prompts.versions(k).stream()
                .map(v -> new VersionRow(v.version, v.note == null ? "" : v.note, v.createdAt.toString(),
                        String.join(", ", prompts.labelsOf(k, v.version))))
                .toList();
        return Templates.prompt(k.dbKey(), k.description(), production, labels.get(PromptLabel.DRAFT), versions, shown,
                shownBody, diff, editorBody, note, errors, Show.listAll());
    }

    private List<OverrideRow> overrideRows(long showId) {
        Map<PromptKey, Integer> pinned = prompts.overrides(showId);
        List<OverrideRow> rows = new ArrayList<>();
        for (PromptKey key : PromptKey.values()) {
            List<Integer> versions = prompts.versions(key).stream().map(v -> v.version).toList();
            rows.add(new OverrideRow(key.dbKey(), pinned.get(key), versions));
        }
        return rows;
    }

    private static PromptKey promptKey(String key) {
        return PromptKey.fromDb(key).orElseThrow(NotFoundException::new);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
