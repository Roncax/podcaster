package org.roncax.podcaster.tts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.roncax.podcaster.config.PodcasterConfig;
import org.roncax.podcaster.util.Retries;
import org.roncax.podcaster.util.RetryableException;

@ApplicationScoped
public class PiperHttpTtsEngine implements TtsEngine {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String baseUrl;
    private final int attempts;
    private final Duration retryDelay;

    @Inject
    public PiperHttpTtsEngine(PodcasterConfig config) {
        this(config.tts().piperUrl(), config.tts().attempts(), config.tts().retryDelay());
    }

    public PiperHttpTtsEngine(String baseUrl, int attempts, Duration retryDelay) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.attempts = attempts;
        this.retryDelay = retryDelay;
    }

    @Override
    public byte[] synthesize(String text, VoiceConfig voice) throws Exception {
        String body = MAPPER.writeValueAsString(Map.of(
                "text", text, "voice", voice.voiceId(), "length_scale", voice.lengthScale()));
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/synthesize"))
                .timeout(Duration.ofMinutes(2))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return Retries.withBackoff(attempts, retryDelay, () -> {
            HttpResponse<byte[]> response = send(request);
            int status = response.statusCode();
            if (status >= 500) throw new RetryableException("Piper returned HTTP " + status);
            if (status != 200) throw new IllegalStateException("Piper returned HTTP " + status + ": " + preview(response.body()));
            if (!Wav.looksLikeWav(response.body())) {
                throw new IllegalStateException("Piper returned a non-WAV response (" + response.body().length + " bytes): " + preview(response.body()));
            }
            return response.body();
        });
    }

    @Override
    public Set<String> voices() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/voices")).timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<byte[]> response = send(request);
        if (response.statusCode() != 200) throw new IllegalStateException("Piper /voices returned HTTP " + response.statusCode());
        JsonNode node = MAPPER.readTree(response.body());
        Set<String> voices = new TreeSet<>();
        node.fieldNames().forEachRemaining(voices::add);
        return voices;
    }

    private HttpResponse<byte[]> send(HttpRequest request) throws InterruptedException {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new RetryableException("Piper unreachable at " + baseUrl + ": " + e.getMessage(), e);
        }
    }

    private static String preview(byte[] body) {
        String s = new String(body, StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
