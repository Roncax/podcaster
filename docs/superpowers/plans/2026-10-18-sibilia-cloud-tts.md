# Sibilia Cloud TTS Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let any show use the Sibilia-TTS Italian voice, run on a serverless L4 GPU at Modal. It sits next to the existing Piper voices and is chosen per show by voice name.

**Architecture:**
- **Modal side:** a Modal app (`cloud/sibilia/`) exposes a token-protected, Piper-shaped HTTP API (`GET /voices`, `POST /synthesize` → WAV). A CPU FastAPI front end validates each request, then hands synthesis to a GPU class.
- **Podcaster side:**
  - The Piper engine is generalized into a plain `HttpTtsEngine` with a name, headers and redirect-following.
  - A new `RoutingTtsEngine` bean (the only `TtsEngine`) sends `sibilia`/`sibilia-*` voices to Modal and everything else to Piper. It also supplies per-voice chunk size and parallelism (`TtsLimits`).
- `TtsStage` guards against resuming a run with chunks made by a different voice.

**Tech Stack:**
- **Podcaster:** Quarkus 3.40.1 and Java 25. Tests use WireMock and an embedded PostgreSQL; templates are Qute.
- **Cloud service:** Python 3.12, `modal`, FastAPI, `transformers==5.20.1`, PyTorch (CUDA) and pytest.

**Spec:** `docs/superpowers/specs/2026-10-18-sibilia-cloud-tts-design.md`

## Global Constraints

- Sibilia voice names are exactly `sibilia` (built-in) or `sibilia-<name>`, where `<name>` matches `[a-z0-9]+`. Routing is `voiceId.equals("sibilia") || voiceId.startsWith("sibilia-")`.
- Config keys:
  - `podcaster.tts.sibilia.url: ${PODCASTER_SIBILIA_URL:}`, where empty means disabled.
  - `key: ${PODCASTER_SIBILIA_KEY:}` and `secret: ${PODCASTER_SIBILIA_SECRET:}`.
  - `chunk-chars: 300`, `parallelism: 2` and `request-timeout: 5m`.
- Auth headers are `Modal-Key` and `Modal-Secret`, sent only when both are set. The key and secret are never logged, rendered or committed.
- Sibilia output is a 24 kHz mono 16-bit PCM WAV. `length_scale` is accepted and ignored.
- Generation cap: `max_new_tokens = ceil(len(text) / 14 * 12.5 * 1.5) + 50`. Reaching the cap → HTTP 500.
- **Modal GPU class:**
  - `gpu="L4"`, `scaledown_window=300`, `max_containers=2`, `timeout=600`.
  - Web front end uses `requires_proxy_auth=True`.
  - Model loads in bf16, and `backbone_model.embed_tokens.embed_audio_tokens.weight` is re-tied to `depth_decoder.model.embed_tokens.weight` after loading.
- **`/voices` timeouts:** Piper 10 s, Sibilia 30 s.
- **Errors and retries:**
  - 401/403 are never retried and give the message `<name> rejected the credentials (HTTP <code>)`.
  - 429 and 5xx are retried.
- Sibilia voice with Sibilia unconfigured → `Sibilia is not configured (PODCASTER_SIBILIA_URL)`.
- Run-dir marker file is `voice.txt`, with content `<voiceId>|<chunkChars>`.
- Build and test with `JAVA_HOME=$HOME/.jdks/temurin-25 ./mvnw test` (full suite) or `-Dtest=<Class>` for one class.
- Work in the worktree `/home/roncax/projects/podcaster-sibilia` (branch `feature/sibilia-cloud-tts`). Commit messages end with:
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01LbcoMupq1VXnfzdqroFmyp
  ```

## Review Focus

1. **Modal's 303 redirect after 150 s:** the client must follow it as a GET and keep the auth headers. Otherwise every cold-start chunk fails with a confusing 303 or 401. *(Task 1: `followsModalRedirectKeepingHeaders`)*
2. **Retry after switching a show's voice:** old 22 kHz Piper chunks must be discarded, not stitched together with 24 kHz Sibilia chunks, which would end in a WavConcat "format mismatch" failure. The same voice must still resume. *(Task 3: `voiceChangeDiscardsOldChunks`, `sameVoiceResumes`)*
3. **Sibilia down while Piper is fine:** the show form and settings must still list Piper voices. They must not show "Piper unreachable" or an empty select. *(Task 2: `sibiliaDownStillListsPiperVoices`; Task 4: settings shows "Unreachable")*
4. **Secrets in the UI:** the settings page shows the Sibilia host only, never the key or secret. *(Task 4: `settingsShowsSibiliaHostButNeverSecrets`)*
5. **Bad input to the cloud API:** empty text, over-long text, an unknown voice or a malformed voice name must give 400 before any GPU starts. A name like `sibilia-../x` must never touch the filesystem. *(Task 5: `test_rejects_*`)*

---

## File Structure

**Java (Podcaster)**
- Create `src/main/java/org/roncax/podcaster/tts/HttpTtsEngine.java`: Piper-protocol HTTP client with a name, headers, timeouts and redirects. Plain class.
- Create `src/main/java/org/roncax/podcaster/tts/TtsLimits.java`: record `(int chunkChars, int parallelism)`.
- Create `src/main/java/org/roncax/podcaster/tts/RoutingTtsEngine.java`: the `@ApplicationScoped` `TtsEngine` bean that routes, merges voices, supplies limits and reports Sibilia status.
- Delete `src/main/java/org/roncax/podcaster/tts/PiperHttpTtsEngine.java` and its test (replaced).
- Modify:
  - `tts/TtsEngine.java` (adds `limits`)
  - `config/PodcasterConfig.java` (adds `Tts.Sibilia`)
  - `src/main/resources/application.yml`
  - `runs/TtsStage.java` (limits plus the `voice.txt` guard)
  - `testing/VoiceDryRun.java` (limits; message)
  - `api/ShowService.java` (message)
- Admin:
  - Create `admin/VoiceExtensions.java` (Qute `localVoices`/`cloudVoices`).
  - Modify `admin/AdminResource.java` and `admin/AdminSupport.java`.
  - Modify templates `ShowPages/showFields.html`, `TestPages/panel.html` and `AdminResource/settings.html`.
- Tests:
  - Create `tts/HttpTtsEngineTest.java`, `tts/RoutingTtsEngineTest.java` and `runs/TtsStageVoiceTest.java`.
  - Modify `support/WireMockResource.java` (Sibilia stubs under `/sibilia`), `support/TestAudio.java` (sample-rate overload) and `admin/AdminTest.java`.

**Python (cloud service)**
- Create `cloud/sibilia/sibilia_core.py`: pure logic (voices, cap, conversation, WAV encoding) plus the FastAPI factory. Imports no `modal` or `torch` at module level.
- Create `cloud/sibilia/test_sibilia_core.py`: pytest, no GPU.
- Create `cloud/sibilia/sibilia_app.py`: Modal app (image, volumes, GPU class, web function, CLI functions).
- Create `cloud/sibilia/requirements-dev.txt`: local test and deploy dependencies.

**Docs**
- Modify `README.md`: new "Cloud TTS (Sibilia on Modal)" section.
- Modify `.env.example`, if present, with the three env vars.

---

### Task 1: `HttpTtsEngine`, a named Piper-protocol client with headers and redirects

**Files:**
- Create: `src/main/java/org/roncax/podcaster/tts/HttpTtsEngine.java`
- Create: `src/test/java/org/roncax/podcaster/tts/HttpTtsEngineTest.java`
- Modify: `src/test/java/org/roncax/podcaster/support/TestAudio.java`

**Interfaces:**
- Produces:
  - `public HttpTtsEngine(String name, String baseUrl, Map<String,String> headers, int attempts, Duration retryDelay, Duration requestTimeout, Duration voicesTimeout)`
  - `byte[] synthesize(String text, VoiceConfig voice) throws Exception`
  - `Set<String> voices() throws Exception`
  - `String name()`
  - `String host()`, which returns `URI.create(baseUrl).getHost()`
  - `TestAudio.sineWav(double seconds, int sampleRate)`
- `HttpTtsEngine` does **not** implement `TtsEngine`, because it has no `limits`; the router owns that.

- [ ] **Step 1: Add the sample-rate overload to `TestAudio`**

In `src/test/java/org/roncax/podcaster/support/TestAudio.java`, replace the body of `sineWav(double seconds)` so that it delegates:

```java
    /** 16-bit mono PCM WAV with a 440 Hz tone. */
    public static byte[] sineWav(double seconds) {
        return sineWav(seconds, SAMPLE_RATE);
    }

    /** Same tone at another sample rate (Sibilia returns 24 kHz). */
    public static byte[] sineWav(double seconds, int sampleRate) {
        int samples = (int) Math.round(seconds * sampleRate);
        ByteBuffer pcm = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < samples; i++) {
            pcm.putShort((short) (Math.sin(2 * Math.PI * 440 * i / sampleRate) * 8000));
        }
        return wav(pcm.array(), sampleRate, 1, 16);
    }
```

- [ ] **Step 2: Write the failing tests**

Create `src/test/java/org/roncax/podcaster/tts/HttpTtsEngineTest.java`:

```java
package org.roncax.podcaster.tts;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.*;
import org.roncax.podcaster.support.TestAudio;

