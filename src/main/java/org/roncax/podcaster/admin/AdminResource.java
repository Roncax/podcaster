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
import org.roncax.podcaster.admin.AdminViews.*;

@Path("/admin")
@Produces(MediaType.TEXT_HTML)
public class AdminResource {

    @CheckedTemplate
    static class Templates {
        static native TemplateInstance login(String error);
        static native TemplateInstance dashboard(List<ShowCard> shows, List<RunRow> runs, List<Attention> attention);
        static native TemplateInstance settings(Set<String> models, Set<String> voices, Set<String> connectors,
                                                boolean telegram, String baseUrl, String version);
        static native TemplateInstance prompts(List<PromptRow> rows);
        static native TemplateInstance prompt(String key, String description, Integer production, Integer draft,
                                              List<VersionRow> versions, int shownVersion, String shownBody,
                                              List<DiffRow> diff, String editorBody, String note, List<String> errors,
                                              List<Show> shows);
        static native TemplateInstance dryRun(PromptDryRun.Result result, String error);
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
    @Inject AdminSupport support;

    @GET
    public TemplateInstance dashboard() {
        List<Show> all = QuarkusTransaction.requiringNew().call(() -> Show.<Show>listAll(io.quarkus.panache.common.Sort.by("name")));
        Map<Long, String> names = new HashMap<>();
        all.forEach(s -> names.put(s.id, s.name));
        List<RunRow> runs = QuarkusTransaction.requiringNew().call(() ->
                Run.<Run>find("order by startedAt desc").page(0, 8).list()).stream()
                .map(r -> support.runRow(r, names.getOrDefault(r.showId, "Deleted show"))).toList();
        return Templates.dashboard(all.stream().map(support::showCard).toList(), runs, support.attention());
    }

    @GET
    @Path("/settings")
    public TemplateInstance settings() {
        boolean telegram = config.telegram().botToken().isPresent() && config.telegram().chatId().isPresent();
        String version = org.eclipse.microprofile.config.ConfigProvider.getConfig()
                .getOptionalValue("quarkus.application.version", String.class).orElse("dev");
        return Templates.settings(models.availableNames(), support.voices(), connectors.types(), telegram, config.baseUrl(), version);
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
        return Response.seeOther(URI.create("/admin")).cookie(cookie).build();
    }

    @POST
    @Path("/logout")
    public Response logout() {
        NewCookie cookie = new NewCookie.Builder(ApiKeyFilter.COOKIE).value("").path("/").maxAge(0).build();
        return Response.seeOther(URI.create("/admin/login")).cookie(cookie).build();
    }

    @GET
    @Path("/episodes/{id}")
    public TemplateInstance episodePage(@RestPath long id) {
        Episode episode = Episode.<Episode>findByIdOptional(id).orElseThrow(NotFoundException::new);
        return Templates.episode(Show.findById(episode.showId), episode);
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

    private static PromptKey promptKey(String key) {
        return PromptKey.fromDb(key).orElseThrow(NotFoundException::new);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
