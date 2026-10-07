package org.roncax.podcaster.publishing;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.FileSystemAccess;
import io.vertx.ext.web.handler.StaticHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import org.roncax.podcaster.domain.Show;

/**
 * Serves stored audio under /media/{feedToken}/{slug}/* with HTTP Range support (needed by podcast players).
 * The token must belong to the show owning the slug directory, so audio is as private as the feed.
 */
@ApplicationScoped
public class MediaRoutes {
    @Inject LocalAudioStorage storage;

    void init(@Observes Router router) throws IOException {
        Files.createDirectories(storage.root());
        router.route("/media/:token/*")
                .blockingHandler(MediaRoutes::authorize)
                .handler(StaticHandler.create(FileSystemAccess.ROOT, storage.root().toString())
                        .setCachingEnabled(false)
                        .setDirectoryListing(false)
                        .setIncludeHidden(false));
    }

    private static void authorize(RoutingContext ctx) {
        String token = ctx.pathParam("token");
        String rest = ctx.pathParam("*");
        boolean allowed = rest != null && QuarkusTransaction.requiringNew().call(() -> Show.findByFeedToken(token)
                .map(show -> rest.startsWith(show.slug + "/"))
                .orElse(false));
        if (allowed) ctx.next();
        else ctx.fail(404);
    }
}
