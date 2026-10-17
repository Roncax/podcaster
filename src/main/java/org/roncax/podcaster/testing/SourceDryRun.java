package org.roncax.podcaster.testing;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.*;
import org.roncax.podcaster.config.PodcasterConfig;
import org.roncax.podcaster.domain.Item;
import org.roncax.podcaster.domain.Source;
import org.roncax.podcaster.ingestion.CollectedItem;
import org.roncax.podcaster.ingestion.IngestionService;
import org.roncax.podcaster.util.Exceptions;

/** Fetches all enabled sources like ingestion does, but keeps the items in memory and touches no row. */
@ApplicationScoped
public class SourceDryRun {
    @Inject IngestionService ingestion;
    @Inject PodcasterConfig config;

    public TestSession.Fetched run(long showId) {
        List<Source> sources = QuarkusTransaction.requiringNew()
                .call(() -> Source.<Source>list("showId = ?1 and enabled = true order by id", showId));
        Instant since = Instant.now().minus(config.selection().firstRunWindow());
        List<TestSession.SourceResult> results = new ArrayList<>();
        List<Item> items = new ArrayList<>();
        Set<String> urls = new HashSet<>();
        Set<String> hashes = new HashSet<>();
        for (Source source : sources) {
            try {
                int kept = 0;
                for (CollectedItem c : ingestion.collect(source, since, url -> false)) {
                    if (!urls.add(c.raw().url()) || !hashes.add(c.contentHash())) continue;
                    items.add(IngestionService.toItem(showId, source.id, c));
                    kept++;
                }
                results.add(new TestSession.SourceResult(source.id, source.label(), kept, null));
            } catch (Exception e) {
                results.add(new TestSession.SourceResult(source.id, source.label(), 0, Exceptions.rootMessage(e)));
            }
        }
        items.sort(Comparator.comparing(Item::effectiveDate, Comparator.nullsLast(Comparator.reverseOrder())));
        List<Item> capped = new ArrayList<>(items.subList(0, Math.min(items.size(), config.selection().maxCandidates())));
        for (int i = 0; i < capped.size(); i++) capped.get(i).id = (long) (i + 1); // shown to the LLM as [id=N]
        return new TestSession.Fetched(results, capped);
    }
}
