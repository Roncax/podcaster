package org.roncax.podcaster.runs;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.roncax.podcaster.config.PodcasterConfig;
import org.roncax.podcaster.domain.*;
import org.roncax.podcaster.generation.StoryRanker;
import org.roncax.podcaster.llm.ChatModelRegistry;

@ApplicationScoped
public class SelectStage implements Stage {
    @Inject ChatModelRegistry models;
    @Inject StoryRanker ranker;
    @Inject PodcasterConfig config;

    @Override
    public RunStage stage() { return RunStage.SELECT; }

    @Override
    public StageResult execute(Run run) {
        Show show = QuarkusTransaction.requiringNew().call(() -> Show.<Show>findById(run.showId));
        List<Item> candidates = QuarkusTransaction.requiringNew().call(() -> Item.<Item>find(
                        "showId = ?1 and usedInEpisodeId is null and coalesce(publishedAt, fetchedAt) >= ?2 "
                                + "order by coalesce(publishedAt, fetchedAt) desc", show.id, run.since)
                .page(0, config.selection().maxCandidates())
                .list());
        if (candidates.size() < show.minItems) return StageResult.SKIP;

        Selection selection = ranker.rank(models.get(show.effectiveRankerModel()),
                show.name, show.language, show.focusPrompt, candidates);

        QuarkusTransaction.requiringNew().run(() -> {
            Episode episode = Episode.findByRun(run.id).orElseGet(() -> {
                Episode e = new Episode();
                e.runId = run.id;
                e.showId = show.id;
                e.persist();
                return e;
            });
            episode.selection = selection;
        });
        return StageResult.CONTINUE;
    }
}
