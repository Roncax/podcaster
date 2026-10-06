package org.roncax.podcaster.api;

import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;
import org.roncax.podcaster.config.PodcasterConfig;

public class ApiKeyFilter {
    public static final String COOKIE = "podcaster_key";

    @Inject PodcasterConfig config;

    @ServerRequestFilter(preMatching = true)
    public Optional<Response> filter(ContainerRequestContext ctx) {
        String path = ctx.getUriInfo().getPath();
        boolean api = path.startsWith("/api/") || path.equals("/api");
        boolean admin = path.equals("/admin") || path.startsWith("/admin/");
        if (!api && !admin) return Optional.empty();
        if (path.equals("/admin/login")) return Optional.empty();
        if (isAuthorized(ctx)) return Optional.empty();
        if (api) return Optional.of(Response.status(Response.Status.UNAUTHORIZED).build());
        return Optional.of(Response.seeOther(URI.create("/admin/login")).build());
    }

    private boolean isAuthorized(ContainerRequestContext ctx) {
        String key = ctx.getHeaderString("X-API-Key");
        if (key == null) {
            Cookie cookie = ctx.getCookies().get(COOKIE);
            if (cookie != null) key = cookie.getValue();
        }
        return key != null && MessageDigest.isEqual(
                key.getBytes(StandardCharsets.UTF_8), config.apiKey().getBytes(StandardCharsets.UTF_8));
    }
}
