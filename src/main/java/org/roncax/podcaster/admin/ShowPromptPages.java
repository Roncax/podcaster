package org.roncax.podcaster.admin;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestQuery;
import org.roncax.podcaster.admin.PromptAdminViews.*;
import org.roncax.podcaster.domain.Show;
import org.roncax.podcaster.prompts.*;

/** Admin pages for one show's own versioned prompts. */
@Path("/admin/shows/{id}/prompts")
@Produces(MediaType.TEXT_HTML)
public class ShowPromptPages {
    @Inject PromptRegistry prompts;

    @GET
    @Path("/{key}")
    public TemplateInstance detail(@RestPath long id, @RestPath String key, @RestQuery Integer v) {
        PromptKey k = key(key);
        Show show = show(id);
        int draft = prompts.labels(id, k).get(PromptLabel.DRAFT);
        return page(show, k, v != null ? v : draft, prompts.version(id, k, draft).orElseThrow().body, null, List.of());
    }

    @POST
    @Path("/{key}/versions")
    public Response create(@RestPath long id, @RestPath String key, @RestForm String body, @RestForm String note) {
        PromptKey k = key(key);
        Show show = show(id);
        try {
            PromptVersion created = prompts.createVersion(id, k, body, note);
            return Response.seeOther(URI.create(base(id, k) + "?v=" + created.version)).build();
        } catch (InvalidPromptException e) {
            return Response.ok(page(show, k, prompts.labels(id, k).get(PromptLabel.DRAFT), body, note, e.errors())).build();
        }
    }

    @POST
    @Path("/{key}/labels/{label}")
    public Response setLabel(@RestPath long id, @RestPath String key, @RestPath String label, @RestForm int version) {
        PromptKey k = key(key);
        show(id);
        prompts.setLabel(id, k, PromptLabel.fromDb(label).orElseThrow(NotFoundException::new), version);
        return Response.seeOther(URI.create(base(id, k) + "?v=" + version)).build();
    }

    @POST
    @Path("/{key}/reset")
    public Response reset(@RestPath long id, @RestPath String key) {
        PromptKey k = key(key);
        show(id);
        PromptVersion v = prompts.resetToDefault(id, k);
        return Response.seeOther(URI.create(base(id, k) + "?v=" + v.version)).build();
    }

    private TemplateInstance page(Show show, PromptKey k, int shown, String editorBody, String note, List<String> errors) {
        Map<PromptLabel, Integer> labels = prompts.labels(show.id, k);
        int production = labels.get(PromptLabel.PRODUCTION);
        String shownBody = prompts.version(show.id, k, shown).orElseThrow(NotFoundException::new).body;
        String productionBody = prompts.version(show.id, k, production).orElseThrow().body;
        List<VersionRow> versions = prompts.versions(show.id, k).stream()
                .map(ver -> new VersionRow(ver.version, ver.note == null ? "" : ver.note, AdminSupport.when(ver.createdAt),
                        String.join(", ", prompts.labelsOf(show.id, k, ver.version))))
                .toList();
        return PromptPages.Templates.detail(new PromptPage(base(show.id, k),
                List.of(new Crumb("Shows", "/admin/shows"), new Crumb(show.name, "/admin/shows/" + show.id + "?tab=prompts")),
                "shows", k.dbKey(), k.description(), "production v" + production + " · draft v" + labels.get(PromptLabel.DRAFT),
                null, true, versions, shown, "v" + shown + " compared with production (v" + production + ")",
                productionBody, shownBody, PromptPages.diffRows(productionBody, shownBody),
                editorBody, note, errors, PromptPages.variables(k), base(show.id, k) + "/reset", "/admin/shows/" + show.id + "?tab=test&prompt=" + k.dbKey()));
    }

    private static String base(long id, PromptKey k) { return "/admin/shows/" + id + "/prompts/" + k.dbKey(); }

    private static Show show(long id) {
        return QuarkusTransaction.requiringNew().call(() -> Show.<Show>findByIdOptional(id).orElseThrow(NotFoundException::new));
    }

    private static PromptKey key(String key) { return PromptKey.fromDb(key).orElseThrow(NotFoundException::new); }
}
