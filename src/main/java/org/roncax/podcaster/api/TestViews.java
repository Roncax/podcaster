package org.roncax.podcaster.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.roncax.podcaster.testing.TestSession;

public final class TestViews {
    private TestViews() {}

    public record StepView(String status, String error) {}
    public record ItemView(long id, long sourceId, String title, String url, Instant publishedAt, boolean hasFullText, int textLength) {}
    public record ClipView(double durationSeconds, double wordsPerMinute, String voiceId, double lengthScale, String audioUrl) {}
    /** {@code steps} is keyed by step name (SOURCES, PROMPTS, VOICE) so JSON and Qute can both look steps up by string. */
    public record Session(Map<String, StepView> steps, List<TestSession.SourceResult> sources, List<ItemView> items,
                          Object dryRun, ClipView clip) {}

    public static Session of(TestSession s, String audioUrl) {
        Map<String, StepView> steps = new LinkedHashMap<>();
        for (TestSession.Step step : TestSession.Step.values()) {
            TestSession.StepState st = s.state(step);
            steps.put(step.name(), new StepView(st.status().name(), st.error()));
        }
        TestSession.Fetched f = s.fetched();
        List<ItemView> items = f == null ? List.of() : f.items().stream()
                .map(i -> new ItemView(i.id, i.sourceId, i.title, i.url, i.publishedAt, i.fullText != null,
                        i.bestText() == null ? 0 : i.bestText().length()))
                .toList();
        TestSession.Clip c = s.clip();
        ClipView clip = c == null ? null : new ClipView(c.durationSeconds(), c.wordsPerMinute(), c.voiceId(), c.lengthScale(), audioUrl);
        return new Session(steps, f == null ? List.of() : f.sources(), items, s.dryRun(), clip);
    }

    public static Session empty(long showId) {
        return of(new TestSession(showId), null);
    }
}