class HttpTtsEngineTest {
    static WireMockServer wm;
    HttpTtsEngine engine;

    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }

    @BeforeEach
    void reset() {
        wm.resetAll();
        engine = engine("Piper", wm.baseUrl() + "/", Map.of(), 3, Duration.ofSeconds(5));
    }

    private static HttpTtsEngine engine(String name, String url, Map<String, String> headers, int attempts, Duration timeout) {
        return new HttpTtsEngine(name, url, headers, attempts, Duration.ofMillis(5), timeout, Duration.ofSeconds(10));
    }

    @Test
    void postsTextVoiceAndLengthScale() throws Exception {
        byte[] wav = TestAudio.sineWav(0.2);
        wm.stubFor(post("/synthesize").willReturn(aResponse().withStatus(200).withBody(wav)));
        assertArrayEquals(wav, engine.synthesize("Ciao a tutti.", new VoiceConfig("it_IT-paola-medium", 1.1)));
        wm.verify(postRequestedFor(urlEqualTo("/synthesize")).withRequestBody(
                equalToJson("{\"text\":\"Ciao a tutti.\",\"voice\":\"it_IT-paola-medium\",\"length_scale\":1.1}")));
    }

    @Test
    void retriesServerErrors() throws Exception {
        wm.stubFor(post("/synthesize").inScenario("p").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(serviceUnavailable()).willSetStateTo("up"));
        wm.stubFor(post("/synthesize").inScenario("p").whenScenarioStateIs("up")
                .willReturn(aResponse().withStatus(200).withBody(TestAudio.sineWav(0.1))));
        assertTrue(Wav.looksLikeWav(engine.synthesize("x", new VoiceConfig("v", 1.0))));
        wm.verify(2, postRequestedFor(urlEqualTo("/synthesize")));
    }

    @Test
    void retriesTooManyRequests() throws Exception {
        wm.stubFor(post("/synthesize").inScenario("r").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(429)).willSetStateTo("ok"));
        wm.stubFor(post("/synthesize").inScenario("r").whenScenarioStateIs("ok")
                .willReturn(aResponse().withStatus(200).withBody(TestAudio.sineWav(0.1))));
        assertTrue(Wav.looksLikeWav(engine.synthesize("x", new VoiceConfig("v", 1.0))));
        wm.verify(2, postRequestedFor(urlEqualTo("/synthesize")));
    }

    @Test
    void rejectsNonWavBody() {
        wm.stubFor(post("/synthesize").willReturn(aResponse().withStatus(200).withBody("<html>oops</html>")));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> engine.synthesize("x", new VoiceConfig("v", 1.0)));
        assertTrue(ex.getMessage().contains("Piper returned a non-WAV"), ex.getMessage());
    }

    @Test
    void clientErrorsAreNotRetried() {
        wm.stubFor(post("/synthesize").willReturn(aResponse().withStatus(400).withBody("bad voice")));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> engine.synthesize("x", new VoiceConfig("missing", 1.0)));
        assertTrue(ex.getMessage().contains("400"), ex.getMessage());
        assertTrue(ex.getMessage().contains("bad voice"), ex.getMessage());
        wm.verify(1, postRequestedFor(urlEqualTo("/synthesize")));
    }

    @Test
    void rejectedCredentialsAreNotRetriedAndNameTheEngine() {
        HttpTtsEngine sibilia = engine("Sibilia", wm.baseUrl(), Map.of("Modal-Key", "k", "Modal-Secret", "s"), 3, Duration.ofSeconds(5));
        wm.stubFor(post("/synthesize").willReturn(aResponse().withStatus(401).withBody("missing credentials")));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> sibilia.synthesize("x", new VoiceConfig("sibilia", 1.0)));
        assertEquals("Sibilia rejected the credentials (HTTP 401)", ex.getMessage());
        wm.verify(1, postRequestedFor(urlEqualTo("/synthesize")));
    }

    @Test
    void sendsConfiguredHeaders() throws Exception {
        HttpTtsEngine sibilia = engine("Sibilia", wm.baseUrl(), Map.of("Modal-Key", "wk-1", "Modal-Secret", "ws-2"), 1, Duration.ofSeconds(5));
        wm.stubFor(post("/synthesize").willReturn(aResponse().withStatus(200).withBody(TestAudio.sineWav(0.1, 24000))));
        wm.stubFor(get("/voices").willReturn(okJson("{\"sibilia\":{}}")));
        sibilia.synthesize("x", new VoiceConfig("sibilia", 1.0));
        sibilia.voices();
        wm.verify(postRequestedFor(urlEqualTo("/synthesize")).withHeader("Modal-Key", equalTo("wk-1")).withHeader("Modal-Secret", equalTo("ws-2")));
        wm.verify(getRequestedFor(urlEqualTo("/voices")).withHeader("Modal-Key", equalTo("wk-1")).withHeader("Modal-Secret", equalTo("ws-2")));
    }

    @Test
    void followsModalRedirectKeepingHeaders() throws Exception {
        HttpTtsEngine sibilia = engine("Sibilia", wm.baseUrl(), Map.of("Modal-Key", "wk-1", "Modal-Secret", "ws-2"), 1, Duration.ofSeconds(5));
        byte[] wav = TestAudio.sineWav(0.1, 24000);
        wm.stubFor(post("/synthesize").willReturn(aResponse().withStatus(303).withHeader("Location", "/synthesize?__modal_function_call_id=fc-1")));
        wm.stubFor(get("/synthesize?__modal_function_call_id=fc-1").willReturn(aResponse().withStatus(200).withBody(wav)));
        assertArrayEquals(wav, sibilia.synthesize("x", new VoiceConfig("sibilia", 1.0)));
        wm.verify(getRequestedFor(urlEqualTo("/synthesize?__modal_function_call_id=fc-1"))
                .withHeader("Modal-Key", equalTo("wk-1")).withHeader("Modal-Secret", equalTo("ws-2")));
    }

    @Test
    void listsVoices() throws Exception {
        wm.stubFor(get("/voices").willReturn(okJson("{\"it_IT-paola-medium\":{\"x\":1},\"en_US-lessac-medium\":{}}")));
        assertEquals(Set.of("it_IT-paola-medium", "en_US-lessac-medium"), engine.voices());
    }

    @Test
    void voicesTimeoutIsConfigurable() {
        wm.stubFor(get("/voices").willReturn(okJson("{}").withFixedDelay(1500)));
        HttpTtsEngine impatient = new HttpTtsEngine("Sibilia", wm.baseUrl(), Map.of(), 1, Duration.ofMillis(5),
                Duration.ofSeconds(5), Duration.ofMillis(300));
        Exception ex = assertThrows(Exception.class, impatient::voices);
        assertTrue(ex.getMessage().contains("Sibilia"), ex.getMessage());
    }

    @Test
    void requestTimeoutIsConfigurable() throws Exception {
        wm.stubFor(post("/synthesize").willReturn(aResponse().withStatus(200).withBody(TestAudio.sineWav(0.1)).withFixedDelay(1500)));

        HttpTtsEngine impatient = engine("Piper", wm.baseUrl(), Map.of(), 2, Duration.ofMillis(300));
        Exception ex = assertThrows(Exception.class, () -> impatient.synthesize("x", new VoiceConfig("v", 1.0)));
        assertTrue(ex.getMessage().contains("timed out"), ex.getMessage());
        wm.verify(2, postRequestedFor(urlEqualTo("/synthesize")));

        HttpTtsEngine patient = engine("Piper", wm.baseUrl(), Map.of(), 1, Duration.ofSeconds(5));
        assertTrue(Wav.looksLikeWav(patient.synthesize("x", new VoiceConfig("v", 1.0))));
    }

    @Test
    void exposesNameAndHostOnly() {
        HttpTtsEngine sibilia = engine("Sibilia", "https://ws--podcaster-sibilia-api.modal.run", Map.of("Modal-Key", "k"), 1, Duration.ofSeconds(5));
        assertEquals("Sibilia", sibilia.name());
        assertEquals("ws--podcaster-sibilia-api.modal.run", sibilia.host());
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `cd /home/roncax/projects/podcaster-sibilia && JAVA_HOME=$HOME/.jdks/temurin-25 ./mvnw -q test -Dtest=HttpTtsEngineTest`
Expected: a compilation failure, `cannot find symbol: class HttpTtsEngine`.

- [ ] **Step 4: Implement `HttpTtsEngine`**

Create `src/main/java/org/roncax/podcaster/tts/HttpTtsEngine.java`:

```java
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
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `JAVA_HOME=$HOME/.jdks/temurin-25 ./mvnw -q test -Dtest=HttpTtsEngineTest`
Expected: PASS, 12 tests.

**If `followsModalRedirectKeepingHeaders` fails** because the GET lacks the headers: the JDK client dropped user headers on redirect. Switch to `HttpClient.Redirect.NEVER` and follow 303/302/307 manually inside the retry lambda. Re-issue a GET to `request.uri().resolve(location)` with the same headers, up to 20 hops (Modal allows 20 × 150 s), then re-run the tests.

**If `voicesTimeoutIsConfigurable` fails** on the message: `HttpTimeoutException` is an `IOException`, so it is wrapped by `send` with the engine name. Check that `voices()` calls `send`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/roncax/podcaster/tts/HttpTtsEngine.java src/test/java/org/roncax/podcaster/tts/HttpTtsEngineTest.java src/test/java/org/roncax/podcaster/support/TestAudio.java
git commit -m "Add HttpTtsEngine: named Piper-protocol client with auth headers and redirect following

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01LbcoMupq1VXnfzdqroFmyp"
```

---

### Task 2: `RoutingTtsEngine`, `TtsLimits` and Sibilia configuration

**Files:**
- Create: `src/main/java/org/roncax/podcaster/tts/TtsLimits.java`
- Create: `src/main/java/org/roncax/podcaster/tts/RoutingTtsEngine.java`
- Create: `src/test/java/org/roncax/podcaster/tts/RoutingTtsEngineTest.java`
- Modify: `src/main/java/org/roncax/podcaster/tts/TtsEngine.java`
- Modify: `src/main/java/org/roncax/podcaster/config/PodcasterConfig.java`
- Modify: `src/main/resources/application.yml` (the `podcaster.tts` block)
- Modify: `src/main/java/org/roncax/podcaster/api/ShowService.java:103`
- Modify: `src/main/java/org/roncax/podcaster/testing/VoiceDryRun.java` (the "not installed in Piper" message)
- Modify: `src/test/java/org/roncax/podcaster/support/WireMockResource.java`
- Delete: `src/main/java/org/roncax/podcaster/tts/PiperHttpTtsEngine.java`
- Delete: `src/test/java/org/roncax/podcaster/tts/PiperHttpTtsEngineTest.java`

**Interfaces:**
- Consumes: the `HttpTtsEngine` constructor from Task 1, plus `name()`, `host()`, `synthesize` and `voices`.
- Produces:
  - `public record TtsLimits(int chunkChars, int parallelism)`
  - `TtsEngine.limits(String voiceId)` returning `TtsLimits`
  - `public static boolean RoutingTtsEngine.isSibilia(String voiceId)`
  - `public String RoutingTtsEngine.sibiliaStatus()`, which returns `"Not configured"`, `"Connected · <host>"` or `"Unreachable · <host>"`
  - Test config: `podcaster.tts.sibilia.url = <wiremock>/sibilia`, with stubs `GET /sibilia/voices` → `{"sibilia":{}}` and `POST /sibilia/synthesize` → a 24 kHz WAV.

- [ ] **Step 1: Write the failing unit tests**

Create `src/test/java/org/roncax/podcaster/tts/RoutingTtsEngineTest.java`:

```java
package org.roncax.podcaster.tts;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.*;
import org.roncax.podcaster.support.TestAudio;

class RoutingTtsEngineTest {
    static WireMockServer wm;
    static final TtsLimits PIPER = new TtsLimits(500, 2);
    static final TtsLimits SIBILIA = new TtsLimits(300, 2);

    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }

    @BeforeEach
    void stubs() {
        wm.resetAll();
        wm.stubFor(get("/piper/voices").willReturn(okJson("{\"it_IT-paola-medium\":{}}")));
        wm.stubFor(post("/piper/synthesize").willReturn(aResponse().withStatus(200).withBody(TestAudio.sineWav(0.1))));
        wm.stubFor(get("/sibilia/voices").willReturn(okJson("{\"sibilia\":{},\"sibilia-anna\":{}}")));
        wm.stubFor(post("/sibilia/synthesize").willReturn(aResponse().withStatus(200).withBody(TestAudio.sineWav(0.1, 24000))));
    }

    private static HttpTtsEngine http(String name, String path) {
        return new HttpTtsEngine(name, wm.baseUrl() + path, Map.of(), 1, Duration.ofMillis(5), Duration.ofSeconds(5), Duration.ofSeconds(2));
    }

    private static RoutingTtsEngine router(boolean withSibilia) {
        return new RoutingTtsEngine(http("Piper", "/piper"), withSibilia ? Optional.of(http("Sibilia", "/sibilia")) : Optional.empty(), PIPER, SIBILIA);
    }

    @Test
    void recognisesSibiliaVoices() {
        assertTrue(RoutingTtsEngine.isSibilia("sibilia"));
        assertTrue(RoutingTtsEngine.isSibilia("sibilia-anna"));
        assertFalse(RoutingTtsEngine.isSibilia("sibilian"));
        assertFalse(RoutingTtsEngine.isSibilia("it_IT-paola-medium"));
        assertFalse(RoutingTtsEngine.isSibilia(null));
    }

    @Test
    void routesByVoice() throws Exception {
        RoutingTtsEngine router = router(true);
        router.synthesize("ciao", new VoiceConfig("sibilia-anna", 1.0));
        router.synthesize("ciao", new VoiceConfig("it_IT-paola-medium", 1.0));
        wm.verify(1, postRequestedFor(urlEqualTo("/sibilia/synthesize")).withRequestBody(matchingJsonPath("$.voice", equalTo("sibilia-anna"))));
        wm.verify(1, postRequestedFor(urlEqualTo("/piper/synthesize")).withRequestBody(matchingJsonPath("$.voice", equalTo("it_IT-paola-medium"))));
    }

    @Test
    void mergesVoices() throws Exception {
        assertEquals(Set.of("it_IT-paola-medium", "sibilia", "sibilia-anna"), router(true).voices());
    }

    @Test
    void sibiliaDownStillListsPiperVoices() throws Exception {
        wm.stubFor(get("/sibilia/voices").willReturn(serverError()));
        assertEquals(Set.of("it_IT-paola-medium"), router(true).voices());
    }

    @Test
    void unconfiguredSibiliaListsPiperOnlyAndRefusesSibiliaVoices() throws Exception {
        RoutingTtsEngine router = router(false);
        assertEquals(Set.of("it_IT-paola-medium"), router.voices());
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> router.synthesize("ciao", new VoiceConfig("sibilia", 1.0)));
        assertEquals("Sibilia is not configured (PODCASTER_SIBILIA_URL)", ex.getMessage());
        wm.verify(0, postRequestedFor(urlPathMatching("/.*synthesize")));
    }

    @Test
    void limitsPerVoice() {
        RoutingTtsEngine router = router(true);
        assertEquals(SIBILIA, router.limits("sibilia"));
        assertEquals(PIPER, router.limits("it_IT-paola-medium"));
    }

    @Test
    void sibiliaStatus() {
        assertEquals("Not configured", router(false).sibiliaStatus());
        assertEquals("Connected · localhost", router(true).sibiliaStatus());
        wm.stubFor(get("/sibilia/voices").willReturn(serverError()));
        assertEquals("Unreachable · localhost", router(true).sibiliaStatus());
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `JAVA_HOME=$HOME/.jdks/temurin-25 ./mvnw -q test -Dtest=RoutingTtsEngineTest`
Expected: a compilation failure, `cannot find symbol: class RoutingTtsEngine` / `TtsLimits`.

- [ ] **Step 3: Add `TtsLimits` and `TtsEngine.limits`**

Create `src/main/java/org/roncax/podcaster/tts/TtsLimits.java`:

```java
package org.roncax.podcaster.tts;

/** How a voice's text is split and how many chunks may be synthesized at once. */
public record TtsLimits(int chunkChars, int parallelism) {}
```

Replace `src/main/java/org/roncax/podcaster/tts/TtsEngine.java` with:

```java
package org.roncax.podcaster.tts;

import java.util.Set;

public interface TtsEngine {
    /** Returns a PCM WAV file. */
    byte[] synthesize(String text, VoiceConfig voice) throws Exception;

    Set<String> voices() throws Exception;

    /** Chunk size and parallelism to use for this voice's engine. */
    TtsLimits limits(String voiceId);
}
```

- [ ] **Step 4: Add the config**

In `src/main/java/org/roncax/podcaster/config/PodcasterConfig.java`, inside `interface Tts`, after `Duration requestTimeout();`, add:

```java
        /** Sibilia-TTS on Modal; disabled while {@code url} is empty. */
        Sibilia sibilia();

        interface Sibilia {
            Optional<String> url();
            Optional<String> key();
            Optional<String> secret();
            int chunkChars();
            int parallelism();
            Duration requestTimeout();
        }
```

In `src/main/resources/application.yml`, under `podcaster.tts`, after the `request-timeout:` line, add (two-space YAML indentation, matching the siblings):

```yaml
    sibilia:                # Sibilia-TTS on Modal (cloud/sibilia); empty url = disabled
      url: ${PODCASTER_SIBILIA_URL:}
      key: ${PODCASTER_SIBILIA_KEY:}
      secret: ${PODCASTER_SIBILIA_SECRET:}
      chunk-chars: 300
      parallelism: 2
      request-timeout: 5m
```

SmallRye maps an empty `${VAR:}` to `Optional.empty()` for `Optional<String>`.

- [ ] **Step 5: Implement `RoutingTtsEngine` and delete the Piper engine**

Create `src/main/java/org/roncax/podcaster/tts/RoutingTtsEngine.java`:

```java
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

/** The application's TTS engine: Sibilia voices go to the Modal service, all others to Piper. */
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

    public static boolean isSibilia(String voiceId) {
        return voiceId != null && (voiceId.equals("sibilia") || voiceId.startsWith("sibilia-"));
    }

    @Override
    public byte[] synthesize(String text, VoiceConfig voice) throws Exception {
        if (!isSibilia(voice.voiceId())) return piper.synthesize(text, voice);
        return sibilia.orElseThrow(() -> new IllegalStateException("Sibilia is not configured (PODCASTER_SIBILIA_URL)"))
                .synthesize(text, voice);
    }

    @Override
    public Set<String> voices() throws Exception {
        Set<String> voices = new TreeSet<>(piper.voices());
        if (sibilia.isPresent()) {
            try {
                voices.addAll(sibilia.get().voices());
            } catch (Exception e) {
                LOG.warnf("Sibilia voices unavailable: %s", e.getMessage());
            }
        }
        return voices;
    }

    @Override
    public TtsLimits limits(String voiceId) {
        return isSibilia(voiceId) ? sibiliaLimits : piperLimits;
    }

    /** For the settings page: never includes the credentials. */
    public String sibiliaStatus() {
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
        return Optional.of(new HttpTtsEngine("Sibilia", url.get(), headers, tts.attempts(), tts.retryDelay(),
                s.requestTimeout(), Duration.ofSeconds(30)));
    }
}
```

Then delete the replaced engine and its test:

```bash
git rm src/main/java/org/roncax/podcaster/tts/PiperHttpTtsEngine.java src/test/java/org/roncax/podcaster/tts/PiperHttpTtsEngineTest.java
```

- [ ] **Step 6: Make the messages engine-neutral**

In `src/main/java/org/roncax/podcaster/api/ShowService.java`, line 103, change:

```java
                errors.add("voiceId '" + r.voiceId() + "' is not installed in Piper; available: " + voices);
