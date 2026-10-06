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

@Path("/admin")
@Produces(MediaType.TEXT_HTML)
public class AdminResource {

    @CheckedTemplate
    static class Templates {
        static native TemplateInstance login(String error);
        static native TemplateInstance shows(List<Show> shows, ShowForm form, List<String> errors, Set<String> models, Set<String> voices);
        static native TemplateInstance show(Show show, ShowForm form, String formAction, List<Source> sources, List<Run> runs,
                                            List<Episode> episodes, List<String> errors, Set<String> models, Set<String> voices,
                                            Set<String> connectors);
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
                models.availableNames(), voices(), connectors.types());
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
}
