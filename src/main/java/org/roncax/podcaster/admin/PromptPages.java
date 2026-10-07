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
import java.util.*;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestQuery;
import org.roncax.podcaster.admin.PromptAdminViews.*;
import org.roncax.podcaster.domain.Show;
import org.roncax.podcaster.prompts.*;

@Path("/admin/prompts")
@Produces(MediaType.TEXT_HTML)
public class PromptPages {

    @CheckedTemplate
    static class Templates {
        static native TemplateInstance list(List<PromptRow> rows);
        static native TemplateInstance detail(String key, String description, Integer production, Integer draft,
                                              List<VersionRow> versions, int shownVersion, String shownBody, String productionBody,
                                              List<DiffRow> diff, String editorBody, String note, List<String> errors,
                                              List<Show> shows, List<String> variables);
        static native TemplateInstance dryRun(PromptDryRun.Result result, String error);
    }

    @Inject PromptRegistry prompts;
    @Inject PromptDryRun dryRun;

    @GET
    public TemplateInstance list() {
        List<PromptRow> rows = new ArrayList<>();
        for (PromptKey key : PromptKey.values()) {
            Map<PromptLabel, Integer> labels = prompts.labels(key);
            rows.add(new PromptRow(key.dbKey(), key.description(), labels.get(PromptLabel.PRODUCTION), labels.get(PromptLabel.DRAFT)));
        }
        return Templates.list(rows);
    }

    @GET
    @Path("/{key}")
    public TemplateInstance detail(@RestPath String key, @RestQuery Integer v) {
        PromptKey k = key(key);
        Map<PromptLabel, Integer> labels = prompts.labels(k);
        int shown = v != null ? v : labels.get(PromptLabel.DRAFT);
        String draftBody = prompts.version(k, labels.get(PromptLabel.DRAFT)).orElseThrow().body;
        return template(k, shown, draftBody, null, List.of());
    }

    @POST
    @Path("/{key}/versions")
    public Response create(@RestPath String key, @RestForm String body, @RestForm String note) {
        PromptKey k = key(key);
        try {
            PromptVersion created = prompts.createVersion(k, body, note);
            return Response.seeOther(URI.create("/admin/prompts/" + k.dbKey() + "?v=" + created.version)).build();
        } catch (InvalidPromptException e) {
            int shown = prompts.labels(k).get(PromptLabel.DRAFT);
            return Response.ok(template(k, shown, body, note, e.errors())).build();
        }
    }

    @POST
    @Path("/{key}/labels/{label}")
    public Response setLabel(@RestPath String key, @RestPath String label, @RestForm int version) {
        PromptKey k = key(key);
        PromptLabel l = PromptLabel.fromDb(label).orElseThrow(NotFoundException::new);
        prompts.setLabel(k, l, version);
        return Response.seeOther(URI.create("/admin/prompts/" + k.dbKey() + "?v=" + version)).build();
    }

    @POST
    @Path("/dry-run")
    public TemplateInstance dryRun(@RestForm long showId) {
        try {
            return Templates.dryRun(dryRun.run(showId), null);
        } catch (RuntimeException e) {
            return Templates.dryRun(null, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    private TemplateInstance template(PromptKey k, int shown, String editorBody, String note, List<String> errors) {
        Map<PromptLabel, Integer> labels = prompts.labels(k);
        Integer production = labels.get(PromptLabel.PRODUCTION);
        String shownBody = prompts.version(k, shown).orElseThrow(NotFoundException::new).body;
        String productionBody = prompts.version(k, production).orElseThrow().body;
        List<DiffRow> diff = LineDiff.diff(productionBody, shownBody).stream()
                .map(l -> new DiffRow(l.op() == '+' ? "diff-add" : l.op() == '-' ? "diff-del" : "diff-same", String.valueOf(l.op()), l.text()))
                .toList();
        List<VersionRow> versions = prompts.versions(k).stream()
                .map(ver -> new VersionRow(ver.version, ver.note == null ? "" : ver.note, AdminSupport.when(ver.createdAt),
                        String.join(", ", prompts.labelsOf(k, ver.version))))
                .toList();
        List<String> variables = new TreeSet<>(k.variables()).stream().filter(n -> !n.equals("contract") || k.contract() != null)
                .map(n -> "{" + n + "}").toList();
        List<Show> shows = QuarkusTransaction.requiringNew().call(() -> Show.<Show>listAll(Sort.by("name")));
        return Templates.detail(k.dbKey(), k.description(), production, labels.get(PromptLabel.DRAFT), versions, shown,
                shownBody, productionBody, diff, editorBody, note, errors, shows, variables);
    }

    private static PromptKey key(String key) {
        return PromptKey.fromDb(key).orElseThrow(NotFoundException::new);
    }
}