```
to
```java
                errors.add("voiceId '" + r.voiceId() + "' is not available; available: " + voices);
```

In `src/main/java/org/roncax/podcaster/testing/VoiceDryRun.java`, change:

```java
                if (!voices.contains(voice)) errors.add("voiceId '" + voice + "' is not installed in Piper; available: " + voices);
```
to
```java
                if (!voices.contains(voice)) errors.add("voiceId '" + voice + "' is not available; available: " + voices);
```

and the comment `// Piper unreachable: the step itself will report it` to `// TTS unreachable: the step itself will report it`.

Then run `grep -rn "installed in Piper" src/` and update any test that asserted the old text.

- [ ] **Step 7: Enable a fake Sibilia in the Quarkus test resource**

In `src/test/java/org/roncax/podcaster/support/WireMockResource.java`, add the Sibilia URL to the returned config map:

```java
        return Map.of(
                "podcaster.tts.piper-url", server.baseUrl(),
                "podcaster.tts.sibilia.url", server.baseUrl() + "/sibilia",
                "podcaster.tts.sibilia.key", "wk-test-key",
                "podcaster.tts.sibilia.secret", "ws-test-secret",
                "podcaster.telegram.api-url", server.baseUrl(),
                "podcaster.reddit.base-url", server.baseUrl());
```

