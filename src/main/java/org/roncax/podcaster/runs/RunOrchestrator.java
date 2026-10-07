package org.roncax.podcaster.runs;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import org.jboss.logging.Logger;
import org.roncax.podcaster.domain.*;
import org.roncax.podcaster.notify.Notifier;
import org.roncax.podcaster.util.Exceptions;

@ApplicationScoped
public class RunOrchestrator {
    private static final Logger LOG = Logger.getLogger(RunOrchestrator.class);

    @Inject Instance<Stage> allStages;
    @Inject Notifier notifier;
    private final Map<RunStage, Stage> stages = new EnumMap<>(RunStage.class);

    @PostConstruct
    void init() {
        allStages.forEach(s -> stages.put(s.stage(), s));
        for (RunStage stage : RunStage.values()) {
            if (!stages.containsKey(stage)) throw new IllegalStateException("No Stage bean for " + stage);
        }
    }

    @ActivateRequestContext
    public void execute(long runId) {
        while (true) {
            Run run = QuarkusTransaction.requiringNew().call(() -> Run.<Run>findById(runId));
            if (run == null || run.status != RunStatus.RUNNING) return;
            StageResult result;
            try {
                LOG.infof("Run %d (show %d): %s", runId, run.showId, run.stage);
                result = stages.get(run.stage).execute(run);
            } catch (Throwable e) {
                fail(run, e);
                return;
            }
            if (result == StageResult.SKIP) {
                finish(runId, RunStatus.SKIPPED, null);
                return;
            }
            if (run.stage == RunStage.PUBLISH) {
                finish(runId, RunStatus.DONE, null);
                return;
            }
            RunStage next = run.stage.next();
            QuarkusTransaction.requiringNew().run(() -> {
                Run r = Run.findById(runId);
                if (r != null) r.stage = next;
            });
        }
    }

    private void fail(Run run, Throwable e) {
        String error = run.stage + ": " + Exceptions.message(e);
        LOG.errorf(e, "Run %d failed", run.id);
        try {
            Show show = QuarkusTransaction.requiringNew().call(() -> Show.<Show>findById(run.showId));
            if (show != null) notifier.runFailed(show, run, error);
        } finally {
            finish(run.id, RunStatus.FAILED, error);
        }
    }

    private void finish(long runId, RunStatus status, String error) {
        QuarkusTransaction.requiringNew().run(() -> {
            Run r = Run.findById(runId);
            if (r == null) return;
            r.status = status;
            r.error = error;
            r.finishedAt = Instant.now();
            r.progress = null;
        });
    }
}
