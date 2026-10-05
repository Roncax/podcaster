package org.roncax.podcaster.runs;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.roncax.podcaster.domain.Run;
import org.roncax.podcaster.domain.RunStage;
import org.roncax.podcaster.domain.Show;
import org.roncax.podcaster.ingestion.IngestionReport;
import org.roncax.podcaster.ingestion.IngestionService;
import org.roncax.podcaster.notify.Notifier;

@ApplicationScoped
public class IngestStage implements Stage {
    @Inject IngestionService ingestion;
    @Inject Notifier notifier;

    @Override
    public RunStage stage() { return RunStage.INGEST; }

    @Override
    public StageResult execute(Run run) {
        Show show = QuarkusTransaction.requiringNew().call(() -> Show.<Show>findById(run.showId));
        IngestionReport report = ingestion.ingest(run.showId, run.since);
        if (report.sources() == 0) throw new StageException("Show has no enabled sources");
        if (report.allFailed()) throw new StageException("All sources failed: " + String.join("; ", report.errors()));
        if (!report.errors().isEmpty()) {
            notifier.warning(show, report.errors().size() + " of " + report.sources() + " sources failed:\n"
                    + String.join("\n", report.errors()));
        }
        return StageResult.CONTINUE;
    }
}
