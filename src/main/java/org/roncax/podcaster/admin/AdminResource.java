package org.roncax.podcaster.admin;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.*;
import org.jboss.resteasy.reactive.RestForm;
import org.roncax.podcaster.admin.AdminViews.*;
import org.roncax.podcaster.api.ApiKeyFilter;
import org.roncax.podcaster.config.PodcasterConfig;
import org.roncax.podcaster.domain.*;
import org.roncax.podcaster.ingestion.ConnectorRegistry;
import org.roncax.podcaster.llm.ChatModelRegistry;

@Path("/admin")
@Produces(MediaType.TEXT_HTML)
public class AdminResource {

    @CheckedTemplate
    static class Templates {
        static native TemplateInstance login(String error);
        static native TemplateInstance dashboard(List<ShowCard> shows, List<RunRow> runs, List<Attention> attention);
        static native TemplateInstance settings(Set<String> models, Set<String> voices, Set<String> connectors,
                                                boolean telegram, String baseUrl, String version);
    }

    @Inject PodcasterConfig config;
    @Inject ChatModelRegistry models;
    @Inject ConnectorRegistry connectors;
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

}
