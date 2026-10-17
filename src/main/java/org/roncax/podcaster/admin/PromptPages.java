package org.roncax.podcaster.admin;

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
import org.roncax.podcaster.prompts.*;

/** Admin pages for the default prompts (the starting point for new shows). */
@Path("/admin/prompts")
@Produces(MediaType.TEXT_HTML)
public class PromptPages {

    @CheckedTemplate
    static class Templates {
        static native TemplateInstance list(List<PromptRow> rows);
        static native TemplateInstance detail(PromptPage page);
        static native TemplateInstance dryRun(PromptDryRun.Result result, String error);
    }

    @Inject PromptRegistry prompts;
    @Inject PromptDryRun dryRun;

    @GET
    public TemplateInstance list() {
        List<PromptRow> rows = new ArrayList<>();
        for (PromptKey key : PromptKey.values()) {
            rows.add(new PromptRow(key.dbKey(), key.description(), prompts.latestDefault(key).version));
        }
        return Templates.list(rows);
    }

    @GET
    @Path("/{key}")
    public TemplateInstance detail(@RestPath String key, @RestQuery Integer v) {
        PromptKey k = key(key);
        PromptVersion latest = prompts.latestDefault(k);
        return defaultPage(k, v != null ? v : latest.version, latest.body, null, List.of());
    }

    @POST
    @Path("/{key}/versions")
    public Response create(@RestPath String key, @RestForm String body, @RestForm String note) {
        PromptKey k = key(key);
        try {
            PromptVersion created = prompts.createDefaultVersion(k, body, note);
            return Response.seeOther(URI.create("/admin/prompts/" + k.dbKey() + "?v=" + created.version)).build();
        } catch (InvalidPromptException e) {
            return Response.ok(defaultPage(k, prompts.latestDefault(k).version, body, note, e.errors())).build();
        }
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

    static List<DiffRow> diffRows(String base, String shown) {
        return LineDiff.diff(base, shown).stream()
                .map(l -> new DiffRow(l.op() == '+' ? "diff-add" : l.op() == '-' ? "diff-del" : "diff-same", String.valueOf(l.op()), l.text()))
                .toList();
    }

    static List<String> variables(PromptKey k) {
        return new TreeSet<>(k.variables()).stream().filter(n -> !n.equals("contract") || k.contract() != null)
                .map(n -> "{" + n + "}").toList();
    }

    private TemplateInstance defaultPage(PromptKey k, int shown, String editorBody, String note, List<String> errors) {
        PromptVersion shownV = prompts.defaultVersion(k, shown).orElseThrow(NotFoundException::new);
        PromptVersion base = prompts.defaultVersion(k, shown - 1).orElse(shownV);
        int latest = prompts.latestDefault(k).version;
        List<VersionRow> versions = prompts.defaultVersions(k).stream()
                .map(ver -> new VersionRow(ver.version, ver.note == null ? "" : ver.note, AdminSupport.when(ver.createdAt), ver.version == latest ? "latest" : ""))
                .toList();
        String compare = base == shownV ? "v" + shown + " (first version)" : "v" + shown + " compared with v" + base.version;
        return Templates.detail(new PromptPage("/admin/prompts/" + k.dbKey(),
                List.of(new Crumb("Default prompts", "/admin/prompts")), "prompts", k.dbKey(), k.description(),
                "latest v" + latest, "Used for new shows. Existing shows change only via Reset to default.", false,
                versions, shown, compare, base.body, shownV.body, diffRows(base.body, shownV.body),
                editorBody, note, errors, variables(k), null, null));
    }

    private static PromptKey key(String key) {
        return PromptKey.fromDb(key).orElseThrow(NotFoundException::new);
    }
}
