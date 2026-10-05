package org.roncax.podcaster.publishing;

import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.FileSystemAccess;
import io.vertx.ext.web.handler.StaticHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;

/** Serves stored audio under /media/* with HTTP Range support (needed by podcast players). */
@ApplicationScoped
public class MediaRoutes {
    @Inject LocalAudioStorage storage;

    void init(@Observes Router router) throws IOException {
        Files.createDirectories(storage.root());
        router.route("/media/*").handler(StaticHandler.create(FileSystemAccess.ROOT, storage.root().toString())
                .setCachingEnabled(false)
                .setDirectoryListing(false)
                .setIncludeHidden(false));
    }
}
