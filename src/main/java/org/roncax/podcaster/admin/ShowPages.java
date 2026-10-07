package org.roncax.podcaster.admin;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.panache.common.Sort;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.RestHeader;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestQuery;
import org.roncax.podcaster.admin.AdminViews.*;
import org.roncax.podcaster.admin.PromptAdminViews.OverrideRow;
import org.roncax.podcaster.api.*;
import org.roncax.podcaster.domain.*;
import org.roncax.podcaster.generation.Prompts;
import org.roncax.podcaster.ingestion.ConnectorRegistry;
import org.roncax.podcaster.ingestion.reddit.RedditSourceConnector;
import org.roncax.podcaster.ingestion.RssSourceConnector;
import org.roncax.podcaster.llm.ChatModelRegistry;
import org.roncax.podcaster.prompts.PromptKey;
import org.roncax.podcaster.prompts.PromptRegistry;
import org.roncax.podcaster.runs.RunAlreadyActiveException;
import org.roncax.podcaster.runs.RunLauncher;

@Path("/admin")
@Produces(MediaType.TEXT_HTML)
public class ShowPages {
    static final Set<String> TABS = Set.of("overview", "sources", "episodes", "settings", "prompts");

    @CheckedTemplate
    static class Templates {
        static native TemplateInstance list(List<ShowCard> shows, ShowForm form, List<String> errors, Set<String> models, Set<String> voices);
        static native TemplateInstance detail(Show show, String tab, List<String> chips, LiveRun live,
                                              List<EpisodeLink> episodes, List<RunRow> runs, List<SourceRow> sources,
                                              List<String> sourceErrors, ShowForm form, String formAction, List<String> errors,
                                              Set<String> models, Set<String> voices, Set<String> connectors,
                                              List<OverrideRow> overrides, String cronText);
        static native TemplateInstance live(long showId, LiveRun live);
        static native TemplateInstance sourceTest(SourceTestResult result);
    }

    @Inject ShowService shows;
    @Inject RunLauncher launcher;
    @Inject ChatModelRegistry models;
    @Inject ConnectorRegistry connectors;
    @Inject PromptRegistry prompts;
    @Inject AdminSupport support;

    @GET
    @Path("/shows")
    public TemplateInstance list() {
        return listTemplate(ShowForm.defaults(), List.of());
    }

    @POST
    @Path("/shows")
    public Response create(@BeanParam ShowForm form) {
        List<String> errors = ShowForm.newErrors();
        ShowRequest request = form.toRequest(errors);
        errors.addAll(support.beanErrors(request));
        errors.addAll(shows.validationErrors(request, null));
        if (errors.isEmpty()) {
            try {
                Show show = shows.create(request);
                return Response.seeOther(URI.create("/admin/shows/" + show.id)).build();
            } catch (InvalidRequestException e) {
                errors.addAll(e.errors());
            }
        }
        return Response.ok(listTemplate(form, errors)).build();
    }

    @GET
    @Path("/shows/{id}")
    public TemplateInstance detail(@RestPath long id, @RestQuery String tab) {
        Show show = find(id);
        return detailTemplate(show, tab, ShowForm.from(show), List.of(), List.of());
    }

    @POST
    @Path("/shows/{id}")
    public Response update(@RestPath long id, @BeanParam ShowForm form) {
        Show existing = find(id);
        List<String> errors = ShowForm.newErrors();
        ShowRequest request = form.toRequest(errors);
        errors.addAll(support.beanErrors(request));
        errors.addAll(shows.validationErrors(request, id));
        if (errors.isEmpty()) {
            try {
                shows.update(id, request);
                return Response.seeOther(URI.create("/admin/shows/" + id + "?tab=settings")).build();
            } catch (InvalidRequestException e) {
                errors.addAll(e.errors());
            }
        }
        return Response.ok(detailTemplate(existing, "settings", form, errors, List.of())).build();
    }

