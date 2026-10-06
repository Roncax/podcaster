package org.roncax.podcaster.prompts;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.roncax.podcaster.config.PodcasterConfig;
import org.roncax.podcaster.domain.*;
import org.roncax.podcaster.generation.OutlinePlanner;
import org.roncax.podcaster.generation.Script;
import org.roncax.podcaster.generation.ScriptWriter;
import org.roncax.podcaster.generation.StoryRanker;
import org.roncax.podcaster.llm.ChatModelRegistry;
import org.roncax.podcaster.tts.VoiceCalibrationService;

/** Runs ranking and script writing with the draft prompts. Persists nothing. */
@ApplicationScoped
public class PromptDryRun {
    public record Result(Map<String, Integer> promptVersions, Selection selection, Outline outline,
                         String title, String description, List<String> scriptParts) {}

    @Inject ChatModelRegistry models;
    @Inject StoryRanker ranker;
    @Inject OutlinePlanner planner;
    @Inject ScriptWriter writer;
    @Inject VoiceCalibrationService calibration;
    @Inject PromptResolver resolver;
    @Inject PodcasterConfig config;

    public Result run(long showId) {
        Show show = QuarkusTransaction.requiringNew().call(() -> Show.<Show>findByIdOptional(showId)
                .orElseThrow(() -> new NotFoundException("Show " + showId + " not found")));
        Instant since = Instant.now().minus(config.selection().firstRunWindow());
        List<Item> candidates = QuarkusTransaction.requiringNew().call(() ->
                Item.unusedCandidates(show.id, since, config.selection().maxCandidates()));
        if (candidates.isEmpty()) {
            throw new NoCandidatesException("No unused items from the last " + config.selection().firstRunWindow().toHours()
                    + "h for show '" + show.name + "'; run ingestion first");
        }
        PromptSet prompts = resolver.resolve(show.id, PromptResolver.Mode.DRAFT);
        Selection selection = ranker.rank(models.get(show.effectiveRankerModel()), prompts,
                show.name, show.language, show.focusPrompt, candidates);
        Outline outline = planner.plan(selection, show.targetDurationMinutes,
                calibration.wordsPerMinute(show.voiceId, show.lengthScale));
        Map<Long, Item> items = candidates.stream().collect(Collectors.toMap(i -> i.id, Function.identity()));
        Script script = writer.write(models.get(show.writerModel), prompts, show, outline, items, LocalDate.now());
        return new Result(prompts.versions(PromptKey.values()), selection, outline,
                script.title(), script.description(), script.parts());
    }
}