(The fake credentials let Task 4 prove that the admin never renders them.)

In `installDefaults`, after the Piper stubs, add:

```java
        server.stubFor(get("/sibilia/voices").willReturn(okJson("{\"sibilia\":{}}")));
        server.stubFor(post("/sibilia/synthesize").willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "audio/wav").withBody(TestAudio.sineWav(0.5, 24000))));
```

- [ ] **Step 8: Run the unit tests, then the full suite**

Run: `JAVA_HOME=$HOME/.jdks/temurin-25 ./mvnw -q test -Dtest=RoutingTtsEngineTest`
Expected: PASS, 7 tests.

Run: `JAVA_HOME=$HOME/.jdks/temurin-25 ./mvnw -q test`
Expected: PASS. In particular `AdminTest` (`TTS: Piper returned HTTP 503`), `RunPipelineTest`, `VoiceTestApiTest` and `ApiTest` (`/api/meta/voices` has `it_IT-paola-medium`). `TtsStage` and `VoiceDryRun` still compile, because they don't call `limits` yet.

- [ ] **Step 9: Commit**

```bash
git add -A src/main/java/org/roncax/podcaster/tts src/main/java/org/roncax/podcaster/config/PodcasterConfig.java src/main/resources/application.yml src/main/java/org/roncax/podcaster/api/ShowService.java src/main/java/org/roncax/podcaster/testing/VoiceDryRun.java src/test/java/org/roncax/podcaster/tts src/test/java/org/roncax/podcaster/support/WireMockResource.java
git commit -m "Route sibilia voices to the Modal service and everything else to Piper

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01LbcoMupq1VXnfzdqroFmyp"
```

---

### Task 3: Per-voice limits in `TtsStage`/`VoiceDryRun` and the voice-change guard

**Files:**
- Modify: `src/main/java/org/roncax/podcaster/runs/TtsStage.java:43-80`
- Modify: `src/main/java/org/roncax/podcaster/testing/VoiceDryRun.java` (`execute`)
- Create: `src/test/java/org/roncax/podcaster/runs/TtsStageVoiceTest.java`

**Interfaces:**
- Consumes: `TtsEngine.limits(String)` and `TtsLimits` from Task 2, plus the test stubs at `/sibilia/*` (24 kHz) and `/synthesize` (Piper, 22.05 kHz).
- Produces: the run-dir marker `voice.txt` with content `<voiceId>|<chunkChars>`, and `static void TtsStage.resetChunksIfVoiceChanged(Path dir, String marker)` (package-private for tests).

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/org/roncax/podcaster/runs/TtsStageVoiceTest.java`:

```java
package org.roncax.podcaster.runs;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.config.PodcasterConfig;
import org.roncax.podcaster.domain.*;
import org.roncax.podcaster.support.*;

@QuarkusTest
@WithTestResource(WireMockResource.class)
@WithTestResource(org.roncax.podcaster.support.PostgresResource.class)
class TtsStageVoiceTest {
    /** Nine ~100-character sentences: 2 chunks at Piper's 500 characters, 3+ at Sibilia's 300. */
    static final String TEXT = String.join(" ", java.util.Collections.nCopies(9,
            "Questa è una frase di prova abbastanza lunga da occupare circa cento caratteri nel testo finale."));

    @InjectWireMock WireMockServer wm;
    @Inject TtsStage stage;
    @Inject PodcasterConfig config;

    @BeforeEach
    void setup() {
        TestData.cleanDb();
        WireMockResource.installDefaults(wm);
    }

    private Run run(String voiceId) {
        Show show = TestData.show("voice" + System.nanoTime());
        return QuarkusTransaction.requiringNew().call(() -> {
            Show s = Show.findById(show.id);
            s.voiceId = voiceId;
            Run r = new Run();
            r.showId = show.id; r.trigger = RunTrigger.MANUAL; r.status = RunStatus.RUNNING; r.stage = RunStage.TTS;
            r.since = Instant.now();
            r.persist();
            Episode e = new Episode();
            e.runId = r.id; e.showId = show.id; e.title = "Episodio"; e.scriptParts = List.of(TEXT);
            e.persist();
            return r;
        });
    }

    private static int sibiliaCalls(WireMockServer wm) {
        return wm.findAll(postRequestedFor(urlEqualTo("/sibilia/synthesize"))).size();
    }

    @Test
    void sibiliaVoiceUsesItsOwnChunkSize() throws Exception {
        Run run = run("sibilia");
        stage.execute(run);
        assertTrue(sibiliaCalls(wm) >= 3, "expected 300-char chunks, got " + sibiliaCalls(wm));
        wm.verify(0, postRequestedFor(urlEqualTo("/synthesize")));
        assertEquals("sibilia|300", Files.readString(TtsStage.workDir(config, run.id).resolve("voice.txt")));
    }

    @Test
    void piperVoiceKeepsGlobalChunkSize() throws Exception {
        stage.execute(run("it_IT-paola-medium"));
        wm.verify(2, postRequestedFor(urlEqualTo("/synthesize")));
        assertEquals(0, sibiliaCalls(wm));
    }

    @Test
    void voiceChangeDiscardsOldChunks() throws Exception {
        Run run = run("sibilia");
        Path dir = TtsStage.workDir(config, run.id);
        Files.createDirectories(dir);
        Files.write(dir.resolve("chunk-0000.wav"), TestAudio.sineWav(0.5)); // 22.05 kHz, made by Piper
        Files.writeString(dir.resolve("voice.txt"), "it_IT-paola-medium|500");

        stage.execute(run); // would fail with a format mismatch if the Piper chunk were reused

        int chunks = sibiliaCalls(wm);
        assertTrue(chunks >= 3, "every chunk re-synthesized with Sibilia, got " + chunks);
        assertEquals("sibilia|300", Files.readString(dir.resolve("voice.txt")));
    }

