package org.roncax.podcaster.runs;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.roncax.podcaster.config.PodcasterConfig;
import org.roncax.podcaster.domain.*;
import org.roncax.podcaster.generation.StoryRanker;
import org.roncax.podcaster.llm.ChatModelRegistry;
import org.roncax.podcaster.prompts.PromptKey;
import org.roncax.podcaster.prompts.PromptResolver;
import org.roncax.podcaster.prompts.PromptSet;

@ApplicationScoped
public class SelectStage implements Stage {
    @Inject ChatModelRegistry models;
    @Inject StoryRanker ranker;
    @Inject PodcasterConfig config;
    @Inject PromptResolver prompts;
    @Inject RunProgress progress;

    @Override
    public RunStage stage() { return RunStage.SELECT; }

    @Override
    public StageResult execute(Run run) {
        Show show = QuarkusTransaction.requiringNew().call(() -> Show.<Show>findById(run.showId));
        List<Item> candidates = QuarkusTransaction.requiringNew().call(() ->
                Item.unusedCandidates(show.id, run.since, config.selection().maxCandidates()));
        if (candidates.size() < show.minItems) return StageResult.SKIP;
        progress.update(run.id, "Ranking " + candidates.size() + " items");

        PromptSet promptSet = prompts.resolve(show.id, PromptResolver.Mode.PRODUCTION);
        Selection selection = ranker.rank(models.get(show.effectiveRankerModel()), promptSet,
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
            episode.promptVersions = merge(episode.promptVersions, promptSet.versions(PromptKey.RANK, PromptKey.JSON_REPAIR));
        });
        return StageResult.CONTINUE;
    }

    static java.util.Map<String, Integer> merge(java.util.Map<String, Integer> existing, java.util.Map<String, Integer> used) {
        java.util.Map<String, Integer> merged = new java.util.LinkedHashMap<>();
        if (existing != null) merged.putAll(existing);
        merged.putAll(used);
        return merged;
    }
}
