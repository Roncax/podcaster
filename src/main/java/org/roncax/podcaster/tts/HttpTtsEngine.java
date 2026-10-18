package org.roncax.podcaster.tts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.roncax.podcaster.util.Retries;
import org.roncax.podcaster.util.RetryableException;

/**
 * Client for a Piper-shaped TTS server ({@code GET /voices}, {@code POST /synthesize} → WAV).
 * Used for local Piper and for the Sibilia service on Modal, which adds auth headers and may answer a slow
 * call with a 303 to its result URL (followed as a GET).
 */
public class HttpTtsEngine {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final String name;
    private final String baseUrl;
    private final Map<String, String> headers;
    private final int attempts;
    private final Duration retryDelay;
    private final Duration requestTimeout;
    private final Duration voicesTimeout;

    public HttpTtsEngine(String name, String baseUrl, Map<String, String> headers, int attempts, Duration retryDelay,
                         Duration requestTimeout, Duration voicesTimeout) {
        this.name = name;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.headers = Map.copyOf(headers);
        this.attempts = attempts;
        this.retryDelay = retryDelay;
        this.requestTimeout = requestTimeout;
        this.voicesTimeout = voicesTimeout;
    }

    public String name() { return name; }

    /** Host only, safe to show in the admin (never the headers). */
    public String host() { return URI.create(baseUrl).getHost(); }

    public byte[] synthesize(String text, VoiceConfig voice) throws Exception {
        String body = MAPPER.writeValueAsString(Map.of(
                "text", text, "voice", voice.voiceId(), "length_scale", voice.lengthScale()));
        HttpRequest request = withHeaders(HttpRequest.newBuilder(URI.create(baseUrl + "/synthesize")))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return Retries.withBackoff(attempts, retryDelay, () -> {
            HttpResponse<byte[]> response = send(request);
            int status = response.statusCode();
            checkCredentials(status);
            if (status >= 500 || status == 429) throw new RetryableException(name + " returned HTTP " + status);
            if (status != 200) throw new IllegalStateException(name + " returned HTTP " + status + ": " + preview(response.body()));
            if (!Wav.looksLikeWav(response.body())) {
                throw new IllegalStateException(name + " returned a non-WAV response (" + response.body().length + " bytes): " + preview(response.body()));
            }
            return response.body();
        });
    }

    public Set<String> voices() throws Exception {
        HttpRequest request = withHeaders(HttpRequest.newBuilder(URI.create(baseUrl + "/voices"))).timeout(voicesTimeout).GET().build();
        HttpResponse<byte[]> response = send(request);
        checkCredentials(response.statusCode());
        if (response.statusCode() != 200) throw new IllegalStateException(name + " /voices returned HTTP " + response.statusCode());
        JsonNode node = MAPPER.readTree(response.body());
        Set<String> voices = new TreeSet<>();
        node.fieldNames().forEachRemaining(voices::add);
        return voices;
    }

    private HttpRequest.Builder withHeaders(HttpRequest.Builder builder) {
        headers.forEach(builder::header);
        return builder;
    }

    private void checkCredentials(int status) {
        if (status == 401 || status == 403) throw new IllegalStateException(name + " rejected the credentials (HTTP " + status + ")");
    }

    private HttpResponse<byte[]> send(HttpRequest request) throws InterruptedException {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new RetryableException(name + " unreachable at " + baseUrl + ": " + e.getMessage(), e);
        }
    }

    private static String preview(byte[] body) {
        String s = new String(body, StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
