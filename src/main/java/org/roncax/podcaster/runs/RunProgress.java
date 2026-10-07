package org.roncax.podcaster.runs;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import org.roncax.podcaster.domain.Run;

/** Short human-readable progress of the running stage, shown live in the admin UI. */
@ApplicationScoped
public class RunProgress {
    static final int MAX = 300;

    public void update(long runId, String text) {
        String value = text == null ? null : text.length() > MAX ? text.substring(0, MAX) : text;
        QuarkusTransaction.requiringNew().run(() -> Run.update("progress = ?1 where id = ?2", value, runId));
    }

    public void clear(long runId) {
        update(runId, null);
    }
}
