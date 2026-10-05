package org.roncax.podcaster.tts;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.Optional;
import org.roncax.podcaster.config.PodcasterConfig;
import org.roncax.podcaster.domain.VoiceCalibration;

@ApplicationScoped
public class VoiceCalibrationService {
    static final double ALPHA = 0.3;

    @Inject PodcasterConfig config;

    @Transactional
    public double wordsPerMinute(String voiceId, double lengthScale) {
        return find(voiceId, lengthScale).map(c -> c.wordsPerMinute).orElse(config.tts().defaultWpm());
    }

    @Transactional
    public double record(String voiceId, double lengthScale, int words, double durationSeconds) {
        if (words <= 0 || durationSeconds <= 0) return wordsPerMinute(voiceId, lengthScale);
        double measured = words / (durationSeconds / 60.0);
        VoiceCalibration c = find(voiceId, lengthScale).orElseGet(() -> {
            VoiceCalibration n = new VoiceCalibration();
            n.voiceId = voiceId;
            n.lengthScale = lengthScale;
            n.wordsPerMinute = config.tts().defaultWpm();
            n.persist();
            return n;
        });
        c.wordsPerMinute = c.samples == 0 ? measured : (1 - ALPHA) * c.wordsPerMinute + ALPHA * measured;
        c.samples++;
        return c.wordsPerMinute;
    }

    private Optional<VoiceCalibration> find(String voiceId, double lengthScale) {
        return VoiceCalibration.find("voiceId = ?1 and lengthScale = ?2", voiceId, lengthScale).firstResultOptional();
    }
}