    @Test
    void sameVoiceResumes() throws Exception {
        Run run = run("sibilia");
        Path dir = TtsStage.workDir(config, run.id);
        Files.createDirectories(dir);
        Files.write(dir.resolve("chunk-0000.wav"), TestAudio.sineWav(0.5, 24000));
        Files.writeString(dir.resolve("voice.txt"), "sibilia|300");

        stage.execute(run);
        int resumed = sibiliaCalls(wm);

        wm.resetRequests();
        stage.execute(run("sibilia"));
        int fresh = sibiliaCalls(wm);

        assertEquals(fresh - 1, resumed, "chunk 0 was reused");
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `JAVA_HOME=$HOME/.jdks/temurin-25 ./mvnw -q test -Dtest=TtsStageVoiceTest`
Expected: FAIL.
- `sibiliaVoiceUsesItsOwnChunkSize` fails because there are only 2 calls (global 500) and no `voice.txt`.
- `voiceChangeDiscardsOldChunks` fails with a WavConcat format-mismatch `IllegalStateException`.

- [ ] **Step 3: Implement limits and the guard in `TtsStage`**

In `src/main/java/org/roncax/podcaster/runs/TtsStage.java`, replace the block from `Path dir = workDir(config, run.id);` through `ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, cfg.parallelism()));` with:

```java
        Path dir = workDir(config, run.id);
        Files.createDirectories(dir);
        PodcasterConfig.Tts cfg = config.tts();
        TtsLimits limits = tts.limits(show.voiceId);
        resetChunksIfVoiceChanged(dir, show.voiceId + "|" + limits.chunkChars());
        List<TtsChunk> chunks = ScriptChunker.chunk(episode.scriptParts, limits.chunkChars(), cfg.chunkPause(), cfg.segmentPause());
        VoiceConfig voice = new VoiceConfig(show.voiceId, show.lengthScale);

        List<Path> files = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, limits.parallelism()));
```

Add this method below `synthesize(...)`:

```java
    /**
     * Chunks from an earlier attempt are reused on retry, but only if they were made by the same voice and chunk size:
     * otherwise their indexes and audio format (Piper 22.05 kHz vs Sibilia 24 kHz) would not line up.
     */
    static void resetChunksIfVoiceChanged(Path dir, String marker) throws java.io.IOException {
        Path file = dir.resolve("voice.txt");
        if (Files.exists(file) && !Files.readString(file).equals(marker)) {
            try (var chunks = Files.newDirectoryStream(dir, "chunk-*")) {
                for (Path chunk : chunks) Files.delete(chunk);
            }
        }
        Files.writeString(file, marker);
    }
```

`TtsLimits` comes in through the existing `import org.roncax.podcaster.tts.*;`.

- [ ] **Step 4: Use limits in `VoiceDryRun`**

In `src/main/java/org/roncax/podcaster/testing/VoiceDryRun.java`, method `execute`, change:

```java
        List<TtsChunk> chunks = ScriptChunker.chunk(List.of(text), cfg.chunkChars(), cfg.chunkPause(), cfg.segmentPause());
```
to
```java
        List<TtsChunk> chunks = ScriptChunker.chunk(List.of(text), tts.limits(p.voiceId()).chunkChars(), cfg.chunkPause(), cfg.segmentPause());
```

- [ ] **Step 5: Run the tests**

Run: `JAVA_HOME=$HOME/.jdks/temurin-25 ./mvnw -q test -Dtest='TtsStageVoiceTest,RunPipelineTest,VoiceTestApiTest'`
Expected: PASS. The real ffmpeg is used, as in `RunPipelineTest`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/roncax/podcaster/runs/TtsStage.java src/main/java/org/roncax/podcaster/testing/VoiceDryRun.java src/test/java/org/roncax/podcaster/runs/TtsStageVoiceTest.java
git commit -m "Chunk per voice engine and discard a run's chunks when its voice changes

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01LbcoMupq1VXnfzdqroFmyp"
```

---

### Task 4: Admin UI: grouped voice selects and Sibilia status on Settings

**Files:**
- Create: `src/main/java/org/roncax/podcaster/admin/VoiceExtensions.java`
- Modify: `src/main/resources/templates/ShowPages/showFields.html:14-17`
- Modify: `src/main/resources/templates/TestPages/panel.html:67`
- Modify: `src/main/resources/templates/AdminResource/settings.html:7-8,11-16`
- Modify: `src/main/java/org/roncax/podcaster/admin/AdminResource.java` (the `settings` template and method)
- Modify: `src/test/java/org/roncax/podcaster/admin/AdminTest.java`

**Interfaces:**
- Consumes: `RoutingTtsEngine.isSibilia(String)` and `RoutingTtsEngine.sibiliaStatus()` from Task 2.
- Produces: the Qute extensions `{voices.localVoices}` and `{voices.cloudVoices}` (on `Collection<String>`, returning `List<String>`), and a new `String sibilia` parameter on `Templates.settings`.

- [ ] **Step 1: Write the failing tests**

In `src/test/java/org/roncax/podcaster/admin/AdminTest.java`, add these tests and keep the existing ones. Add imports for `static org.hamcrest.Matchers.*` if they're not already present.

```java
    @Test
    void settingsShowsSibiliaHostButNeverSecrets() {
        admin().get("/admin/settings").then().statusCode(200)
                .body(containsString("Sibilia"))
                .body(containsString("Connected · localhost"))
                .body(containsString("Sibilia (cloud GPU)"))
                .body(not(containsString("wk-test-key")))
                .body(not(containsString("ws-test-secret")));
    }

    @Test
    void settingsReportsUnreachableSibilia() {
        wm.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get("/sibilia/voices")
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.serverError()));
        admin().get("/admin/settings").then().statusCode(200)
                .body(containsString("Unreachable · localhost"))
                .body(containsString("it_IT-paola-medium"));
    }

    @Test
    void showFormGroupsLocalAndCloudVoices() {
        String html = admin().get("/admin/shows").then().statusCode(200).extract().asString();
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("<optgroup label=\"Piper (local)\">"), html);
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("<optgroup label=\"Sibilia (cloud GPU)\">"), html);
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("<option value=\"sibilia\""), html);
    }
```

If `AdminTest` has no `@InjectWireMock WireMockServer wm;` field yet, add it, plus `@BeforeEach`-level `WireMockResource.installDefaults(wm);` if the class doesn't already reset stubs. Check the class header first and follow its existing setup. Also check that `admin().get("/admin/shows")` renders the create-show form (`ShowPages/list.html` includes `showFields`). If the list page path differs, use the path the existing `ShowPagesTest` uses for the create form.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `JAVA_HOME=$HOME/.jdks/temurin-25 ./mvnw -q test -Dtest=AdminTest`
Expected: the three new tests FAIL (no optgroups and no Sibilia status).

- [ ] **Step 3: Add the template extensions**

Create `src/main/java/org/roncax/podcaster/admin/VoiceExtensions.java`:

```java
package org.roncax.podcaster.admin;

import io.quarkus.qute.TemplateExtension;
import java.util.Collection;
import java.util.List;
import org.roncax.podcaster.tts.RoutingTtsEngine;

/** {@code {voices.localVoices}} / {@code {voices.cloudVoices}}: split a voice list by engine for grouped selects. */
@TemplateExtension
public class VoiceExtensions {
    private VoiceExtensions() {}

    static List<String> localVoices(Collection<String> voices) {
        return voices.stream().filter(v -> !RoutingTtsEngine.isSibilia(v)).toList();
    }

