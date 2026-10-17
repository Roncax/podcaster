package org.roncax.podcaster.testing;

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
import org.roncax.podcaster.prompts.*;
import org.roncax.podcaster.tts.VoiceCalibrationService;

/** Runs ranking, outline and script writing with a show's draft or production prompts. Persists nothing. */
@ApplicationScoped
public class PromptDryRun {
    public enum ItemsSource { SESSION, DATABASE }
    public record Result(Map<String, Integer> promptVersions, Selection selection, Outline outline,
                         String title, String description, List<String> scriptParts) {}
    public record Prepared(Show show, List<Item> candidates, PromptSet prompts) {}

    @Inject ChatModelRegistry models;
    @Inject StoryRanker ranker;
    @Inject OutlinePlanner planner;
    @Inject ScriptWriter writer;
    @Inject VoiceCalibrationService calibration;
    @Inject PromptResolver resolver;
    @Inject PodcasterConfig config;

    /** Cheap checks done before the step is started, so the caller gets 404/409 synchronously. */
    public Prepared prepare(long showId, PromptResolver.Mode mode, ItemsSource items, TestSession session) {
        Show show = QuarkusTransaction.requiringNew().call(() -> Show.<Show>findByIdOptional(showId)
                .orElseThrow(() -> new NotFoundException("Show " + showId + " not found")));
        List<Item> candidates;
        if (items == ItemsSource.SESSION) {
            TestSession.Fetched fetched = session.fetched();
            if (fetched == null || fetched.items().isEmpty()) {
                throw new NoCandidatesException("No fetched items for show '" + show.name
                        + "' (or the test session expired). Fetch sources first");
            }
            candidates = fetched.items();
        } else {
            Instant since = Instant.now().minus(config.selection().firstRunWindow());
            candidates = QuarkusTransaction.requiringNew().call(() ->
                    Item.unusedCandidates(show.id, since, config.selection().maxCandidates()));
            if (candidates.isEmpty()) {
                throw new NoCandidatesException("No unused items from the last " + config.selection().firstRunWindow().toHours()
                        + "h for show '" + show.name + "'; run ingestion or fetch sources first");
            }
        }
        return new Prepared(show, candidates, resolver.resolve(show.id, mode));
    }

    public Result execute(Prepared p) {
        Show show = p.show();
        Selection selection = ranker.rank(models.get(show.effectiveRankerModel()), p.prompts(),
                show.name, show.language, show.focusPrompt, p.candidates());
        Outline outline = planner.plan(selection, show.targetDurationMinutes,
                calibration.wordsPerMinute(show.voiceId, show.lengthScale));
        Map<Long, Item> byId = p.candidates().stream().collect(Collectors.toMap(i -> i.id, Function.identity()));
        Script script = writer.write(models.get(show.writerModel), p.prompts(), show, outline, byId, LocalDate.now());
        return new Result(p.prompts().versions(PromptKey.values()), selection, outline,
                script.title(), script.description(), script.parts());
    }
}
