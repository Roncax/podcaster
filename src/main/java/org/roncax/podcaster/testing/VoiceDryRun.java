package org.roncax.podcaster.testing;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.roncax.podcaster.api.InvalidRequestException;
import org.roncax.podcaster.config.PodcasterConfig;
import org.roncax.podcaster.domain.Show;
import org.roncax.podcaster.generation.ScriptChunker;
import org.roncax.podcaster.generation.TtsChunk;
import org.roncax.podcaster.generation.TtsTextNormalizer;
import org.roncax.podcaster.tts.*;

/** Synthesizes a short clip with a show's voice (or an override). Stores nothing but the session clip. */
@ApplicationScoped
public class VoiceDryRun {
    public static final int MAX_CHARS = 1000;

    public record Request(String text, Integer part, String voiceId, Double lengthScale) {}
    public record Prepared(String text, String voiceId, double lengthScale, String language) {}

    @Inject TtsEngine tts;
    @Inject PodcasterConfig config;

    public Prepared prepare(long showId, Request r, TestSession session) {
        Show show = QuarkusTransaction.requiringNew().call(() -> Show.<Show>findByIdOptional(showId).orElseThrow(NotFoundException::new));
        List<String> errors = new ArrayList<>();
        String text = r == null ? null : r.text();
        if (r != null && r.part() != null) {
            PromptDryRun.Result dryRun = session.dryRun();
            if (dryRun == null) errors.add("No script parts yet. Run the prompt dry-run first");
            else if (r.part() < 0 || r.part() >= dryRun.scriptParts().size()) errors.add("Unknown script part " + r.part());
            else text = dryRun.scriptParts().get(r.part());
        }
        if (errors.isEmpty() && (text == null || text.isBlank())) errors.add("Text is empty");
        if (errors.isEmpty() && text.length() > MAX_CHARS) errors.add("Text is " + text.length() + " characters; the limit is " + MAX_CHARS);
        String voice = r != null && r.voiceId() != null && !r.voiceId().isBlank() ? r.voiceId().trim() : show.voiceId;
        double scale = r != null && r.lengthScale() != null ? r.lengthScale() : show.lengthScale;
        if (scale < 0.5 || scale > 2.0) errors.add("lengthScale must be between 0.5 and 2.0");
        if (!voice.equals(show.voiceId)) {
            try {
                Set<String> voices = tts.voices();
                if (!voices.contains(voice)) errors.add("voiceId '" + voice + "' is not installed in Piper; available: " + voices);
            } catch (Exception e) {
                // Piper unreachable: the step itself will report it
            }
        }
        if (!errors.isEmpty()) throw new InvalidRequestException(errors);
        return new Prepared(text.trim(), voice, scale, show.language);
    }

    public TestSession.Clip execute(Prepared p) throws Exception {
        PodcasterConfig.Tts cfg = config.tts();
        String text = TtsTextNormalizer.normalize(p.text(), p.language());
        List<TtsChunk> chunks = ScriptChunker.chunk(List.of(text), cfg.chunkChars(), cfg.chunkPause(), cfg.segmentPause());
        VoiceConfig voice = new VoiceConfig(p.voiceId(), p.lengthScale());
        List<byte[]> wavs = new ArrayList<>();
        for (TtsChunk chunk : chunks) wavs.add(tts.synthesize(chunk.text(), voice));
        WavConcat.Joined joined = WavConcat.concat(wavs, chunks.stream().map(TtsChunk::pauseAfter).toList(),
                chunks.stream().map(TtsChunk::part).toList());
        int words = text.isBlank() ? 0 : text.trim().split("\\s+").length;
        double wpm = joined.durationSeconds() > 0 ? words / (joined.durationSeconds() / 60.0) : 0;
        return new TestSession.Clip(joined.toWavBytes(), joined.durationSeconds(), wpm, p.voiceId(), p.lengthScale());
    }
}
