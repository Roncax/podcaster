package org.roncax.podcaster.config;

import io.smallrye.config.ConfigMapping;
import java.time.Duration;
import java.util.Optional;

@ConfigMapping(prefix = "podcaster")
public interface PodcasterConfig {
    String apiKey();
    String baseUrl();
    Storage storage();
    Http http();
    Selection selection();
    Script script();
    Tts tts();
    Runs runs();
    Telegram telegram();

    interface Storage { String root(); String workDir(); }

    interface Http {
        String userAgent();
        Duration timeout();
        Duration politenessDelay();
        int attempts();
        Duration retryDelay();
    }

    interface Selection { int maxCandidates(); Duration firstRunWindow(); }

    interface Script {
        int introOutroWords();
        int minSegmentWords();
        int maxSegmentWords();
        int maxSourceChars();
    }

    interface Tts {
        String piperUrl();
        double defaultWpm();
        int chunkChars();
        int parallelism();
        Duration chunkPause();
        Duration segmentPause();
        String ffmpeg();
        String bitrate();
        int attempts();
        Duration retryDelay();
    }

    interface Runs { int workers(); }

    interface Telegram { Optional<String> botToken(); Optional<String> chatId(); String apiUrl(); }
}
