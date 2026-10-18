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
    Reddit reddit();

    interface Storage { String root(); String workDir(); }

    interface Http {
        String userAgent();
        Duration timeout();
        Duration politenessDelay();
        int attempts();
        Duration retryDelay();
        @io.smallrye.config.WithDefault("false") boolean allowPrivateHosts();
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
        int maxChunkChars();
        int parallelism();
        Duration chunkPause();
        Duration segmentPause();
        String ffmpeg();
        String bitrate();
        int attempts();
        Duration retryDelay();
        /** How long one /synthesize call may take; raise it for slow CPUs or heavy (-high) voices. */
        Duration requestTimeout();
        /** Sibilia-TTS on Modal; disabled while {@code url} is empty. */
        Sibilia sibilia();

        interface Sibilia {
            Optional<String> url();
            Optional<String> key();
            Optional<String> secret();
            int chunkChars();
            int maxChunkChars();
            int parallelism();
            Duration requestTimeout();
        }
    }

    interface Runs { int workers(); }

    interface Reddit { Duration requestDelay(); String baseUrl(); }

    interface Telegram { Optional<String> botToken(); Optional<String> chatId(); String apiUrl(); }
}
