package org.roncax.podcaster.testing;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jboss.logging.Logger;
import org.roncax.podcaster.util.Exceptions;

/** Runs test steps in the background, one per show at a time; separate from real runs. */
@ApplicationScoped
public class TestRunner {
    private static final Logger LOG = Logger.getLogger(TestRunner.class);

    public interface Work { void run(TestSession session) throws Exception; }

    @Inject TestSessionStore store;
    private ExecutorService executor;

    @PostConstruct
    void start() {
        executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "podcaster-test");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void stop() { executor.shutdownNow(); }

    public void start(long showId, TestSession.Step step, Work work) {
        TestSession session = store.session(showId);
        if (!session.begin(step)) throw new TestStepBusyException(showId);
        try {
        executor.submit(() -> {
            try {
                work.run(session);
                session.finish(step, null);
            } catch (Throwable t) {
                LOG.infof("Test step %s for show %d failed: %s", step, showId, Exceptions.message(t));
                session.finish(step, Exceptions.message(t));
            }
        });
        } catch (RuntimeException e) {
            session.finish(step, Exceptions.message(e));
            throw e;
        }
    }
}
