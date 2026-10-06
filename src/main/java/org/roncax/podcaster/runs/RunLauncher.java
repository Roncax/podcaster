package org.roncax.podcaster.runs;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.roncax.podcaster.config.PodcasterConfig;
import org.roncax.podcaster.domain.*;
import org.jboss.logging.Logger;
import org.roncax.podcaster.util.Exceptions;

@ApplicationScoped
public class RunLauncher {
    private static final Logger LOG = Logger.getLogger(RunLauncher.class);
    /** Re-read the hour before the previous run so items published while it ran are not missed (dedupe prevents repeats). */
    static final Duration OVERLAP = Duration.ofHours(1);

    @Inject RunOrchestrator orchestrator;
    @Inject PodcasterConfig config;
    private ExecutorService executor;

    @PostConstruct
    void start() {
        executor = Executors.newFixedThreadPool(Math.max(1, config.runs().workers()), r -> {
            Thread t = new Thread(r, "podcaster-run");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }

    public long launch(long showId, RunTrigger trigger) {
        long runId;
        try {
            runId = QuarkusTransaction.requiringNew().call(() -> {
                Show show = Show.findById(showId);
                if (show == null) throw new NotFoundException("Show " + showId + " not found");
                Run run = new Run();
                run.showId = showId;
                run.trigger = trigger;
                run.since = since(showId);
                run.persistAndFlush();
                return run.id;
            });
        } catch (RuntimeException e) {
            if (Exceptions.isUniqueViolation(e)) throw new RunAlreadyActiveException(showId);
            throw e;
        }
        submit(runId);
        return runId;
    }

    public void retry(long runId) {
        long[] showId = new long[1];
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                Run run = Run.findById(runId);
                if (run == null) throw new NotFoundException("Run " + runId + " not found");
                if (run.status != RunStatus.FAILED) {
                    throw new IllegalStateException("Only failed runs can be retried (run " + runId + " is " + run.status + ")");
                }
                showId[0] = run.showId;
                run.status = RunStatus.RUNNING;
                run.attempt++;
                run.error = null;
                run.finishedAt = null;
                Run.flush();
            });
        } catch (RuntimeException e) {
            if (Exceptions.isUniqueViolation(e)) throw new RunAlreadyActiveException(showId[0]);
            throw e;
        }
        submit(runId);
    }

    private Instant since(long showId) {
        return Run.<Run>find("showId = ?1 and status = ?2 order by startedAt desc", showId, RunStatus.DONE)
                .firstResultOptional()
                .map(r -> r.startedAt.minus(OVERLAP))
                .orElse(Instant.now().minus(config.selection().firstRunWindow()));
    }

    private void submit(long runId) {
        executor.submit(() -> {
            try {
                orchestrator.execute(runId);
            } catch (Throwable t) {
                LOG.errorf(t, "Run %d crashed outside its stages; it may stay RUNNING until restart", runId);
            }
        });
    }
}