    static List<String> cloudVoices(Collection<String> voices) {
        return voices.stream().filter(RoutingTtsEngine::isSibilia).toList();
    }
}
```

- [ ] **Step 4: Group the voice selects**

In `src/main/resources/templates/ShowPages/showFields.html`, replace lines 15–16, the two `{#for v in voices}…` and `{#if voices.isEmpty}…` lines inside the `voiceId` `<select>`, with:

```html
        {#if !voices.localVoices.isEmpty}<optgroup label="Piper (local)">{#for v in voices.localVoices}<option value="{v}" {#if v == form.voiceId}selected{/if}>{v}</option>{/for}</optgroup>{/if}
        {#if !voices.cloudVoices.isEmpty}<optgroup label="Sibilia (cloud GPU)">{#for v in voices.cloudVoices}<option value="{v}" {#if v == form.voiceId}selected{/if}>{v}</option>{/for}</optgroup>{/if}
        {#if voices.isEmpty}<option value="{form.voiceId ?: ''}" selected>{form.voiceId ?: 'TTS unreachable'}</option>{/if}
```

In `src/main/resources/templates/TestPages/panel.html`, line 67, replace the `<select class="field-input" name="voiceId">…</select>` contents the same way:

```html
<select class="field-input" name="voiceId">{#if !voices.localVoices.isEmpty}<optgroup label="Piper (local)">{#for v in voices.localVoices}<option value="{v}" {#if v == form.voiceId}selected{/if}>{v}</option>{/for}</optgroup>{/if}{#if !voices.cloudVoices.isEmpty}<optgroup label="Sibilia (cloud GPU)">{#for v in voices.cloudVoices}<option value="{v}" {#if v == form.voiceId}selected{/if}>{v}</option>{/for}</optgroup>{/if}</select>
```

Run `grep -rn "Piper unreachable" src/test` and update any assertion to `TTS unreachable`.

- [ ] **Step 5: Show Sibilia status on Settings**

In `src/main/java/org/roncax/podcaster/admin/AdminResource.java`:
- Change the template signature to:

```java
        static native TemplateInstance settings(Set<String> models, Set<String> voices, Set<String> connectors,
                                                boolean telegram, String baseUrl, String version, String sibilia);
```

- Add `@Inject org.roncax.podcaster.tts.RoutingTtsEngine tts;` next to the other injections.
- In `settings()`, change the return to:

```java
        return Templates.settings(models.availableNames(), support.voices(), connectors.types(), telegram, config.baseUrl(), version,
                tts.sibiliaStatus());
```

In `src/main/resources/templates/AdminResource/settings.html`, replace the "Piper voices" section (lines 7–8) with:

```html
  <section class="card"><h2 class="m-0 text-base font-semibold">Voices</h2>
    <h3 class="m-0 text-sm text-muted">Piper (local)</h3>
    <ul class="m-0 flex flex-wrap gap-2 p-0" role="list">{#for v in voices.localVoices}<li class="chip mono">{v}</li>{#else}<li class="text-sm text-muted">Piper unreachable</li>{/for}</ul>
    {#if !voices.cloudVoices.isEmpty}<h3 class="m-0 text-sm text-muted">Sibilia (cloud GPU)</h3>
    <ul class="m-0 flex flex-wrap gap-2 p-0" role="list">{#for v in voices.cloudVoices}<li class="chip mono">{v}</li>{/for}</ul>{/if}</section>
```

In the System `<dl>`, after the Telegram row, add:

```html
      <dt class="text-muted">Sibilia</dt><dd class="m-0 mono">{sibilia}</dd>
```

- [ ] **Step 6: Run the tests**

Run: `JAVA_HOME=$HOME/.jdks/temurin-25 ./mvnw -q test -Dtest='AdminTest,ShowPagesTest,TestPagesTest'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/roncax/podcaster/admin src/main/resources/templates src/test/java/org/roncax/podcaster/admin
git commit -m "Admin: group voices by engine and show Sibilia status on Settings

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01LbcoMupq1VXnfzdqroFmyp"
```

---

### Task 5: Cloud service core (`sibilia_core.py`) with tests

**Files:**
- Create: `cloud/sibilia/sibilia_core.py`
- Create: `cloud/sibilia/test_sibilia_core.py`
- Create: `cloud/sibilia/requirements-dev.txt`

**Interfaces:**
- Produces, for Task 6:
  - `BUILT_IN = "sibilia"`, `SAMPLE_RATE = 24000` and `MAX_TEXT_CHARS = 2000`
  - `max_frames(text: str) -> int`
  - `valid_voice_name(name: str) -> bool`
  - `list_voices(voices_dir: Path) -> dict[str, dict]`
  - `resolve_voice(voice: str, voices_dir: Path) -> tuple[Path, str] | None`, which raises `UnknownVoice`
  - `conversation(text: str, reference: tuple[np.ndarray, str] | None) -> list[dict]`
  - `wav_bytes(samples: np.ndarray, sample_rate: int = SAMPLE_RATE) -> bytes`
  - `create_web_app(synthesize: Callable[[str, str], bytes], voices_dir: Path, refresh: Callable[[], None] = lambda: None) -> FastAPI`
  - `class UnknownVoice(ValueError)` and `class GenerationRambled(RuntimeError)`

- [ ] **Step 1: Create the dev requirements and a venv**

Create `cloud/sibilia/requirements-dev.txt`:

```
modal==1.3.2
fastapi[standard]==0.120.0
httpx==0.28.1
numpy==2.3.4
soundfile==0.13.1
pytest==8.4.2
```

Run:
```bash
cd /home/roncax/projects/podcaster-sibilia/cloud/sibilia && ~/.local/bin/uv venv -q -p 3.12 .venv && ~/.local/bin/uv pip install -q -p .venv -r requirements-dev.txt
```
Expected: installs without error. If a pinned version doesn't exist, pin the newest release older than two weeks and update the file. Add `cloud/sibilia/.venv/` to `.gitignore`.

- [ ] **Step 2: Write the failing tests**

Create `cloud/sibilia/test_sibilia_core.py`:

```python
import io
import math

import numpy as np
import pytest
import soundfile as sf
from fastapi.testclient import TestClient

import sibilia_core as core


def make_voice(tmp_path, name, transcript="Ciao, sono una voce di prova.", seconds=4.0):
    voices = tmp_path / "voices"
    voices.mkdir(exist_ok=True)
    sf.write(voices / f"{name}.wav", np.zeros(int(seconds * core.SAMPLE_RATE), dtype=np.float32), core.SAMPLE_RATE)
    (voices / f"{name}.txt").write_text(transcript, encoding="utf-8")
    return voices


def test_max_frames_formula():
    assert core.max_frames("a" * 300) == math.ceil(300 / 14 * 12.5 * 1.5) + 50
    assert core.max_frames("") == 50


@pytest.mark.parametrize("name,ok", [("anna", True), ("marco2", True), ("Anna", False), ("an-na", False), ("../x", False), ("", False)])
def test_valid_voice_name(name, ok):
    assert core.valid_voice_name(name) is ok


def test_list_voices_has_built_in_and_complete_references_only(tmp_path):
    voices = make_voice(tmp_path, "anna")
    (voices / "orphan.wav").write_bytes(b"x")  # no transcript: ignored
    (voices / "Bad.wav").write_bytes(b"x")
    (voices / "Bad.txt").write_text("x")      # invalid name: ignored
    assert set(core.list_voices(voices)) == {"sibilia", "sibilia-anna"}
    assert core.list_voices(tmp_path / "missing") == {"sibilia": {"kind": "built-in"}}


def test_resolve_voice(tmp_path):
    voices = make_voice(tmp_path, "anna", transcript="  Trascrizione.  ")
    assert core.resolve_voice("sibilia", voices) is None
    clip, transcript = core.resolve_voice("sibilia-anna", voices)
    assert clip.name == "anna.wav" and transcript == "Trascrizione."
    for bad in ("sibilia-marco", "sibilia-../anna", "sibilia-", "paola", "sibilia-Anna"):
        with pytest.raises(core.UnknownVoice):
            core.resolve_voice(bad, voices)


def test_conversation_shapes():
    assert core.conversation("Ciao.", None) == [{"role": "0", "content": [{"type": "text", "text": "Ciao."}]}]
    audio = np.zeros(10, dtype=np.float32)
    conv = core.conversation("Ciao.", (audio, "Riferimento."))
    assert conv[0]["content"][0] == {"type": "text", "text": "Riferimento."}
    assert conv[0]["content"][1]["type"] == "audio" and conv[0]["content"][1]["audio"] is audio
    assert conv[1] == {"role": "0", "content": [{"type": "text", "text": "Ciao."}]}


def test_wav_bytes_is_24khz_mono_16bit():
    data, sr = sf.read(io.BytesIO(core.wav_bytes(np.zeros(2400, dtype=np.float32))))
    info = sf.info(io.BytesIO(core.wav_bytes(np.zeros(2400, dtype=np.float32))))
    assert sr == 24000 and info.channels == 1 and info.subtype == "PCM_16" and len(data) == 2400


@pytest.fixture
def client(tmp_path):
    calls = []

    def synthesize(text, voice):
        calls.append((text, voice))
        if text == "boom":
            raise core.GenerationRambled("hit the frame cap")
        return core.wav_bytes(np.zeros(240, dtype=np.float32))

    app = core.create_web_app(synthesize, make_voice(tmp_path, "anna"))
    c = TestClient(app)
    c.calls = calls
    return c


def test_voices_endpoint(client):
    assert set(client.get("/voices").json()) == {"sibilia", "sibilia-anna"}


def test_synthesize_returns_wav_and_ignores_length_scale(client):
    r = client.post("/synthesize", json={"text": "Ciao a tutti.", "voice": "sibilia", "length_scale": 1.3})
    assert r.status_code == 200 and r.headers["content-type"] == "audio/wav" and r.content[:4] == b"RIFF"
    assert client.calls == [("Ciao a tutti.", "sibilia")]


def test_rejects_unknown_voice_before_synthesis(client):
    r = client.post("/synthesize", json={"text": "Ciao.", "voice": "sibilia-marco"})
    assert r.status_code == 400 and "sibilia-marco" in r.text
    assert client.calls == []


def test_rejects_path_like_voice_before_synthesis(client):
    r = client.post("/synthesize", json={"text": "Ciao.", "voice": "sibilia-../anna"})
    assert r.status_code == 400
    assert client.calls == []


def test_rejects_empty_and_overlong_text(client):
    assert client.post("/synthesize", json={"text": "  ", "voice": "sibilia"}).status_code == 400
    assert client.post("/synthesize", json={"text": "a" * (core.MAX_TEXT_CHARS + 1), "voice": "sibilia"}).status_code == 400
    assert client.calls == []


def test_generation_failure_is_500(client):
    r = client.post("/synthesize", json={"text": "boom", "voice": "sibilia"})
    assert r.status_code == 500
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `cd /home/roncax/projects/podcaster-sibilia/cloud/sibilia && .venv/bin/python -m pytest -q`
Expected: an error, `ModuleNotFoundError: No module named 'sibilia_core'`.

- [ ] **Step 4: Implement `sibilia_core.py`**

Create `cloud/sibilia/sibilia_core.py`:

```python
"""Pure logic for the Sibilia TTS service: no modal/torch imports, so it is testable locally."""

import io
import math
import re
from pathlib import Path
from typing import Callable

import numpy as np
import soundfile as sf

BUILT_IN = "sibilia"
SAMPLE_RATE = 24000
MAX_TEXT_CHARS = 2000
_NAME = re.compile(r"[a-z0-9]+")


class UnknownVoice(ValueError):
    pass


class GenerationRambled(RuntimeError):
    """The model hit the frame cap: almost always babble, so the caller should retry."""


def max_frames(text: str) -> int:
    """Mimi runs at 12.5 frames/s; Italian narration is ~14 chars/s; 1.5x headroom plus 4 s."""
    return math.ceil(len(text) / 14 * 12.5 * 1.5) + 50


def valid_voice_name(name: str) -> bool:
    return bool(_NAME.fullmatch(name))


def list_voices(voices_dir: Path) -> dict[str, dict]:
    voices = {BUILT_IN: {"kind": "built-in"}}
    if voices_dir.is_dir():
        for clip in sorted(voices_dir.glob("*.wav")):
            if valid_voice_name(clip.stem) and clip.with_suffix(".txt").is_file():
                voices[f"{BUILT_IN}-{clip.stem}"] = {"kind": "reference"}
    return voices


def resolve_voice(voice: str, voices_dir: Path) -> tuple[Path, str] | None:
    """None for the built-in voice, (clip, transcript) for a reference voice."""
    if voice == BUILT_IN:
        return None
    prefix = BUILT_IN + "-"
    name = voice[len(prefix):] if voice.startswith(prefix) else ""
    if not valid_voice_name(name):
        raise UnknownVoice(f"Unknown voice '{voice}'")
    clip, transcript = voices_dir / f"{name}.wav", voices_dir / f"{name}.txt"
    if not (clip.is_file() and transcript.is_file()):
        raise UnknownVoice(f"Unknown voice '{voice}'")
    return clip, transcript.read_text(encoding="utf-8").strip()


def conversation(text: str, reference: tuple[np.ndarray, str] | None) -> list[dict]:
    """CSM chat format: an optional context turn (transcript + 24 kHz audio) then the text to speak."""
    turns = []
    if reference is not None:
        audio, transcript = reference
        turns.append({"role": "0", "content": [{"type": "text", "text": transcript}, {"type": "audio", "audio": audio}]})
    turns.append({"role": "0", "content": [{"type": "text", "text": text}]})
    return turns


def wav_bytes(samples: np.ndarray, sample_rate: int = SAMPLE_RATE) -> bytes:
    buf = io.BytesIO()
    sf.write(buf, np.asarray(samples, dtype=np.float32).reshape(-1), sample_rate, format="WAV", subtype="PCM_16")
    return buf.getvalue()


def create_web_app(synthesize: Callable[[str, str], bytes], voices_dir: Path, refresh: Callable[[], None] = lambda: None):
    """Piper-shaped API: GET /voices, POST /synthesize {text, voice, length_scale?} -> audio/wav."""
    from fastapi import Body, FastAPI, HTTPException
    from fastapi.responses import Response

    app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)

    @app.get("/voices")
    def voices():
        refresh()
        return list_voices(voices_dir)

    @app.post("/synthesize")
    def synth(body: dict = Body(...)):
        text = str(body.get("text") or "").strip()
        voice = str(body.get("voice") or BUILT_IN)
        if not text:
            raise HTTPException(400, "Text is empty")
        if len(text) > MAX_TEXT_CHARS:
            raise HTTPException(400, f"Text is {len(text)} characters; the limit is {MAX_TEXT_CHARS}")
        refresh()
        try:
            resolve_voice(voice, voices_dir)
        except UnknownVoice as e:
            raise HTTPException(400, str(e))
        try:
            audio = synthesize(text, voice)
        except Exception as e:  # GenerationRambled, CUDA errors...: 500 so Podcaster retries
            raise HTTPException(500, f"Synthesis failed: {type(e).__name__}: {e}")
        return Response(content=audio, media_type="audio/wav")

    return app
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd /home/roncax/projects/podcaster-sibilia/cloud/sibilia && .venv/bin/python -m pytest -q`
Expected: all tests pass (17 with the parametrized cases).

- [ ] **Step 6: Commit**

```bash
cd /home/roncax/projects/podcaster-sibilia
git add cloud/sibilia/sibilia_core.py cloud/sibilia/test_sibilia_core.py cloud/sibilia/requirements-dev.txt .gitignore
git commit -m "Cloud TTS core: voices, frame cap, CSM conversation and the Piper-shaped web API

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01LbcoMupq1VXnfzdqroFmyp"
```

---

### Task 6: Modal app (`sibilia_app.py`): image, volumes, GPU class, web function, CLI

**Files:**
- Create: `cloud/sibilia/sibilia_app.py`

**Interfaces:**
- Consumes: everything `sibilia_core` exports (Task 5).
- Produces:
  - Modal app `podcaster-sibilia`.
  - Web function `api`, whose URL prints on deploy as `https://<workspace>--podcaster-sibilia-api.modal.run`.
  - CLI entry points `download`, `add_voice` and `smoke`.

