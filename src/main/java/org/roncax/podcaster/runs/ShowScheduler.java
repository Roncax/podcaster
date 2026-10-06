package org.roncax.podcaster.runs;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduler;
import io.quarkus.scheduler.Trigger;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;
import org.roncax.podcaster.domain.RunTrigger;
import org.roncax.podcaster.domain.Show;

@ApplicationScoped
public class ShowScheduler {
    private static final Logger LOG = Logger.getLogger(ShowScheduler.class);
    private static final String PREFIX = "show-";

    @Inject Scheduler scheduler;
    @Inject RunLauncher launcher;

    void onStart(@Observes StartupEvent event) {
        List<Show> shows = QuarkusTransaction.requiringNew().call(() -> Show.<Show>list("enabled = true and cron is not null"));
        shows.forEach(this::reschedule);
        LOG.infof("Scheduled %d show(s)", scheduledJobIds().size());
    }

    public void reschedule(Show show) {
        unschedule(show.id);
        if (!show.enabled || show.cron == null || show.cron.isBlank()) return;
        long showId = show.id;
        scheduler.newJob(PREFIX + showId)
                .setCron(show.cron.trim())
                .setTask(execution -> launchQuietly(showId))
                .schedule();
    }

    public void unschedule(long showId) {
        scheduler.unscheduleJob(PREFIX + showId);
    }

    public Set<String> scheduledJobIds() {
        return scheduler.getScheduledJobs().stream()
                .map(Trigger::getId)
                .filter(id -> id.startsWith(PREFIX))
                .collect(Collectors.toSet());
    }

    private void launchQuietly(long showId) {
        try {
            launcher.launch(showId, RunTrigger.SCHEDULED);
        } catch (RunAlreadyActiveException e) {
            LOG.infof("Skipping scheduled run: %s", e.getMessage());
        } catch (Exception e) {
            LOG.errorf(e, "Scheduled run for show %d could not start", showId);
        }
    }
}
