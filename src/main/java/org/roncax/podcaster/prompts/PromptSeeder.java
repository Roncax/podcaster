package org.roncax.podcaster.prompts;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.roncax.podcaster.domain.Show;

/** Inserts missing default prompts with their seed text and forks prompts for shows that lack any. Idempotent. */
@ApplicationScoped
public class PromptSeeder {
    private static final Logger LOG = Logger.getLogger(PromptSeeder.class);

    void onStart(@Observes @Priority(10) StartupEvent event) {
        seed();
    }

    @Inject PromptRegistry registry;

    public void seed() {
        QuarkusTransaction.requiringNew().run(() -> {
            for (PromptKey key : PromptKey.values()) {
                if (Prompt.findById(key.dbKey()) == null) {
                    Prompt p = new Prompt();
                    p.key = key.dbKey();
                    p.description = key.description();
                    p.persist();
                }
                if (PromptVersion.count("showId is null and promptKey = ?1", key.dbKey()) > 0) continue;
                PromptVersion v = new PromptVersion();
                v.promptKey = key.dbKey();
                v.version = 1;
                v.body = key.seedBody();
                v.note = "Initial version";
                v.persist();
                LOG.infof("Seeded default prompt %s v1", key.dbKey());
            }
            for (Show show : Show.<Show>listAll()) registry.fork(show.id);
        });
    }
}