- [ ] **Step 1: Write the Modal app**

Create `cloud/sibilia/sibilia_app.py`:

```python
"""Sibilia-TTS on Modal: a token-protected, Piper-shaped TTS API for Podcaster.

Deploy:      modal deploy sibilia_app.py           (run from cloud/sibilia)
Weights:     modal run sibilia_app.py::download
Add a voice: modal run sibilia_app.py::add_voice --name anna --wav clip.wav --text "trascrizione"
Smoke test:  modal run sibilia_app.py::smoke
"""

import time
from pathlib import Path

import modal

import sibilia_core as core

MODEL_ID = "DeepMount00/Sibilia-TTS"
MODELS_DIR = "/models"
VOICES_ROOT = "/voices"
VOICES_DIR = Path(VOICES_ROOT) / "voices"
L4_DOLLARS_PER_SECOND = 0.000222
TEST_SENTENCE = ("Buongiorno e benvenuti alla rassegna del mattino. Oggi parliamo di politica, di esteri "
                 "e di una storia curiosa che arriva da Torino.")

app = modal.App("podcaster-sibilia")
models = modal.Volume.from_name("sibilia-models", create_if_missing=True)
voices = modal.Volume.from_name("sibilia-voices", create_if_missing=True)

web_image = (
    modal.Image.debian_slim(python_version="3.12")
    .uv_pip_install("fastapi[standard]==0.120.0", "numpy==2.3.4", "soundfile==0.13.1")
    .add_local_python_source("sibilia_core")
)
gpu_image = (
    modal.Image.debian_slim(python_version="3.12")
    .uv_pip_install("torch==2.9.0", "torchaudio==2.9.0", "transformers==5.20.1", "accelerate==1.11.0",
                    "huggingface_hub==1.1.2", "numpy==2.3.4", "soundfile==0.13.1")
    .env({"HF_HOME": MODELS_DIR, "HF_HUB_OFFLINE": "1"})
    .add_local_python_source("sibilia_core")
)


@app.cls(image=gpu_image, gpu="L4", volumes={MODELS_DIR: models, VOICES_ROOT: voices},
         scaledown_window=300, max_containers=2, timeout=600)
class Sibilia:
    @modal.enter()
    def load(self):
        import torch
        from transformers import AutoProcessor, CsmForConditionalGeneration

        self.torch = torch
        self.processor = AutoProcessor.from_pretrained(MODEL_ID)
        self.model = CsmForConditionalGeneration.from_pretrained(MODEL_ID, torch_dtype=torch.bfloat16, device_map="cuda")
        # transformers 5 skips this tie (tie_word_embeddings=false) although the checkpoint relies on it
        self.model.backbone_model.embed_tokens.embed_audio_tokens.weight = self.model.depth_decoder.model.embed_tokens.weight
        self.model.eval()

    def _reference(self, voice: str):
        resolved = core.resolve_voice(voice, VOICES_DIR)
        if resolved is None:
            return None
        import soundfile as sf
        import torchaudio.functional as F

        clip, transcript = resolved
        data, sr = sf.read(clip, dtype="float32", always_2d=True)
        mono = self.torch.from_numpy(data.mean(axis=1))
        if sr != core.SAMPLE_RATE:
            mono = F.resample(mono, sr, core.SAMPLE_RATE)
        return mono.numpy(), transcript

    @modal.method()
    def synthesize(self, text: str, voice: str) -> bytes:
        voices.reload()
        # BatchFeature.to casts only floating tensors (reference audio) to bf16; token ids stay integers
        inputs = self.processor.apply_chat_template(core.conversation(text, self._reference(voice)),
                                                    tokenize=True, return_dict=True).to(device="cuda", dtype=self.torch.bfloat16)
        cap = core.max_frames(text)
        with self.torch.inference_mode():
            audio = self.model.generate(**inputs, output_audio=True, max_new_tokens=cap)
        samples = audio[0].float().cpu().numpy()
        if len(samples) >= cap * core.SAMPLE_RATE / 12.5 * 0.98:
            raise core.GenerationRambled(f"Reached the {cap}-frame cap for {len(text)} characters")
        return core.wav_bytes(samples)


@app.function(image=web_image, volumes={VOICES_ROOT: voices})
@modal.asgi_app(requires_proxy_auth=True)
def api():
    return core.create_web_app(lambda text, voice: Sibilia().synthesize.remote(text, voice), VOICES_DIR, refresh=voices.reload)


@app.function(image=gpu_image.env({"HF_HUB_OFFLINE": "0"}), volumes={MODELS_DIR: models}, timeout=1800)
def download_weights():
    from huggingface_hub import snapshot_download

    snapshot_download(MODEL_ID)
    models.commit()


@app.local_entrypoint()
def download():
    download_weights.remote()
    print("Weights stored in the sibilia-models volume")


@app.function(image=web_image, volumes={VOICES_ROOT: voices})
def store_voice(name: str, wav: bytes, transcript: str):
    VOICES_DIR.mkdir(parents=True, exist_ok=True)
    (VOICES_DIR / f"{name}.wav").write_bytes(wav)
    (VOICES_DIR / f"{name}.txt").write_text(transcript.strip(), encoding="utf-8")
    voices.commit()


@app.local_entrypoint()
def add_voice(name: str, wav: str, text: str):
    import soundfile as sf

    if not core.valid_voice_name(name):
        raise SystemExit("Voice names must match [a-z0-9]+")
    info = sf.info(wav)
    if not 3 <= info.duration <= 20:
        raise SystemExit(f"Reference clip must be 3-20 s long; {wav} is {info.duration:.1f} s")
    if not text.strip():
        raise SystemExit("--text (the clip's transcript) is required")
    store_voice.remote(name, Path(wav).read_bytes(), text)
    out = Path(f"{name}-test.wav")
    out.write_bytes(Sibilia().synthesize.remote(TEST_SENTENCE, f"{core.BUILT_IN}-{name}"))
    print(f"Stored voice sibilia-{name}; listen to {out} before using it in a show")


@app.local_entrypoint()
def smoke():
    import io

    import soundfile as sf

    for voice in core.list_voices(Path("/nonexistent")) | {v: {} for v in _remote_voices()}:
        started = time.time()
        wav = Sibilia().synthesize.remote(TEST_SENTENCE, voice)
        elapsed = time.time() - started
        seconds = sf.info(io.BytesIO(wav)).duration
        Path(f"smoke-{voice}.wav").write_bytes(wav)
        monthly = elapsed / seconds * 20 * 60 * 30 * L4_DOLLARS_PER_SECOND
        print(f"{voice}: {seconds:.1f}s audio in {elapsed:.1f}s wall ({elapsed / seconds:.2f}x real time, "
              f"includes any cold start) -> ~${monthly:.2f}/month for 20 min/day")


@app.function(image=web_image, volumes={VOICES_ROOT: voices})
def list_remote_voices() -> list[str]:
    return list(core.list_voices(VOICES_DIR))


def _remote_voices() -> list[str]:
    return list_remote_voices.remote()
```

