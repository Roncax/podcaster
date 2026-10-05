package org.roncax.podcaster.runs;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.time.Instant;
import org.jboss.logging.Logger;
import org.roncax.podcaster.domain.Run;
import org.roncax.podcaster.domain.RunStatus;

@ApplicationScoped
public class RunRecovery {
    private static final Logger LOG = Logger.getLogger(RunRecovery.class);

    void onStart(@Observes StartupEvent event) {
        int recovered = recover();
        if (recovered > 0) LOG.warnf("Marked %d interrupted run(s) as FAILED", recovered);
    }

    public int recover() {
        return QuarkusTransaction.requiringNew().call(() -> Run.update(
                "status = ?1, error = ?2, finishedAt = ?3 where status = ?4",
                RunStatus.FAILED, "Interrupted by restart", Instant.now(), RunStatus.RUNNING));
    }
}
