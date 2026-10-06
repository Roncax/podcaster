package org.roncax.podcaster.prompts;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

/** Inserts missing prompts with their v1 seed text and points both labels at it. Idempotent. */
@ApplicationScoped
public class PromptSeeder {
    private static final Logger LOG = Logger.getLogger(PromptSeeder.class);

    void onStart(@Observes @Priority(10) StartupEvent event) {
        seed();
    }

    public void seed() {
        QuarkusTransaction.requiringNew().run(() -> {
            for (PromptKey key : PromptKey.values()) {
                if (Prompt.findById(key.dbKey()) == null) {
                    Prompt p = new Prompt();
                    p.key = key.dbKey();
                    p.description = key.description();
                    p.persist();
                }
                if (PromptVersion.count("promptKey", key.dbKey()) > 0) continue;
                PromptVersion v = new PromptVersion();
                v.promptKey = key.dbKey();
                v.version = 1;
                v.body = key.seedBody();
                v.note = "Initial version";
                v.persist();
                for (PromptLabel label : PromptLabel.values()) {
                    PromptLabelAssignment a = new PromptLabelAssignment();
                    a.promptKey = key.dbKey();
                    a.label = label.dbValue();
                    a.versionId = v.id;
                    a.persist();
                }
                LOG.infof("Seeded prompt %s v1", key.dbKey());
            }
        });
    }
}