    @POST
    @Path("/shows/{id}/delete")
    public Response delete(@RestPath long id) {
        shows.delete(id);
        return Response.seeOther(URI.create("/admin/shows")).build();
    }

    @POST
    @Path("/shows/{id}/sources")
    public Response addSource(@RestPath long id, @RestForm String connectorType, @RestForm String url,
                              @RestForm String subreddit, @RestForm String window, @RestForm String maxPosts,
                              @RestForm String config, @RestForm String fetchFullText) {
        Map<String, String> cfg = new LinkedHashMap<>(parseConfig(config));
        if (RssSourceConnector.TYPE.equals(connectorType) && notBlank(url)) cfg.put("url", url.trim());
        if (RedditSourceConnector.TYPE.equals(connectorType)) {
            if (notBlank(subreddit)) cfg.put("subreddit", subreddit.trim().replaceFirst("^r/", ""));
            if (notBlank(window)) cfg.put("window", window.trim());
            if (notBlank(maxPosts)) cfg.put("maxPosts", maxPosts.trim());
        }
        try {
            shows.addSource(id, new SourceRequest(connectorType, cfg, fetchFullText != null, true));
            return Response.seeOther(URI.create("/admin/shows/" + id + "?tab=sources")).build();
        } catch (InvalidRequestException e) {
            Show show = find(id);
            return Response.ok(detailTemplate(show, "sources", ShowForm.from(show), List.of(), e.errors())).build();
        }
    }

    @POST
    @Path("/sources/{id}/delete")
    public Response deleteSource(@RestPath long id) {
        Source source = QuarkusTransaction.requiringNew().call(() -> Source.<Source>findByIdOptional(id).orElseThrow(NotFoundException::new));
        shows.deleteSource(id);
        return Response.seeOther(URI.create("/admin/shows/" + source.showId + "?tab=sources")).build();
    }

    @POST
    @Path("/sources/{id}/test")
    public TemplateInstance testSource(@RestPath long id) {
        return Templates.sourceTest(shows.testSource(id));
    }

    @POST
    @Path("/shows/{id}/run")
    public Response runNow(@RestPath long id, @RestHeader("HX-Request") String htmx) {
        try {
            launcher.launch(id, RunTrigger.MANUAL);
        } catch (RunAlreadyActiveException ignored) {
            // the live card already shows the active run
        }
        return htmx != null ? Response.ok(Templates.live(id, support.liveRun(id))).build()
                : Response.seeOther(URI.create("/admin/shows/" + id)).build();
    }

    @POST
    @Path("/runs/{id}/retry")
    public Response retry(@RestPath long id, @RestHeader("HX-Request") String htmx) {
        Run run = QuarkusTransaction.requiringNew().call(() -> Run.<Run>findByIdOptional(id).orElseThrow(NotFoundException::new));
        try {
            launcher.retry(id);
        } catch (IllegalStateException | RunAlreadyActiveException ignored) {
            // status is visible in the refreshed card
        }
        return htmx != null ? Response.ok(Templates.live(run.showId, support.liveRun(run.showId))).build()
                : Response.seeOther(URI.create("/admin/shows/" + run.showId)).build();
    }

    @GET
    @Path("/shows/{id}/live")
    public TemplateInstance live(@RestPath long id) {
        return Templates.live(id, support.liveRun(id));
    }

    @POST
    @Path("/shows/{id}/prompts")
    public Response saveOverrides(@RestPath long id, @RestForm String rank, @RestForm String segment,
                                  @RestForm String framing, @RestForm("json_repair") String jsonRepair) {
        Map<PromptKey, String> form = Map.of(PromptKey.RANK, nz(rank), PromptKey.SEGMENT, nz(segment),
                PromptKey.FRAMING, nz(framing), PromptKey.JSON_REPAIR, nz(jsonRepair));
        form.forEach((key, value) -> {
            if (value.isBlank()) prompts.unpin(id, key);
            else prompts.pin(id, key, Integer.parseInt(value.trim()));
        });
        return Response.seeOther(URI.create("/admin/shows/" + id + "?tab=prompts")).build();
    }

