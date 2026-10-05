package org.roncax.podcaster.runs;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.roncax.podcaster.domain.*;
import org.roncax.podcaster.generation.OutlinePlanner;
import org.roncax.podcaster.generation.Script;
import org.roncax.podcaster.generation.ScriptWriter;
import org.roncax.podcaster.llm.ChatModelRegistry;
import org.roncax.podcaster.tts.VoiceCalibrationService;

@ApplicationScoped
public class ScriptStage implements Stage {
    @Inject ChatModelRegistry models;
    @Inject OutlinePlanner planner;
    @Inject ScriptWriter writer;
    @Inject VoiceCalibrationService calibration;

    @Override
    public RunStage stage() { return RunStage.SCRIPT; }

    @Override
    public StageResult execute(Run run) {
        Show show = QuarkusTransaction.requiringNew().call(() -> Show.<Show>findById(run.showId));
        Episode episode = QuarkusTransaction.requiringNew().call(() -> Episode.findByRun(run.id)
                .orElseThrow(() -> new StageException("Run " + run.id + " has no selection")));
        double wpm = calibration.wordsPerMinute(show.voiceId, show.lengthScale);
        Outline outline = planner.plan(episode.selection, show.targetDurationMinutes, wpm);
        Map<Long, Item> items = QuarkusTransaction.requiringNew().call(() -> Item.<Item>list("id in ?1", outline.itemIds())
                .stream().collect(Collectors.toMap(i -> i.id, Function.identity())));

        Script script = writer.write(models.get(show.writerModel), show, outline, items, LocalDate.now());

        QuarkusTransaction.requiringNew().run(() -> {
            Episode e = Episode.findById(episode.id);
            e.outline = outline;
            e.title = script.title();
            e.description = script.description();
            e.scriptParts = script.parts();
            e.script = script.joined();
        });
        return StageResult.CONTINUE;
    }
}