- [ ] **Step 2: Static checks (no Modal account needed)**

Run:
```bash
cd /home/roncax/projects/podcaster-sibilia/cloud/sibilia && .venv/bin/python -c "import ast,sys; ast.parse(open('sibilia_app.py').read()); print('ok')" && .venv/bin/python -c "import sibilia_app; print(sorted(f for f in sibilia_app.app.registered_functions))"
```
Expected: `ok`, then a list containing `api`, `download_weights`, `list_remote_voices`, `store_voice` and `Sibilia.*`. Importing builds definitions locally without contacting Modal; if the import needs credentials, the AST check alone is acceptable here.

Run: `.venv/bin/python -m pytest -q`
Expected: still passes. Task 5's tests are untouched.

- [ ] **Step 3: Commit**

```bash
cd /home/roncax/projects/podcaster-sibilia
git add cloud/sibilia/sibilia_app.py
git commit -m "Modal app for Sibilia-TTS: L4 worker, token-protected API, weights/voice/smoke commands

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01LbcoMupq1VXnfzdqroFmyp"
```

---

### Task 7: Documentation

**Files:**
- Modify: `README.md` (add a section after the slow-CPU TTS note around line 121, and add one line to the env-var list if the README has one)
- Modify: `.env.example` (only if it exists: `ls .env.example`)

- [ ] **Step 1: Add the README section**

Insert after the paragraph that starts `On a slow CPU (e.g. a Celeron NAS)`:

````markdown
### Cloud TTS (Sibilia on Modal)

Shows can use **Sibilia-TTS** (an Italian Sesame CSM fine-tune) running on a serverless L4 GPU at [Modal](https://modal.com) instead of Piper. Choose a `sibilia` voice in the show form ("Sibilia (cloud GPU)" group); other shows keep using Piper.

Setup, once:

1. `cd cloud/sibilia && uv venv -p 3.12 .venv && uv pip install -p .venv -r requirements-dev.txt`
2. `.venv/bin/modal token new`, then `.venv/bin/modal deploy sibilia_app.py` (note the printed `…--podcaster-sibilia-api.modal.run` URL) and `.venv/bin/modal run sibilia_app.py::download`
3. Create a proxy auth token at https://modal.com/settings/proxy-auth-tokens and set in `.env`:
   `PODCASTER_SIBILIA_URL=<the api URL>`, `PODCASTER_SIBILIA_KEY=wk-…`, `PODCASTER_SIBILIA_SECRET=ws-…`
4. `.venv/bin/modal run sibilia_app.py::smoke` prints speed and the projected monthly cost.

Voices: `sibilia` is the model's built-in voice (stable across chunks). Add reference voices with
`modal run sibilia_app.py::add_voice --name anna --wav clip.wav --text "exact transcript"` (3–20 s of clean speech); it saves `anna-test.wav` so you can judge the imitation before selecting `sibilia-anna` in a show.

Notes: the show's speed (`length_scale`) is ignored for Sibilia voices; chunks are 300 characters (`podcaster.tts.sibilia.chunk-chars`); the endpoint rejects requests without the token before starting any container; at most 2 GPU containers run and each stops 5 minutes after its last request.
````

- [ ] **Step 2: `.env.example`**

If `.env.example` exists, append:

```
# Sibilia cloud TTS on Modal (optional; see README "Cloud TTS")
PODCASTER_SIBILIA_URL=
PODCASTER_SIBILIA_KEY=
PODCASTER_SIBILIA_SECRET=
```

Also check `docker-compose.yml`. If the app service lists env vars explicitly instead of using `env_file`, add the three `PODCASTER_SIBILIA_*` variables there as `${PODCASTER_SIBILIA_URL:-}` and so on. Check `deploy/synology/` for a compose file that needs the same.

- [ ] **Step 3: Full verification**

Run: `cd /home/roncax/projects/podcaster-sibilia && JAVA_HOME=$HOME/.jdks/temurin-25 ./mvnw -q test && (cd cloud/sibilia && .venv/bin/python -m pytest -q)`
Expected: both suites pass.

- [ ] **Step 4: Commit**

```bash
git add README.md .env.example docker-compose.yml deploy 2>/dev/null; git commit -m "Docs: Sibilia cloud TTS setup, voices and limits

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01LbcoMupq1VXnfzdqroFmyp"
```

---

### Task 8: Live rollout (needs the user)

This task needs the user's Modal account and NAS sudo password. **Never print or commit tokens.**

- [ ] **Step 1: Modal login (user)**

Ask the user to run `! cd /home/roncax/projects/podcaster-sibilia/cloud/sibilia && .venv/bin/modal token new` (it opens a browser).

- [ ] **Step 2: Deploy, download weights, smoke test**

```bash
cd /home/roncax/projects/podcaster-sibilia/cloud/sibilia
.venv/bin/modal deploy sibilia_app.py
.venv/bin/modal run sibilia_app.py::download
.venv/bin/modal run sibilia_app.py::smoke
```

Expected:
- The deploy prints the `api` URL with a 🔑 marker.
- `smoke` prints a line for `sibilia` with the real-time factor and the monthly estimate.
- Also run `smoke` a second time while the container is warm, to separate cold-start time from synthesis speed.

**Check:** call the URL without a token, e.g. `curl -s -o /dev/null -w '%{http_code}' <url>/voices`. Expected: `401`.

**Stop and report to the user** if the warm real-time factor is above ~3× or the monthly estimate is above ~$30. Speed fixes such as a static KV cache or `torch.compile`, or a different GPU, would be a new decision.

- [ ] **Step 3: NAS env (user creates the token; values never echoed)**

The user creates a proxy auth token in the Modal dashboard. Then either the user edits `/volume1/docker/podcaster/.env` on the NAS, or the implementer appends the three lines over `ssh nas` using values the user pastes into a local file that is read without echoing. Check that `.env` contains three `PODCASTER_SIBILIA_` keys using `grep -c`, not by printing them.

- [ ] **Step 4: PR**

```bash
cd /home/roncax/projects/podcaster-sibilia && git push -u origin feature/sibilia-cloud-tts
gh pr create --title "Sibilia cloud TTS on Modal as a per-show voice" --body "<summary of the tasks, the smoke-test numbers from Step 2, and the test plan>

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_01LbcoMupq1VXnfzdqroFmyp"
```

- [ ] **Step 5: After merge (user runs the sudo part)**

The user runs:
```
! ssh -t nas 'cd /volume1/docker/podcaster && sudo /usr/local/bin/docker compose pull && sudo /usr/local/bin/docker compose up -d'
```

Then verify from WSL:
- `curl -s http://192.168.1.53:8090/q/health` reports `UP`.
- The admin Settings page shows `Sibilia  Connected · …modal.run`.

The user then switches "Rassegna del mattino" to `sibilia`, presses **Run now**, and listens to the episode.
