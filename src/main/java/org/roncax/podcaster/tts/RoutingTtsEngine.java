package org.roncax.podcaster.tts;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.jboss.logging.Logger;
import org.roncax.podcaster.config.PodcasterConfig;

/** The application's TTS engine: cloud voices (Sibilia, Azzurra) go to the Modal service, all others to Piper. */
@ApplicationScoped
public class RoutingTtsEngine implements TtsEngine {
    private static final Logger LOG = Logger.getLogger(RoutingTtsEngine.class);
    private final HttpTtsEngine piper;
    private final Optional<HttpTtsEngine> sibilia;
    private final TtsLimits piperLimits;
    private final TtsLimits sibiliaLimits;

    @Inject
    public RoutingTtsEngine(PodcasterConfig config) {
        this(piper(config.tts()), sibilia(config.tts()),
                new TtsLimits(config.tts().chunkChars(), config.tts().parallelism()),
                new TtsLimits(config.tts().sibilia().chunkChars(), config.tts().sibilia().parallelism()));
    }

    RoutingTtsEngine(HttpTtsEngine piper, Optional<HttpTtsEngine> sibilia, TtsLimits piperLimits, TtsLimits sibiliaLimits) {
        this.piper = piper;
        this.sibilia = sibilia;
        this.piperLimits = piperLimits;
        this.sibiliaLimits = sibiliaLimits;
    }

    /** Voices served by the Modal service: Sibilia (built-in or {@code sibilia-<name>} reference voices) and Azzurra. */
    public static boolean isCloudVoice(String voiceId) {
        return voiceId != null && (voiceId.equals("sibilia") || voiceId.startsWith("sibilia-") || voiceId.equals("azzurra"));
    }

    @Override
    public byte[] synthesize(String text, VoiceConfig voice) throws Exception {
        if (!isCloudVoice(voice.voiceId())) return piper.synthesize(text, voice);
        return sibilia.orElseThrow(() -> new IllegalStateException("Cloud TTS is not configured (PODCASTER_SIBILIA_URL)"))
                .synthesize(text, voice);
    }

    @Override
    public Set<String> voices() throws Exception {
        Set<String> voices = new TreeSet<>(piper.voices());
        if (sibilia.isPresent()) {
            try {
                voices.addAll(sibilia.get().voices());
            } catch (Exception e) {
                LOG.warnf("Cloud TTS voices unavailable: %s", e.getMessage());
            }
        }
        return voices;
    }

    @Override
    public TtsLimits limits(String voiceId) {
        return isCloudVoice(voiceId) ? sibiliaLimits : piperLimits;
    }

    /** For the settings page: never includes the credentials. */
    public String cloudStatus() {
        if (sibilia.isEmpty()) return "Not configured";
        HttpTtsEngine s = sibilia.get();
        try {
            s.voices();
            return "Connected · " + s.host();
        } catch (Exception e) {
            return "Unreachable · " + s.host();
        }
    }

    private static HttpTtsEngine piper(PodcasterConfig.Tts tts) {
        return new HttpTtsEngine("Piper", tts.piperUrl(), Map.of(), tts.attempts(), tts.retryDelay(),
                tts.requestTimeout(), Duration.ofSeconds(10));
    }

    private static Optional<HttpTtsEngine> sibilia(PodcasterConfig.Tts tts) {
        PodcasterConfig.Tts.Sibilia s = tts.sibilia();
        Optional<String> url = s.url().filter(u -> !u.isBlank());
        if (url.isEmpty()) return Optional.empty();
        Map<String, String> headers = new LinkedHashMap<>();
        if (s.key().isPresent() && s.secret().isPresent()) {
            headers.put("Modal-Key", s.key().get());
            headers.put("Modal-Secret", s.secret().get());
        }
        return Optional.of(new HttpTtsEngine("Cloud TTS", url.get(), headers, tts.attempts(), tts.retryDelay(),
                s.requestTimeout(), Duration.ofSeconds(30)));
    }
}