    private TemplateInstance listTemplate(ShowForm form, List<String> errors) {
        List<Show> all = QuarkusTransaction.requiringNew().call(() -> Show.<Show>listAll(Sort.by("name")));
        return Templates.list(all.stream().map(support::showCard).toList(), form, errors, models.availableNames(), support.voices());
    }

    private TemplateInstance detailTemplate(Show show, String tab, ShowForm form, List<String> errors, List<String> sourceErrors) {
        String t = tab != null && TABS.contains(tab) ? tab : (sourceErrors.isEmpty() ? "overview" : "sources");
        List<String> chips = List.of(Prompts.languageName(show.language), "Voice: " + show.voiceId, "Model: " + show.writerModel,
                "Target " + show.targetDurationMinutes + " min", AdminSupport.cronText(show.cron));
        List<Episode> eps = QuarkusTransaction.requiringNew().call(() ->
                Episode.<Episode>find("showId = ?1 and publishedAt is not null order by publishedAt desc", show.id).list());
        List<EpisodeLink> episodes = eps.stream().map(e -> support.episodeLink(e, show.name)).toList();
        List<RunRow> runs = QuarkusTransaction.requiringNew().call(() ->
                Run.<Run>find("showId = ?1 order by startedAt desc", show.id).page(0, 10).list()).stream()
                .map(r -> support.runRow(r, show.name)).toList();
        Instant dayAgo = Instant.now().minus(Duration.ofHours(24));
        List<SourceRow> sources = QuarkusTransaction.requiringNew().call(() -> Source.<Source>list("showId = ?1 order by id", show.id)).stream()
                .map(s -> {
                    long today = QuarkusTransaction.requiringNew().call(() -> Item.count("sourceId = ?1 and fetchedAt >= ?2", s.id, dayAgo));
                    boolean reddit = RedditSourceConnector.TYPE.equals(s.connectorType);
                    String name = reddit ? "r/" + s.config.getOrDefault("subreddit", "?") : AdminSupport.site(s.config.getOrDefault("url", s.connectorType));
                    String detail = reddit ? "top of " + s.config.getOrDefault("window", "day") : s.config.getOrDefault("url", "");
                    return new SourceRow(s.id, name, detail, reddit ? "Reddit" : s.connectorType.toUpperCase(), reddit ? "warn" : "info",
                            AdminSupport.when(s.lastFetchedAt), today,
                            s.lastError == null ? "OK" : AdminSupport.abbreviate(s.lastError, 80), s.lastError == null ? "ok" : "warn");
                }).toList();
        return Templates.detail(show, t, chips, support.liveRun(show.id), episodes, runs, sources, sourceErrors, form,
                "/admin/shows/" + show.id, errors, models.availableNames(), support.voices(), connectors.types(),
                overrideRows(show.id), AdminSupport.cronText(show.cron));
    }

    private List<OverrideRow> overrideRows(long showId) {
        Map<PromptKey, Integer> pinned = prompts.overrides(showId);
        List<OverrideRow> rows = new ArrayList<>();
        for (PromptKey key : PromptKey.values()) {
            rows.add(new OverrideRow(key.dbKey(), pinned.get(key), prompts.versions(key).stream().map(v -> v.version).toList()));
        }
        return rows;
    }

    private static Show find(long id) {
        return QuarkusTransaction.requiringNew().call(() -> Show.<Show>findByIdOptional(id).orElseThrow(NotFoundException::new));
    }

    static Map<String, String> parseConfig(String text) {
        Map<String, String> config = new LinkedHashMap<>();
        if (text == null) return config;
        for (String line : text.split("\\R")) {
            int eq = line.indexOf('=');
            if (eq > 0) config.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        return config;
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    private static String nz(String s) { return s == null ? "" : s; }
}
