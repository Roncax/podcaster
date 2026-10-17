package org.roncax.podcaster.testing;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.roncax.podcaster.domain.Item;

/** One show's dry-run state, in memory only. Items are transient (never persisted). */
public final class TestSession {
    public enum Step { SOURCES, PROMPTS, VOICE }
    public enum Status { IDLE, RUNNING, DONE, FAILED }

    public record StepState(Status status, String error) {
        static final StepState IDLE = new StepState(Status.IDLE, null);
    }
    public record SourceResult(long sourceId, String label, int items, String error) {}
    public record Fetched(List<SourceResult> sources, List<Item> items) {}
    public record Clip(byte[] wav, double durationSeconds, double wordsPerMinute, String voiceId, double lengthScale) {}
    /** Last prompt-step form values as submitted (raw strings), echoed back by the admin panel. */
    public record PromptInput(String mode, String items) {}
    /** Last voice-step form values as submitted (raw strings, so invalid input survives a validation error). */
    public record VoiceInput(String text, String voiceId, String lengthScale) {}

    private final long showId;
    private final Map<Step, StepState> states = new EnumMap<>(Step.class);
    private volatile Instant touchedAt = Instant.now();
    private Fetched fetched;
    private PromptDryRun.Result dryRun;
    private Clip clip;
    private long clipSeq;
    private PromptInput promptInput;
    private VoiceInput voiceInput;

    public TestSession(long showId) {
        this.showId = showId;
        for (Step step : Step.values()) states.put(step, StepState.IDLE);
    }

    public long showId() { return showId; }
    public Instant touchedAt() { return touchedAt; }
    void touch() { touchedAt = Instant.now(); }

    /** Marks {@code step} running unless any step already is. */
    public synchronized boolean begin(Step step) {
        if (running()) return false;
        states.put(step, new StepState(Status.RUNNING, null));
        touch();
        return true;
    }

    public synchronized void finish(Step step, String error) {
        states.put(step, new StepState(error == null ? Status.DONE : Status.FAILED, error));
        touch();
    }

    public synchronized boolean running() {
        return states.values().stream().anyMatch(s -> s.status() == Status.RUNNING);
    }

    public synchronized StepState state(Step step) { return states.get(step); }
    public synchronized Fetched fetched() { return fetched; }
    public synchronized Clip clip() { return clip; }
    public synchronized PromptDryRun.Result dryRun() { return dryRun; }

    /** A new fetch invalidates results built on the previous items. */
    public synchronized void fetched(Fetched value) {
        fetched = value;
        dryRun = null;
        clip = null;
        states.put(Step.PROMPTS, StepState.IDLE);
        states.put(Step.VOICE, StepState.IDLE);
    }

    public synchronized void dryRun(PromptDryRun.Result value) { dryRun = value; }
    /** Each stored clip gets a new sequence number, used to cache-bust its audio URL. */
    public synchronized void clip(Clip value) {
        clip = value;
        clipSeq++;
    }

    public synchronized long clipSeq() { return clipSeq; }
    public synchronized PromptInput promptInput() { return promptInput; }
    public synchronized void promptInput(PromptInput value) { promptInput = value; }
    public synchronized VoiceInput voiceInput() { return voiceInput; }
    public synchronized void voiceInput(VoiceInput value) { voiceInput = value; }
}
