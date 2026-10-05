# Podcaster Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A self-hosted Quarkus service that ingests news per Show (RSS + pluggable connectors), writes a ~20-minute single-narrator script with a selectable LLM, synthesizes it with Piper TTS, and publishes it as a private podcast feed.

**Architecture:** Modular monolith (`org.roncax.podcaster.*`), PostgreSQL + Flyway, Panache entities. A Run is a persisted, resumable stage machine (INGEST → SELECT → SCRIPT → TTS → PUBLISH) executed on a bounded worker pool; Quarkus Scheduler triggers runs per Show cron. LLMs are quarkus-langchain4j named-model "slots" looked up by name; Piper runs as a sidecar container over HTTP; ffmpeg encodes MP3.

**Tech Stack:** Java 25, Quarkus 3.40.1 (REST + Jackson, Hibernate ORM Panache, Flyway, Scheduler, Qute, SmallRye OpenAPI/Health, Hibernate Validator), quarkus-langchain4j (openai, anthropic, ai-gemini, ollama), Rome 2.1.0, jsoup 1.23.2, WireMock 3.13.2 (tests), Piper TTS 1.8.0 (`piper-tts[http]`), ffmpeg, Docker Compose.

**Spec:** `docs/superpowers/specs/2026-10-06-podcaster-design.md`

## Global Constraints

- `maven.compiler.release` = 25; Quarkus platform `3.40.1`; langchain4j extensions come from the platform `quarkus-langchain4j-bom` (no explicit versions).
- Base package `org.roncax.podcaster`; one sub-package per module (`domain`, `util`, `http`, `ingestion`, `extraction`, `llm`, `generation`, `tts`, `publishing`, `notify`, `runs`, `api`, `admin`).
- Secrets (LLM API keys, Telegram token, admin API key) come only from env/config, never from the DB.
- LLM slots: `gpt` (provider `openai`), `claude` (`anthropic`), `gemini` (`ai-gemini`), `local` (`ollama`); each enabled at runtime via `enable-integration` (`*_ENABLED` env, default `false`).
- Cron expressions are 5-field Unix syntax (`quarkus.scheduler.cron-type=unix`).
- Defaults (all in `podcaster.*` config): target duration 20 min; default wpm 150; `minItems` 3; `retainEpisodes` 30; first-run window 24h; max ranker candidates 80; intro/outro reserve 200 words; segment words min 250 / max 900; max source chars per segment 12000; TTS chunk ≤ 500 chars; pauses 400 ms (chunk) / 1200 ms (segment); MP3 mono 64 kbps; run workers 2; TTS parallelism 2; calibration EMA α = 0.3.
- Piper API (piper-tts 1.8.0): `POST /synthesize` JSON `{"text","voice","length_scale"}` → `audio/wav`; `GET /voices` → JSON object keyed by voice id.
- Admin/API auth: header `X-API-Key` or cookie `podcaster_key` equal to `podcaster.api-key`; feeds `/feeds/**` and media `/media/**` are unauthenticated (LAN/VPN only).
- No automated test may call a real LLM, Piper, Telegram or the public internet: use `FakeChatModel`/`FakeChatModelRegistry` and WireMock.
- Every `@QuarkusTest` class is annotated `@WithTestResource(WireMockResource.class)` (same resource set everywhere → one app boot shared by all test classes, except classes with their own `@TestProfile`).

## Review Focus

1. **Reasoning models wrap output** in `<think>…</think>` blocks or ```json fences (Ollama qwen3, DeepSeek) → JSON parsing and segment text must strip them, not fail or read them aloud. Test: Task 8 `LlmTextTest`.
2. **Feed items without `pubDate`** → must not be dropped by the `since` filter at ingest, and must be selected using `fetchedAt`. Test: Task 4 `RssSourceConnectorTest.itemsWithoutDateAreKept`, Task 14 `SelectStageTest.usesFetchedAtWhenNoPublishDate`.
3. **First run of a busy Show** (hundreds of fresh items) → ranker prompt must be capped to the newest `maxCandidates` items. Test: Task 14 `SelectStageTest.capsCandidates`.
4. **App restart mid-run** → the stuck `RUNNING` run becomes `FAILED` ("Interrupted by restart") and retry resumes at its stage. Test: Task 15 `RunRecoveryTest`.
5. **Piper answers 200 with a non-WAV body** (HTML error page, empty body) → TTS stage fails with a clear message instead of producing a corrupt MP3. Test: Task 11 `PiperHttpTtsEngineTest.rejectsNonWavBody`.

---

## File Structure

```
pom.xml                                              (modify)
docker-compose.yml, .env.example, .dockerignore, README.md
docker/podcaster/Dockerfile                          multi-stage build, JRE + ffmpeg
docker/piper/Dockerfile, docker/piper/entrypoint.sh
src/main/resources/application.yml                   all config incl. LLM slots
src/main/resources/db/migration/V1__schema.sql
src/main/resources/templates/AdminResource/*.html    Qute admin UI
src/main/java/org/roncax/podcaster/
  config/PodcasterConfig.java                        @ConfigMapping(prefix="podcaster")
  domain/  Show, Source, Item, Run, Episode, VoiceCalibration (entities)
           RunStage, RunStatus, RunTrigger (enums)
           Cluster, Selection, OutlineSegment, Outline (JSON records)
  util/    Retries, RetryableException, Exceptions, JacksonConfig
  http/    HttpFetcher, FetchException
  ingestion/ SourceConnector, SourceConfig, RawItem, RssSourceConnector,
             ConnectorRegistry, ContentHasher, IngestionService, IngestionReport
  extraction/ ContentExtractor, DefaultContentExtractor, AnsaContentExtractor,
              ContentExtractionService
  llm/     ChatModelRegistry, UnknownModelException, LlmText, JsonChat, GenerationException
  generation/ Prompts, StoryRanker, OutlinePlanner, ScriptWriter, Script, Framing,
              TtsTextNormalizer, ScriptChunker, TtsChunk
  tts/     TtsEngine, VoiceConfig, PiperHttpTtsEngine, Wav, AudioAssembler,
           AssembledAudio, Mp3Tags, VoiceCalibrationService
  publishing/ AudioStorage, LocalAudioStorage, MediaRoutes, PodcastFeedRenderer,
              FeedResource, RetentionService
  notify/  Notifier, TelegramNotifier
  runs/    Stage, StageResult, IngestStage, SelectStage, ScriptStage, TtsStage,
           PublishStage, RunOrchestrator, RunLauncher, RunAlreadyActiveException,
           RunRecovery, ShowScheduler, CronValidator
  api/     ApiKeyFilter, ApiExceptionMappers, InvalidRequestException, ShowRequest,
           SourceRequest, SourceTestResult, ShowService, ShowResource, SourceResource,
           RunResource, EpisodeResource, MetaResource
  admin/   AdminResource
src/test/java/org/roncax/podcaster/
  support/ WireMockResource, InjectWireMock, TestAudio, TestData, FakeChatModel,
           FakeChatModelRegistry, TestConfigs
  ...      one test class per production unit (named in each task)
src/test/resources/fixtures/  ansa-feed.xml, ilpost-italia-feed.xml,
                              ansa-article.html, ilpost-article.html (already committed)
```

---

### Task 0: Toolchain prerequisites

No code. Makes `./mvnw` build Java 25 and makes Docker/ffmpeg available for tests.

- [ ] **Step 1: Install Temurin JDK 25 (user-level)**

```bash
mkdir -p ~/.jdks && cd ~/.jdks
curl -L -o temurin25.tar.gz "https://api.adoptium.net/v3/binary/latest/25/ga/linux/x64/jdk/hotspot/normal/eclipse"
tar xzf temurin25.tar.gz && rm temurin25.tar.gz
ln -sfn "$(ls -d ~/.jdks/jdk-25* | head -1)" ~/.jdks/temurin-25
grep -q 'temurin-25' ~/.bashrc || printf '\nexport JAVA_HOME="$HOME/.jdks/temurin-25"\nexport PATH="$JAVA_HOME/bin:$PATH"\n' >> ~/.bashrc
```

- [ ] **Step 2: Ask the user to install ffmpeg and enable Docker**

The user runs (needs sudo): `! sudo apt-get update && sudo apt-get install -y ffmpeg`, and enables Docker Desktop's WSL integration for this distro (or installs Docker Engine). Wait for confirmation.

- [ ] **Step 3: Verify**

Run (new shell so `.bashrc` applies): `bash -lc 'java -version && ffmpeg -version | head -1 && docker info --format "{{.ServerVersion}}"'`
Expected: `openjdk version "25...`, an `ffmpeg version` line, and a Docker server version. Do not continue until all three succeed. All later `./mvnw` commands are run via `bash -lc '...'` (or in a shell with `JAVA_HOME` set).

---

### Task 1: Project foundation (dependencies, config, cleanup)

**Files:**
- Modify: `pom.xml`
- Replace: `src/main/resources/application.yml`
- Create: `src/main/java/org/roncax/podcaster/config/PodcasterConfig.java`
- Create: `src/main/java/org/roncax/podcaster/util/JacksonConfig.java`
- Create: `src/test/java/org/roncax/podcaster/support/WireMockResource.java`, `InjectWireMock.java`, `TestAudio.java`
- Create: `src/test/java/org/roncax/podcaster/HealthTest.java`
- Delete: `src/main/java/org/roncax/{Bot,GreetingConfig,GreetingResource,MyEntity}.java`, `src/main/resources/import.sql`, `src/test/java/org/roncax/{GreetingResourceTest,GreetingResourceIT}.java`, `easy-rag-catalog/`

**Interfaces:**
- Produces: `PodcasterConfig` (all `podcaster.*` settings, see code); `WireMockResource` (starts one WireMock server per test JVM, exposes it via `@InjectWireMock WireMockServer`, sets `podcaster.tts.piper-url` and `podcaster.telegram.api-url` to it, and `installDefaults(WireMockServer)` registers default stubs for `GET /voices`, `POST /synthesize`, `POST /bottest-token/sendMessage`); `TestAudio.sineWav(double seconds)` → `byte[]` 16-bit mono 22050 Hz WAV.

- [ ] **Step 1: Delete scaffold code**

```bash
git rm -q -r easy-rag-catalog src/main/java/org/roncax/Bot.java src/main/java/org/roncax/GreetingConfig.java \
  src/main/java/org/roncax/GreetingResource.java src/main/java/org/roncax/MyEntity.java \
  src/main/resources/import.sql src/test/java/org/roncax/GreetingResourceTest.java src/test/java/org/roncax/GreetingResourceIT.java
```

- [ ] **Step 2: Replace the `<dependencies>` block in `pom.xml`**

Keep `<properties>`, `<dependencyManagement>`, `<build>`, `<profiles>` unchanged. Add `<jsoup.version>1.23.2</jsoup.version>`, `<rome.version>2.1.0</rome.version>`, `<wiremock.version>3.13.2</wiremock.version>` to `<properties>`. New dependencies block:

```xml
    <dependencies>
        <dependency><groupId>io.quarkus</groupId><artifactId>quarkus-rest-jackson</artifactId></dependency>
        <dependency><groupId>io.quarkus</groupId><artifactId>quarkus-rest-qute</artifactId></dependency>
        <dependency><groupId>io.quarkus</groupId><artifactId>quarkus-hibernate-orm-panache</artifactId></dependency>
        <dependency><groupId>io.quarkus</groupId><artifactId>quarkus-hibernate-validator</artifactId></dependency>
        <dependency><groupId>io.quarkus</groupId><artifactId>quarkus-jdbc-postgresql</artifactId></dependency>
        <dependency><groupId>io.quarkus</groupId><artifactId>quarkus-flyway</artifactId></dependency>
        <dependency><groupId>io.quarkus</groupId><artifactId>quarkus-scheduler</artifactId></dependency>
        <dependency><groupId>io.quarkus</groupId><artifactId>quarkus-smallrye-openapi</artifactId></dependency>
        <dependency><groupId>io.quarkus</groupId><artifactId>quarkus-smallrye-health</artifactId></dependency>
        <dependency><groupId>io.quarkus</groupId><artifactId>quarkus-config-yaml</artifactId></dependency>
        <dependency><groupId>io.quarkus</groupId><artifactId>quarkus-arc</artifactId></dependency>
        <dependency><groupId>io.quarkiverse.langchain4j</groupId><artifactId>quarkus-langchain4j-openai</artifactId></dependency>
        <dependency><groupId>io.quarkiverse.langchain4j</groupId><artifactId>quarkus-langchain4j-anthropic</artifactId></dependency>
        <dependency><groupId>io.quarkiverse.langchain4j</groupId><artifactId>quarkus-langchain4j-ai-gemini</artifactId></dependency>
        <dependency><groupId>io.quarkiverse.langchain4j</groupId><artifactId>quarkus-langchain4j-ollama</artifactId></dependency>
        <dependency><groupId>org.jsoup</groupId><artifactId>jsoup</artifactId><version>${jsoup.version}</version></dependency>
        <dependency><groupId>com.rometools</groupId><artifactId>rome</artifactId><version>${rome.version}</version></dependency>

        <dependency><groupId>io.quarkus</groupId><artifactId>quarkus-junit</artifactId><scope>test</scope></dependency>
        <dependency><groupId>io.rest-assured</groupId><artifactId>rest-assured</artifactId><scope>test</scope></dependency>
        <dependency><groupId>org.wiremock</groupId><artifactId>wiremock-standalone</artifactId><version>${wiremock.version}</version><scope>test</scope></dependency>
    </dependencies>
```

- [ ] **Step 3: Write `application.yml`**

```yaml
podcaster:
  api-key: ${PODCASTER_API_KEY:change-me}
  base-url: ${PODCASTER_BASE_URL:http://localhost:8080}
  storage:
    root: ${PODCASTER_AUDIO_DIR:/data/audio}
    work-dir: ${PODCASTER_WORK_DIR:/data/work}
  http:
    user-agent: "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Safari/537.36"
    timeout: 20s
    politeness-delay: 1s
    attempts: 3
    retry-delay: 1s
  selection:
    max-candidates: 80
    first-run-window: 24h
  script:
    intro-outro-words: 200
    min-segment-words: 250
    max-segment-words: 900
    max-source-chars: 12000
  tts:
    piper-url: ${PIPER_URL:http://piper:5000}
    default-wpm: 150
    chunk-chars: 500
    parallelism: 2
    chunk-pause: 400ms
    segment-pause: 1200ms
    ffmpeg: ffmpeg
    bitrate: 64k
    attempts: 3
    retry-delay: 2s
  runs:
    workers: 2
  telegram:
    bot-token: ${TELEGRAM_BOT_TOKEN:}
    chat-id: ${TELEGRAM_CHAT_ID:}
    api-url: https://api.telegram.org

quarkus:
  datasource:
    db-kind: postgresql
    username: ${DB_USER:podcaster}
    password: ${DB_PASSWORD:podcaster}
    jdbc:
      url: ${DB_URL:jdbc:postgresql://postgres:5432/podcaster}
  flyway:
    migrate-at-start: true
  scheduler:
    cron-type: unix
    start-mode: forced
  swagger-ui:
    always-include: true
  arc:
    unremovable-types: dev.langchain4j.model.chat.ChatModel
  langchain4j:
    devservices:
      enabled: false
    timeout: 180s
    # --- model slots: name -> provider (build time) ---
    gpt:
      chat-model:
        provider: openai
    claude:
      chat-model:
        provider: anthropic
    gemini:
      chat-model:
        provider: ai-gemini
    local:
      chat-model:
        provider: ollama
    # --- per-slot provider settings (runtime, env driven) ---
    openai:
      gpt:
        enable-integration: ${GPT_ENABLED:false}
        api-key: ${GPT_API_KEY:unset}
        base-url: ${GPT_BASE_URL:https://api.openai.com/v1/}
        chat-model:
          model-name: ${GPT_MODEL:gpt-5}
    anthropic:
      claude:
        enable-integration: ${CLAUDE_ENABLED:false}
        api-key: ${ANTHROPIC_API_KEY:unset}
        chat-model:
          model-name: ${CLAUDE_MODEL:claude-sonnet-5-5}
          max-tokens: 8192
    ai:
      gemini:
        gemini:
          enable-integration: ${GEMINI_ENABLED:false}
          api-key: ${GEMINI_API_KEY:unset}
          chat-model:
            model-id: ${GEMINI_MODEL:gemini-2.5-flash}
    ollama:
      local:
        enable-integration: ${LOCAL_ENABLED:false}
        base-url: ${OLLAMA_BASE_URL:http://ollama:11434}
        chat-model:
          model-id: "${LOCAL_MODEL:qwen3:14b}"
          num-predict: 4096

"%dev":
  podcaster:
    storage:
      root: target/dev-audio
      work-dir: target/dev-work
  quarkus:
    datasource:
      jdbc:
        url: ""   # empty -> Dev Services PostgreSQL

"%test":
  podcaster:
    api-key: test-key
    base-url: http://podcaster.test
    storage:
      root: target/test-audio
      work-dir: target/test-work
    http:
      politeness-delay: 0s
      retry-delay: 10ms
    tts:
      retry-delay: 10ms
      parallelism: 2
    telegram:
      bot-token: test-token
      chat-id: "42"
  quarkus:
    datasource:
      jdbc:
        url: ""   # empty -> Dev Services PostgreSQL (Testcontainers)
```

Note: if the build fails with an "unknown provider `ai-gemini`" message, use the provider id the error lists for the Gemini extension and update the `gemini` slot (and `ChatModelRegistry.PREFIXES` in Task 7).

- [ ] **Step 4: Write `PodcasterConfig`**

```java
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
```

- [ ] **Step 5: Write `JacksonConfig` (hide Panache's `persistent` property, ISO dates)**

```java
package org.roncax.podcaster.util;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.jackson.ObjectMapperCustomizer;
import jakarta.inject.Singleton;

@Singleton
public class JacksonConfig implements ObjectMapperCustomizer {

    abstract static class PanacheMixin {
        @JsonIgnore abstract boolean isPersistent();
    }

    @Override
    public void customize(ObjectMapper mapper) {
        mapper.addMixIn(PanacheEntityBase.class, PanacheMixin.class);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
```

- [ ] **Step 6: Write test support: `TestAudio`, `InjectWireMock`, `WireMockResource`**

```java
package org.roncax.podcaster.support;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class TestAudio {
    public static final int SAMPLE_RATE = 22050;

    private TestAudio() {}

    /** 16-bit mono PCM WAV with a 440 Hz tone. */
    public static byte[] sineWav(double seconds) {
        int samples = (int) Math.round(seconds * SAMPLE_RATE);
        ByteBuffer pcm = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < samples; i++) {
            pcm.putShort((short) (Math.sin(2 * Math.PI * 440 * i / SAMPLE_RATE) * 8000));
        }
        return wav(pcm.array(), SAMPLE_RATE, 1, 16);
    }

    public static byte[] wav(byte[] pcm, int sampleRate, int channels, int bits) {
        ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        int blockAlign = channels * bits / 8;
        h.put("RIFF".getBytes()).putInt(36 + pcm.length).put("WAVE".getBytes())
         .put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) channels)
         .putInt(sampleRate).putInt(sampleRate * blockAlign).putShort((short) blockAlign).putShort((short) bits)
         .put("data".getBytes()).putInt(pcm.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(h.array());
        out.writeBytes(pcm);
        return out.toByteArray();
    }
}
```

```java
package org.roncax.podcaster.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface InjectWireMock {}
```

```java
package org.roncax.podcaster.support;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.Map;

public class WireMockResource implements QuarkusTestResourceLifecycleManager {
    private WireMockServer server;

    @Override
    public Map<String, String> start() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
        installDefaults(server);
        return Map.of(
                "podcaster.tts.piper-url", server.baseUrl(),
                "podcaster.telegram.api-url", server.baseUrl());
    }

    public static void installDefaults(WireMockServer server) {
        server.resetAll();
        server.stubFor(get("/voices").willReturn(okJson("{\"it_IT-paola-medium\":{},\"en_US-lessac-medium\":{}}")));
        server.stubFor(post("/synthesize").willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "audio/wav").withBody(TestAudio.sineWav(0.5))));
        server.stubFor(post("/bottest-token/sendMessage").willReturn(okJson("{\"ok\":true}")));
    }

    @Override
    public void inject(TestInjector injector) {
        injector.injectIntoFields(server, new TestInjector.AnnotatedAndMatchesType(InjectWireMock.class, WireMockServer.class));
    }

    @Override
    public void stop() {
        if (server != null) server.stop();
    }
}
```

- [ ] **Step 7: Write the smoke test**

```java
package org.roncax.podcaster;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.support.WireMockResource;

@QuarkusTest
@WithTestResource(WireMockResource.class)
class HealthTest {
    @Test
    void appStartsAndIsHealthy() {
        given().get("/q/health").then().statusCode(200).body("status", is("UP"));
    }
}
```

- [ ] **Step 8: Run the build and test**

Run: `bash -lc './mvnw -q test -Dtest=HealthTest'`
Expected: PASS (Dev Services starts PostgreSQL; Flyway finds no migrations yet — that is fine). If the build reports an unknown langchain4j config key or provider, fix the key per the error text and re-run.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "Set up podcaster foundation: dependencies, config, test support

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01LbcoMupq1VXnfzdqroFmyp"
```

---

### Task 2: Schema and domain model

**Files:**
- Create: `src/main/resources/db/migration/V1__schema.sql`
- Create: `src/main/java/org/roncax/podcaster/domain/{Show,Source,Item,Run,Episode,VoiceCalibration,RunStage,RunStatus,RunTrigger,Cluster,Selection,OutlineSegment,Outline}.java`
- Create: `src/main/java/org/roncax/podcaster/util/Exceptions.java`
- Create: `src/test/java/org/roncax/podcaster/support/TestData.java`
- Test: `src/test/java/org/roncax/podcaster/domain/DomainPersistenceTest.java`

**Interfaces:**
- Produces: Panache entities with public fields (names below), `Long id` identity keys, FK columns as plain `Long` fields (`showId`, `sourceId`, `runId`, `usedInEpisodeId`).
  - `Show.findBySlug(String) → Optional<Show>`, `Show#effectiveRankerModel() → String`
  - `Item#effectiveDate() → Instant`
  - `Episode.findByRun(long runId) → Optional<Episode>`
  - `RunStage#next() → RunStage` (PUBLISH.next() throws)
  - JSON records: `Cluster(String headline, List<Long> itemIds, int importance)`, `Selection(List<Cluster> clusters)`, `OutlineSegment(String headline, List<Long> itemIds, int words)`, `Outline(int totalWords, List<OutlineSegment> segments)` with `Outline#itemIds() → List<Long>`
  - `Exceptions.isUniqueViolation(Throwable) → boolean`, `Exceptions.rootMessage(Throwable) → String`, `Exceptions.message(Throwable) → String` (own message, else root cause's; max 2000 chars)
  - `TestData.show(String slug) → Show` (persisted, writerModel `fake`, voice `it_IT-paola-medium`, language `it`, minItems 1), `TestData.source(long showId, String url) → Source` (rss), `TestData.item(long showId, long sourceId, String url, Instant publishedAt) → Item`, `TestData.cleanDb()`, `TestData.run(long showId, Instant since) → Run` (persisted, RUNNING, stage INGEST), `TestData.awaitRun(long runId) → Run` (polls up to 60 s until status ≠ RUNNING).

- [ ] **Step 1: Write the migration**

```sql
create table shows (
    id bigserial primary key,
    name varchar(200) not null,
    slug varchar(100) not null unique,
    description text,
    language varchar(20) not null,
    voice_id varchar(200) not null,
    length_scale double precision not null default 1.0,
    writer_model varchar(100) not null,
    ranker_model varchar(100),
    focus_prompt text,
    target_duration_minutes int not null default 20,
    min_items int not null default 3,
    cron varchar(100),
    enabled boolean not null default true,
    retain_episodes int not null default 30,
    feed_token varchar(100),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table sources (
    id bigserial primary key,
    show_id bigint not null references shows(id) on delete cascade,
    connector_type varchar(100) not null,
    config jsonb not null default '{}',
    fetch_full_text boolean not null default true,
    enabled boolean not null default true,
    last_fetched_at timestamptz,
    last_error text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table runs (
    id bigserial primary key,
    show_id bigint not null references shows(id) on delete cascade,
    trigger varchar(20) not null,
    stage varchar(20) not null,
    status varchar(20) not null,
    since timestamptz not null,
    attempt int not null default 1,
    error text,
    started_at timestamptz not null default now(),
    finished_at timestamptz
);
create unique index runs_one_active_per_show on runs(show_id) where status = 'RUNNING';
create index runs_show_started on runs(show_id, started_at desc);

create table episodes (
    id bigserial primary key,
    run_id bigint not null unique references runs(id) on delete cascade,
    show_id bigint not null references shows(id) on delete cascade,
    title varchar(300),
    description text,
    selection jsonb,
    outline jsonb,
    script_parts jsonb,
    script text,
    audio_path varchar(500),
    duration_seconds double precision,
    size_bytes bigint,
    published_at timestamptz,
    created_at timestamptz not null default now()
);
create index episodes_show_published on episodes(show_id, published_at desc);

create table items (
    id bigserial primary key,
    show_id bigint not null references shows(id) on delete cascade,
    source_id bigint not null references sources(id) on delete cascade,
    url varchar(2000) not null,
    content_hash varchar(64) not null,
    title varchar(1000) not null,
    author varchar(300),
    published_at timestamptz,
    fetched_at timestamptz not null,
    summary text,
    full_text text,
    used_in_episode_id bigint references episodes(id) on delete set null,
    unique (show_id, url)
);
create index items_show_hash on items(show_id, content_hash);
create index items_show_unused on items(show_id) where used_in_episode_id is null;

create table voice_calibrations (
    id bigserial primary key,
    voice_id varchar(200) not null,
    length_scale double precision not null,
    words_per_minute double precision not null,
    samples int not null default 0,
    unique (voice_id, length_scale)
);
```

- [ ] **Step 2: Write the enums and JSON records**

```java
package org.roncax.podcaster.domain;

public enum RunStage {
    INGEST, SELECT, SCRIPT, TTS, PUBLISH;

    public RunStage next() {
        if (this == PUBLISH) throw new IllegalStateException("PUBLISH is the last stage");
        return values()[ordinal() + 1];
    }
}
```

```java
package org.roncax.podcaster.domain;

public enum RunStatus { RUNNING, DONE, FAILED, SKIPPED }
```

```java
package org.roncax.podcaster.domain;

public enum RunTrigger { SCHEDULED, MANUAL }
```

```java
package org.roncax.podcaster.domain;

import java.util.List;

public record Cluster(String headline, List<Long> itemIds, int importance) {}
```

```java
package org.roncax.podcaster.domain;

import java.util.List;

public record Selection(List<Cluster> clusters) {}
```

```java
package org.roncax.podcaster.domain;

import java.util.List;

public record OutlineSegment(String headline, List<Long> itemIds, int words) {}
```

```java
package org.roncax.podcaster.domain;

import java.util.List;

public record Outline(int totalWords, List<OutlineSegment> segments) {
    public List<Long> itemIds() {
        return segments.stream().flatMap(s -> s.itemIds().stream()).distinct().toList();
    }
}
```

- [ ] **Step 3: Write the entities**

```java
package org.roncax.podcaster.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Optional;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "shows")
public class Show extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public String name;
    public String slug;
    public String description;
    public String language;
    public String voiceId;
    public double lengthScale = 1.0;
    public String writerModel;
    public String rankerModel;
    public String focusPrompt;
    public int targetDurationMinutes = 20;
    public int minItems = 3;
    public String cron;
    public boolean enabled = true;
    public int retainEpisodes = 30;
    public String feedToken;
    @CreationTimestamp public Instant createdAt;
    @UpdateTimestamp public Instant updatedAt;

    public static Optional<Show> findBySlug(String slug) {
        return find("slug", slug).firstResultOptional();
    }

    public String effectiveRankerModel() {
        return rankerModel == null || rankerModel.isBlank() ? writerModel : rankerModel;
    }
}
```

```java
package org.roncax.podcaster.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "sources")
public class Source extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Long showId;
    public String connectorType;
    @JdbcTypeCode(SqlTypes.JSON) public Map<String, String> config = new HashMap<>();
    public boolean fetchFullText = true;
    public boolean enabled = true;
    public Instant lastFetchedAt;
    public String lastError;
    @CreationTimestamp public Instant createdAt;
    @UpdateTimestamp public Instant updatedAt;

    public String label() {
        return connectorType + " " + config.getOrDefault("url", "#" + id);
    }
}
```

```java
package org.roncax.podcaster.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "items")
public class Item extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Long showId;
    public Long sourceId;
    public String url;
    public String contentHash;
    public String title;
    public String author;
    public Instant publishedAt;
    public Instant fetchedAt;
    public String summary;
    public String fullText;
    public Long usedInEpisodeId;

    public Instant effectiveDate() {
        return publishedAt != null ? publishedAt : fetchedAt;
    }

    /** Best available body text: full text, else summary, else empty. */
    public String bestText() {
        if (fullText != null && !fullText.isBlank()) return fullText;
        return summary == null ? "" : summary;
    }
}
```

```java
package org.roncax.podcaster.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "runs")
public class Run extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Long showId;
    @Enumerated(EnumType.STRING) public RunTrigger trigger;
    @Enumerated(EnumType.STRING) public RunStage stage = RunStage.INGEST;
    @Enumerated(EnumType.STRING) public RunStatus status = RunStatus.RUNNING;
    public Instant since;
    public int attempt = 1;
    public String error;
    public Instant startedAt = Instant.now();
    public Instant finishedAt;
}
```

```java
package org.roncax.podcaster.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "episodes")
public class Episode extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Long runId;
    public Long showId;
    public String title;
    public String description;
    @JdbcTypeCode(SqlTypes.JSON) public Selection selection;
    @JdbcTypeCode(SqlTypes.JSON) public Outline outline;
    @JdbcTypeCode(SqlTypes.JSON) public List<String> scriptParts;
    public String script;
    public String audioPath;
    public Double durationSeconds;
    public Long sizeBytes;
    public Instant publishedAt;
    @CreationTimestamp public Instant createdAt;

    public static Optional<Episode> findByRun(long runId) {
        return find("runId", runId).firstResultOptional();
    }
}
```

```java
package org.roncax.podcaster.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

@Entity
@Table(name = "voice_calibrations")
public class VoiceCalibration extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public String voiceId;
    public double lengthScale;
    public double wordsPerMinute;
    public int samples;
}
```

- [ ] **Step 4: Write `Exceptions`**

```java
package org.roncax.podcaster.util;

import java.sql.SQLException;

public final class Exceptions {
    private Exceptions() {}

    public static boolean isUniqueViolation(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException sql && "23505".equals(sql.getSQLState())) return true;
            if (c.getCause() == c) break;
        }
        return false;
    }

    /** The exception's own message if it has one (keeps context such as "Model did not return valid JSON"), else the root cause's. */
    public static String message(Throwable t) {
        String msg = t.getMessage() != null && !t.getMessage().isBlank() ? t.getMessage() : rootMessage(t);
        return msg.length() > 2000 ? msg.substring(0, 2000) : msg;
    }

    public static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String msg = root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
        return msg.length() > 2000 ? msg.substring(0, 2000) : msg;
    }
}
```

- [ ] **Step 5: Write `TestData`**

```java
package org.roncax.podcaster.support;

import io.quarkus.narayana.jta.QuarkusTransaction;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.roncax.podcaster.domain.*;

public final class TestData {
    private TestData() {}

    public static void cleanDb() {
        QuarkusTransaction.requiringNew().run(() -> {
            Item.deleteAll();
            Episode.deleteAll();
            Run.deleteAll();
            Source.deleteAll();
            Show.deleteAll();
            VoiceCalibration.deleteAll();
        });
    }

    public static Show show(String slug) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Show s = new Show();
            s.name = "Show " + slug;
            s.slug = slug;
            s.language = "it";
            s.voiceId = "it_IT-paola-medium";
            s.writerModel = "fake";
            s.minItems = 1;
            s.targetDurationMinutes = 5;
            s.persist();
            return s;
        });
    }

    public static Source source(long showId, String url) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Source s = new Source();
            s.showId = showId;
            s.connectorType = "rss";
            s.config = new java.util.HashMap<>(Map.of("url", url));
            s.persist();
            return s;
        });
    }

    public static Item item(long showId, long sourceId, String url, Instant publishedAt) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Item i = new Item();
            i.showId = showId;
            i.sourceId = sourceId;
            i.url = url;
            i.contentHash = Integer.toHexString(url.hashCode());
            i.title = "Title " + url;
            i.summary = "Summary of " + url;
            i.fullText = "Full text of " + url + ". It has several sentences. This is one more.";
            i.publishedAt = publishedAt;
            i.fetchedAt = Instant.now();
            i.persist();
            return i;
        });
    }

    public static Run run(long showId, Instant since) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Run r = new Run();
            r.showId = showId;
            r.trigger = RunTrigger.MANUAL;
            r.since = since;
            r.persist();
            return r;
        });
    }

    public static Run awaitRun(long runId) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        while (Instant.now().isBefore(deadline)) {
            Run run = QuarkusTransaction.requiringNew().call(() -> Run.<Run>findById(runId));
            if (run != null && run.status != RunStatus.RUNNING) return run;
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        throw new AssertionError("Run " + runId + " did not finish within 60s");
    }
}
```

- [ ] **Step 6: Write the failing persistence test**

```java
package org.roncax.podcaster.domain;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.support.TestData;
import org.roncax.podcaster.support.WireMockResource;
import org.roncax.podcaster.util.Exceptions;

@QuarkusTest
@WithTestResource(WireMockResource.class)
class DomainPersistenceTest {

    @BeforeEach
    void clean() { TestData.cleanDb(); }

    @Test
    void persistsJsonColumns() {
        Show show = TestData.show("json");
        Source source = TestData.source(show.id, "http://x/feed");
        long episodeId = QuarkusTransaction.requiringNew().call(() -> {
            Run run = new Run();
            run.showId = show.id;
            run.trigger = RunTrigger.MANUAL;
            run.since = Instant.now();
            run.persist();
            Episode e = new Episode();
            e.runId = run.id;
            e.showId = show.id;
            e.selection = new Selection(List.of(new Cluster("Headline", List.of(1L, 2L), 7)));
            e.outline = new Outline(500, List.of(new OutlineSegment("Headline", List.of(1L), 300)));
            e.scriptParts = List.of("intro", "body", "outro");
            e.persist();
            return e.id;
        });

        Episode loaded = QuarkusTransaction.requiringNew().call(() -> Episode.<Episode>findById(episodeId));
        assertEquals(7, loaded.selection.clusters().get(0).importance());
        assertEquals(List.of(1L), loaded.outline.itemIds());
        assertEquals(List.of("intro", "body", "outro"), loaded.scriptParts);
        Source reloaded = QuarkusTransaction.requiringNew().call(() -> Source.<Source>findById(source.id));
        assertEquals("http://x/feed", reloaded.config.get("url"));
    }

    @Test
    void onlyOneRunningRunPerShow() {
        Show show = TestData.show("lock");
        Runnable insertRunning = () -> QuarkusTransaction.requiringNew().run(() -> {
            Run run = new Run();
            run.showId = show.id;
            run.trigger = RunTrigger.MANUAL;
            run.since = Instant.now();
            run.persistAndFlush();
        });
        insertRunning.run();
        RuntimeException ex = assertThrows(RuntimeException.class, insertRunning::run);
        assertTrue(Exceptions.isUniqueViolation(ex), "expected unique violation, got " + ex);
    }

    @Test
    void stageOrder() {
        assertEquals(RunStage.SELECT, RunStage.INGEST.next());
        assertEquals(RunStage.PUBLISH, RunStage.TTS.next());
        assertThrows(IllegalStateException.class, RunStage.PUBLISH::next);
    }
}
```

- [ ] **Step 7: Run tests**

Run: `bash -lc './mvnw -q test -Dtest=DomainPersistenceTest'`
Expected: PASS (migration applies, JSON round-trips, partial unique index rejects the second RUNNING run).

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "Add database schema and domain entities

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01LbcoMupq1VXnfzdqroFmyp"
```

---

### Task 3: HTTP fetching with retries and politeness

**Files:**
- Create: `src/main/java/org/roncax/podcaster/util/Retries.java`, `util/RetryableException.java`
- Create: `src/main/java/org/roncax/podcaster/http/HttpFetcher.java`, `http/FetchException.java`
- Test: `src/test/java/org/roncax/podcaster/util/RetriesTest.java`, `src/test/java/org/roncax/podcaster/http/HttpFetcherTest.java`

**Interfaces:**
- Produces:
  - `Retries.withBackoff(int attempts, Duration initialDelay, Callable<T> action) → T` — retries only when the action throws `RetryableException`; delay doubles each attempt; after the last attempt rethrows the last `RetryableException` itself; any other exception propagates immediately.
  - `RetryableException(String message)` / `RetryableException(String message, Throwable cause)` (unchecked).
  - `HttpFetcher#get(String url) → byte[]` throws `FetchException` (checked) on 4xx or after retries are exhausted; retries 5xx, 429 and I/O errors; sends configured User-Agent; waits `politenessDelay` between requests to the same host.
  - `HttpFetcher(String userAgent, Duration timeout, Duration politenessDelay, int attempts, Duration retryDelay)` public constructor for unit tests; CDI uses `@Inject HttpFetcher(PodcasterConfig)`.

- [ ] **Step 1: Write the failing `RetriesTest`**

```java
package org.roncax.podcaster.util;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RetriesTest {

    @Test
    void retriesRetryableUntilSuccess() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String result = Retries.withBackoff(3, Duration.ofMillis(1), () -> {
            if (calls.incrementAndGet() < 3) throw new RetryableException("boom");
            return "ok";
        });
        assertEquals("ok", result);
        assertEquals(3, calls.get());
    }

    @Test
    void doesNotRetryOtherExceptions() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> Retries.withBackoff(3, Duration.ofMillis(1), () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("fatal");
        }));
        assertEquals(1, calls.get());
    }

    @Test
    void givesUpAfterAttempts() {
        AtomicInteger calls = new AtomicInteger();
        RetryableException ex = assertThrows(RetryableException.class, () -> Retries.withBackoff(2, Duration.ofMillis(1), () -> {
            calls.incrementAndGet();
            throw new RetryableException("still failing");
        }));
        assertEquals("still failing", ex.getMessage());
        assertEquals(2, calls.get());
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `bash -lc './mvnw -q test -Dtest=RetriesTest'`
Expected: compilation FAIL (`Retries` not found).

- [ ] **Step 3: Implement `RetryableException` and `Retries`**

```java
package org.roncax.podcaster.util;

public class RetryableException extends RuntimeException {
    public RetryableException(String message) { super(message); }
    public RetryableException(String message, Throwable cause) { super(message, cause); }
}
```

```java
package org.roncax.podcaster.util;

import java.time.Duration;
import java.util.concurrent.Callable;

public final class Retries {
    private Retries() {}

    public static <T> T withBackoff(int attempts, Duration initialDelay, Callable<T> action) throws Exception {
        long delay = initialDelay.toMillis();
        RetryableException last = null;
        for (int attempt = 1; attempt <= Math.max(1, attempts); attempt++) {
            try {
                return action.call();
            } catch (RetryableException e) {
                last = e;
                if (attempt < attempts) {
                    Thread.sleep(delay);
                    delay *= 2;
                }
            }
        }
        throw last;
    }
}
```

- [ ] **Step 4: Run `RetriesTest`**

Run: `bash -lc './mvnw -q test -Dtest=RetriesTest'`
Expected: PASS.

- [ ] **Step 5: Write the failing `HttpFetcherTest`**

```java
package org.roncax.podcaster.http;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.*;

class HttpFetcherTest {
    static WireMockServer wm;
    HttpFetcher fetcher = new HttpFetcher("TestAgent/1.0", Duration.ofSeconds(5), Duration.ZERO, 3, Duration.ofMillis(5));

    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }
    @BeforeEach void reset() { wm.resetAll(); }

    @Test
    void returnsBodyAndSendsUserAgent() throws Exception {
        wm.stubFor(get("/page").willReturn(ok("hello")));
        assertEquals("hello", new String(fetcher.get(wm.baseUrl() + "/page"), StandardCharsets.UTF_8));
        wm.verify(getRequestedFor(urlEqualTo("/page")).withHeader("User-Agent", equalTo("TestAgent/1.0")));
    }

    @Test
    void retriesServerErrors() throws Exception {
        wm.stubFor(get("/flaky").inScenario("f").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(serverError()).willSetStateTo("ok"));
        wm.stubFor(get("/flaky").inScenario("f").whenScenarioStateIs("ok").willReturn(ok("fine")));
        assertEquals("fine", new String(fetcher.get(wm.baseUrl() + "/flaky"), StandardCharsets.UTF_8));
        wm.verify(2, getRequestedFor(urlEqualTo("/flaky")));
    }

    @Test
    void clientErrorsFailWithoutRetry() {
        wm.stubFor(get("/missing").willReturn(notFound()));
        FetchException ex = assertThrows(FetchException.class, () -> fetcher.get(wm.baseUrl() + "/missing"));
        assertTrue(ex.getMessage().contains("404"), ex.getMessage());
        wm.verify(1, getRequestedFor(urlEqualTo("/missing")));
    }

    @Test
    void persistentServerErrorsFailAfterAttempts() {
        wm.stubFor(get("/down").willReturn(serviceUnavailable()));
        assertThrows(FetchException.class, () -> fetcher.get(wm.baseUrl() + "/down"));
        wm.verify(3, getRequestedFor(urlEqualTo("/down")));
    }
}
```

- [ ] **Step 6: Run to verify failure**

Run: `bash -lc './mvnw -q test -Dtest=HttpFetcherTest'`
Expected: compilation FAIL (`HttpFetcher` not found).

- [ ] **Step 7: Implement `FetchException` and `HttpFetcher`**

```java
package org.roncax.podcaster.http;

public class FetchException extends Exception {
    public FetchException(String message) { super(message); }
    public FetchException(String message, Throwable cause) { super(message, cause); }
}
```

```java
package org.roncax.podcaster.http;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.roncax.podcaster.config.PodcasterConfig;
import org.roncax.podcaster.util.Retries;
import org.roncax.podcaster.util.RetryableException;

@ApplicationScoped
public class HttpFetcher {
    private final HttpClient client;
    private final String userAgent;
    private final Duration timeout;
    private final Duration politenessDelay;
    private final int attempts;
    private final Duration retryDelay;
    private final Map<String, Instant> nextAllowed = new ConcurrentHashMap<>();

    @Inject
    public HttpFetcher(PodcasterConfig config) {
        this(config.http().userAgent(), config.http().timeout(), config.http().politenessDelay(),
                config.http().attempts(), config.http().retryDelay());
    }

    public HttpFetcher(String userAgent, Duration timeout, Duration politenessDelay, int attempts, Duration retryDelay) {
        this.userAgent = userAgent;
        this.timeout = timeout;
        this.politenessDelay = politenessDelay;
        this.attempts = attempts;
        this.retryDelay = retryDelay;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(timeout)
                .build();
    }

    public byte[] get(String url) throws FetchException {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new FetchException("Invalid URL: " + url, e);
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("User-Agent", userAgent)
                .header("Accept-Language", "it-IT,it;q=0.9,en;q=0.8")
                .GET()
                .build();
        try {
            return Retries.withBackoff(attempts, retryDelay, () -> {
                awaitPoliteness(uri.getHost());
                HttpResponse<byte[]> response;
                try {
                    response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                } catch (IOException e) {
                    throw new RetryableException("I/O error fetching " + url + ": " + e.getMessage(), e);
                }
                int status = response.statusCode();
                if (status == 429 || status >= 500) throw new RetryableException("HTTP " + status + " from " + url);
                if (status >= 400) throw new FetchException("HTTP " + status + " from " + url);
                return response.body();
            });
        } catch (FetchException e) {
            throw e;
        } catch (RetryableException e) {
            throw new FetchException(e.getMessage(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FetchException("Interrupted fetching " + url, e);
        } catch (Exception e) {
            throw new FetchException("Failed fetching " + url + ": " + e.getMessage(), e);
        }
    }

    private void awaitPoliteness(String host) throws InterruptedException {
        if (host == null || politenessDelay.isZero()) return;
        Instant wait;
        synchronized (nextAllowed) {
            Instant now = Instant.now();
            Instant allowed = nextAllowed.getOrDefault(host, now);
            Instant slot = allowed.isAfter(now) ? allowed : now;
            nextAllowed.put(host, slot.plus(politenessDelay));
            wait = slot;
        }
        long millis = Duration.between(Instant.now(), wait).toMillis();
        if (millis > 0) Thread.sleep(millis);
    }
}
```

- [ ] **Step 8: Run tests**

Run: `bash -lc './mvnw -q test -Dtest=RetriesTest,HttpFetcherTest'`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "Add HTTP fetcher with retries and per-host politeness

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01LbcoMupq1VXnfzdqroFmyp"
```

---
### Task 4: Source connector SPI and RSS connector

**Files:**
- Create: `src/main/java/org/roncax/podcaster/ingestion/{SourceConnector,SourceConfig,RawItem,RssSourceConnector,ConnectorRegistry}.java`
- Create: `src/test/java/org/roncax/podcaster/support/Fixtures.java`
- Test: `src/test/java/org/roncax/podcaster/ingestion/RssSourceConnectorTest.java`

**Interfaces:**
- Consumes: `HttpFetcher#get(String) → byte[]` (Task 3).
- Produces:
  - `interface SourceConnector { String type(); List<RawItem> fetch(SourceConfig config, Instant since) throws Exception; default boolean providesFullText() }`
  - `record SourceConfig(Map<String,String> values)` with `require(String key) → String` (throws `IllegalArgumentException("Missing source config 'key'")`) and `get(String key) → Optional<String>`.
  - `record RawItem(String url, String title, String author, Instant publishedAt, String summary, String fullText)` — `author`, `publishedAt`, `summary`, `fullText` may be null.
  - `RssSourceConnector` (type `"rss"`, config key `url`); items dated before `since` are dropped, undated items are kept.
  - `ConnectorRegistry#find(String type) → Optional<SourceConnector>`, `#types() → SortedSet<String>`.
  - `Fixtures.bytes(String name) → byte[]` reads `src/test/resources/fixtures/<name>`.

- [ ] **Step 1: Write `Fixtures` and the failing test**

```java
package org.roncax.podcaster.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

public final class Fixtures {
    private Fixtures() {}

    public static byte[] bytes(String name) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/fixtures/" + name)) {
            if (in == null) throw new IllegalArgumentException("No fixture " + name);
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
```

```java
package org.roncax.podcaster.ingestion;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.roncax.podcaster.http.HttpFetcher;
import org.roncax.podcaster.support.Fixtures;

class RssSourceConnectorTest {
    static WireMockServer wm;
    RssSourceConnector connector = new RssSourceConnector(
            new HttpFetcher("Test", Duration.ofSeconds(5), Duration.ZERO, 1, Duration.ofMillis(1)));

    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }
    @BeforeEach void reset() { wm.resetAll(); }

    private SourceConfig feed(String path, byte[] body) {
        wm.stubFor(get(path).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/rss+xml").withBody(body)));
        return new SourceConfig(Map.of("url", wm.baseUrl() + path));
    }

    @Test
    void parsesAnsaFeed() throws Exception {
        List<RawItem> items = connector.fetch(feed("/ansa", Fixtures.bytes("ansa-feed.xml")),
                Instant.parse("2026-10-05T12:00:00Z"));
        assertEquals(26, items.size());
        RawItem first = items.get(0);
        assertEquals("Deepfake audio, per realizzarli servono tre secondi di voce vera e 30 euro", first.title());
        assertEquals(Instant.parse("2026-10-05T15:56:49Z"), first.publishedAt());
        assertTrue(first.url().startsWith("https://www.ansa.it/canale_tecnologia/notizie/cybersecurity/"));
        assertTrue(first.summary().startsWith("Meloni registra la voce"), first.summary());
        assertNull(first.fullText());
    }

    @Test
    void parsesIlPostFeedWithEmptyFields() throws Exception {
        List<RawItem> items = connector.fetch(feed("/ilpost", Fixtures.bytes("ilpost-italia-feed.xml")),
                Instant.parse("2026-10-04T00:00:00Z"));
        assertEquals(7, items.size());
        RawItem first = items.get(0);
        assertEquals("Venerdì 9 ottobre è previsto uno sciopero dei mezzi pubblici a Milano", first.title());
        assertEquals("https://www.ilpost.it/2026/10/05/sciopero-atm-milano-como/", first.url());
        assertNull(first.author());
        assertNull(first.summary());
    }

    @Test
    void itemsWithoutDateAreKept() throws Exception {
        String rss = """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0"><channel><title>t</title><link>http://x</link><description>d</description>
                <item><title>Undated story</title><link>http://x/undated</link><description>Body</description></item>
                </channel></rss>""";
        List<RawItem> items = connector.fetch(feed("/undated", rss.getBytes()), Instant.now());
        assertEquals(1, items.size());
        assertNull(items.get(0).publishedAt());
        assertEquals("Body", items.get(0).summary());
    }

    @Test
    void missingUrlIsRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> connector.fetch(new SourceConfig(Map.of()), Instant.now()));
        assertTrue(ex.getMessage().contains("url"));
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `bash -lc './mvnw -q test -Dtest=RssSourceConnectorTest'`
Expected: compilation FAIL.

- [ ] **Step 3: Implement SPI types**

```java
package org.roncax.podcaster.ingestion;

import java.time.Instant;
import java.util.List;

/** A pluggable news source. Implementations are CDI beans discovered by {@link ConnectorRegistry}. */
public interface SourceConnector {
    /** Connector type stored in {@code sources.connector_type}, e.g. "rss" or "site:example". */
    String type();

    /** Fetch items published at or after {@code since}. Undated items are returned too. */
    List<RawItem> fetch(SourceConfig config, Instant since) throws Exception;

    /** True when {@link RawItem#fullText()} is already the article text, so no extraction is needed. */
    default boolean providesFullText() { return false; }
}
```

```java
package org.roncax.podcaster.ingestion;

import java.util.Map;
import java.util.Optional;

public record SourceConfig(Map<String, String> values) {
    public SourceConfig {
        values = values == null ? Map.of() : Map.copyOf(values);
    }

    public String require(String key) {
        String v = values.get(key);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("Missing source config '" + key + "'");
        return v.trim();
    }

    public Optional<String> get(String key) {
        return Optional.ofNullable(values.get(key)).filter(v -> !v.isBlank());
    }
}
```

```java
package org.roncax.podcaster.ingestion;

import java.time.Instant;

public record RawItem(String url, String title, String author, Instant publishedAt, String summary, String fullText) {}
```

```java
package org.roncax.podcaster.ingestion;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;

@ApplicationScoped
public class ConnectorRegistry {
    @Inject Instance<SourceConnector> connectors;

    public Optional<SourceConnector> find(String type) {
        return connectors.stream().filter(c -> c.type().equals(type)).findFirst();
    }

    public SortedSet<String> types() {
        TreeSet<String> types = new TreeSet<>();
        connectors.forEach(c -> types.add(c.type()));
        return types;
    }
}
```

- [ ] **Step 4: Implement `RssSourceConnector`**

```java
package org.roncax.podcaster.ingestion;

import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.jsoup.Jsoup;
import org.roncax.podcaster.http.HttpFetcher;

@ApplicationScoped
public class RssSourceConnector implements SourceConnector {
    public static final String TYPE = "rss";
    private static final int MIN_FULL_TEXT_CHARS = 500;

    private final HttpFetcher fetcher;

    @Inject
    public RssSourceConnector(HttpFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public String type() { return TYPE; }

    @Override
    public List<RawItem> fetch(SourceConfig config, Instant since) throws Exception {
        String url = config.require("url");
        byte[] body = fetcher.get(url);
        SyndFeed feed;
        try (XmlReader reader = new XmlReader(new ByteArrayInputStream(body))) {
            feed = new SyndFeedInput().build(reader);
        }
        List<RawItem> items = new ArrayList<>();
        for (SyndEntry entry : feed.getEntries()) {
            String link = firstNonBlank(entry.getLink(), entry.getUri());
            if (link == null) continue;
            Date date = entry.getPublishedDate() != null ? entry.getPublishedDate() : entry.getUpdatedDate();
            Instant published = date == null ? null : date.toInstant();
            if (published != null && since != null && published.isBefore(since)) continue;
            String title = entry.getTitle() == null || entry.getTitle().isBlank() ? link : entry.getTitle().trim();
            String summary = entry.getDescription() == null ? null : htmlToText(entry.getDescription().getValue());
            items.add(new RawItem(link.trim(), title, blankToNull(entry.getAuthor()), published, summary, fullText(entry)));
        }
        return items;
    }

    private static String fullText(SyndEntry entry) {
        for (SyndContent content : entry.getContents()) {
            String text = htmlToText(content.getValue());
            if (text != null && text.length() >= MIN_FULL_TEXT_CHARS) return text;
        }
        return null;
    }

    static String htmlToText(String html) {
        if (html == null) return null;
        String text = Jsoup.parse(html).text().trim();
        return text.isEmpty() ? null : text;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return null;
    }
}
```

- [ ] **Step 5: Run tests**

Run: `bash -lc './mvnw -q test -Dtest=RssSourceConnectorTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Add source connector SPI and RSS connector

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01LbcoMupq1VXnfzdqroFmyp"
```

---

### Task 5: Article text extraction (generic + ANSA)

**Files:**
- Create: `src/main/java/org/roncax/podcaster/extraction/{ContentExtractor,DefaultContentExtractor,AnsaContentExtractor,ContentExtractionService}.java`
- Test: `src/test/java/org/roncax/podcaster/extraction/ContentExtractionTest.java`

**Interfaces:**
- Consumes: `HttpFetcher#get` (Task 3), `Fixtures.bytes` (Task 4).
- Produces:
  - `interface ContentExtractor { boolean supports(URI url); Optional<String> extract(Document doc); default int priority() { return 0; } }` — paragraphs joined with `"\n\n"`.
  - `DefaultContentExtractor` (priority -100, supports all): picks the element whose direct `<p>` children (each ≥ 40 chars) have the most text; requires ≥ 200 chars.
  - `AnsaContentExtractor` (priority 10, host ends with `ansa.it`): `[itemprop=articleBody], div.news-txt` paragraphs except `.article-copyright`.
  - `ContentExtractionService#extract(String url) → Optional<String>` (throws `FetchException`), `#extract(String url, byte[] html) → Optional<String>`; public constructor `ContentExtractionService(HttpFetcher, List<ContentExtractor>)` for unit tests.

- [ ] **Step 1: Write the failing test**

```java
package org.roncax.podcaster.extraction;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.http.HttpFetcher;
import org.roncax.podcaster.support.Fixtures;

class ContentExtractionTest {
    ContentExtractionService service = new ContentExtractionService(
            new HttpFetcher("Test", Duration.ofSeconds(1), Duration.ZERO, 1, Duration.ofMillis(1)),
            List.of(new DefaultContentExtractor(), new AnsaContentExtractor()));

    private static Document parse(String fixture, String url) throws Exception {
        return Jsoup.parse(new ByteArrayInputStream(Fixtures.bytes(fixture)), null, url);
    }

    @Test
    void ansaExtractorReturnsCleanBody() throws Exception {
        String text = new AnsaContentExtractor().extract(parse("ansa-article.html", "https://www.ansa.it/a.html")).orElseThrow();
        assertTrue(text.startsWith("Una traccia vocale sintetica"), text.substring(0, 60));
        assertTrue(text.contains("security.org"));
        assertFalse(text.contains("Riproduzione riservata"));
        assertTrue(text.contains("\n\n"), "paragraphs are separated by blank lines");
    }

    @Test
    void defaultExtractorFindsIlPostBody() throws Exception {
        String text = new DefaultContentExtractor().extract(parse("ilpost-article.html", "https://www.ilpost.it/a/")).orElseThrow();
        assertTrue(text.startsWith("Venerdì 9 ottobre è previsto uno sciopero"), text.substring(0, 60));
        assertTrue(text.contains("52 per cento"));
        assertFalse(text.contains("Tag:"));
    }

    @Test
    void serviceUsesSiteExtractorForAnsaHost() {
        String text = service.extract("https://www.ansa.it/sito/x.html", Fixtures.bytes("ansa-article.html")).orElseThrow();
        assertFalse(text.contains("Riproduzione riservata"));
    }

    @Test
    void serviceFallsBackToDefaultExtractor() {
        String text = service.extract("https://www.ilpost.it/2026/10/05/x/", Fixtures.bytes("ilpost-article.html")).orElseThrow();
        assertTrue(text.contains("funicolare Como-Brunate"));
    }

    @Test
    void pagesWithoutArticleTextYieldEmpty() {
        byte[] html = "<html><body><nav><p>Menu</p></nav><p>Short.</p></body></html>".getBytes();
        assertTrue(service.extract("https://example.com/", html).isEmpty());
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `bash -lc './mvnw -q test -Dtest=ContentExtractionTest'`
Expected: compilation FAIL.

- [ ] **Step 3: Implement the extractors**

```java
package org.roncax.podcaster.extraction;

import java.net.URI;
import java.util.Optional;
import org.jsoup.nodes.Document;

/** Turns an article page into plain text paragraphs separated by blank lines. */
public interface ContentExtractor {
    boolean supports(URI url);

    Optional<String> extract(Document doc);

    /** Higher runs first. Site-specific extractors use positive values, the generic one -100. */
    default int priority() { return 0; }
}
```

```java
package org.roncax.podcaster.extraction;

import jakarta.enterprise.context.ApplicationScoped;
import java.net.URI;
import java.util.Optional;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

@ApplicationScoped
public class DefaultContentExtractor implements ContentExtractor {
    private static final int MIN_PARAGRAPH_CHARS = 40;
    private static final int MIN_TOTAL_CHARS = 200;

    @Override
    public boolean supports(URI url) { return true; }

    @Override
    public int priority() { return -100; }

    @Override
    public Optional<String> extract(Document original) {
        Document doc = original.clone();
        doc.select("script,style,noscript,nav,header,footer,aside,form,figure,iframe").remove();
        Element best = null;
        int bestScore = 0;
        for (Element el : doc.getAllElements()) {
            int score = 0;
            for (Element child : el.children()) {
                if (child.nameIs("p")) {
                    int len = child.text().length();
                    if (len >= MIN_PARAGRAPH_CHARS) score += len;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = el;
            }
        }
        if (best == null || bestScore < MIN_TOTAL_CHARS) return Optional.empty();
        StringBuilder sb = new StringBuilder();
        for (Element child : best.children()) {
            if (child.nameIs("p") && !child.text().isBlank()) {
                if (!sb.isEmpty()) sb.append("\n\n");
                sb.append(child.text().trim());
            }
        }
        return Optional.of(sb.toString());
    }
}
```

```java
package org.roncax.podcaster.extraction;

import jakarta.enterprise.context.ApplicationScoped;
import java.net.URI;
import java.util.Optional;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

@ApplicationScoped
public class AnsaContentExtractor implements ContentExtractor {

    @Override
    public boolean supports(URI url) {
        return url.getHost() != null && url.getHost().endsWith("ansa.it");
    }

    @Override
    public int priority() { return 10; }

    @Override
    public Optional<String> extract(Document doc) {
        Element body = doc.selectFirst("[itemprop=articleBody], div.news-txt");
        if (body == null) return Optional.empty();
        StringBuilder sb = new StringBuilder();
        for (Element p : body.select("p:not(.article-copyright)")) {
            String text = p.text().trim();
            if (text.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append("\n\n");
            sb.append(text);
        }
        return sb.isEmpty() ? Optional.empty() : Optional.of(sb.toString());
    }
}
```

- [ ] **Step 4: Implement `ContentExtractionService`**

```java
package org.roncax.podcaster.extraction;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.roncax.podcaster.http.FetchException;
import org.roncax.podcaster.http.HttpFetcher;

@ApplicationScoped
public class ContentExtractionService {
    private final HttpFetcher fetcher;
    private final List<ContentExtractor> extractors;

    @Inject
    public ContentExtractionService(HttpFetcher fetcher, Instance<ContentExtractor> extractors) {
        this(fetcher, extractors.stream().toList());
    }

    public ContentExtractionService(HttpFetcher fetcher, List<ContentExtractor> extractors) {
        this.fetcher = fetcher;
        this.extractors = extractors.stream()
                .sorted(Comparator.comparingInt(ContentExtractor::priority).reversed())
                .toList();
    }

    public Optional<String> extract(String url) throws FetchException {
        return extract(url, fetcher.get(url));
    }

    public Optional<String> extract(String url, byte[] html) {
        URI uri;
        Document doc;
        try {
            uri = URI.create(url);
            doc = Jsoup.parse(new ByteArrayInputStream(html), null, url);
        } catch (IllegalArgumentException | IOException e) {
            return Optional.empty();
        }
        for (ContentExtractor extractor : extractors) {
            if (!extractor.supports(uri)) continue;
            Optional<String> text = extractor.extract(doc).filter(t -> !t.isBlank());
            if (text.isPresent()) return text;
        }
        return Optional.empty();
    }
}
```

- [ ] **Step 5: Run tests**

Run: `bash -lc './mvnw -q test -Dtest=ContentExtractionTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Add article text extraction with ANSA-specific extractor

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01LbcoMupq1VXnfzdqroFmyp"
```

---

### Task 6: Ingestion service (fetch, dedupe, extract, persist)

**Files:**
- Create: `src/main/java/org/roncax/podcaster/ingestion/{ContentHasher,IngestionReport,IngestionService}.java`
- Test: `src/test/java/org/roncax/podcaster/ingestion/ContentHasherTest.java`, `src/test/java/org/roncax/podcaster/ingestion/IngestionServiceTest.java`

**Interfaces:**
- Consumes: `ConnectorRegistry`, `SourceConfig`, `RawItem` (Task 4); `ContentExtractionService#extract(String)` (Task 5); entities `Source`, `Item` (Task 2); `Exceptions.rootMessage` (Task 2).
- Produces:
  - `ContentHasher.hash(String title, String text) → String` (64-char sha256 hex of normalized title + first 2000 chars of text).
  - `record IngestionReport(int sources, int newItems, List<String> errors)` with `allFailed() → boolean` (true when `sources > 0` and every source failed).
  - `IngestionService#ingest(long showId, Instant since) → IngestionReport` — never throws for source-level failures; records `Source.lastError`/`lastFetchedAt`.

- [ ] **Step 1: Write the failing `ContentHasherTest`**

```java
package org.roncax.podcaster.ingestion;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ContentHasherTest {
    @Test
    void normalizesCaseAndWhitespace() {
        assertEquals(ContentHasher.hash("Big  News", "Some text\n here"), ContentHasher.hash("big news", "some text here"));
    }

    @Test
    void differentTitlesDiffer() {
        assertNotEquals(ContentHasher.hash("A", "x"), ContentHasher.hash("B", "x"));
    }

    @Test
    void handlesNullText() {
        assertEquals(64, ContentHasher.hash("Title", null).length());
    }
}
```

- [ ] **Step 2: Implement `ContentHasher` and `IngestionReport`**

```java
package org.roncax.podcaster.ingestion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

public final class ContentHasher {
    private static final int TEXT_PREFIX = 2000;

    private ContentHasher() {}

    public static String hash(String title, String text) {
        String body = text == null ? "" : text;
        if (body.length() > TEXT_PREFIX) body = body.substring(0, TEXT_PREFIX);
        String normalized = normalize(title) + "\n" + normalize(body);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
}
```

```java
package org.roncax.podcaster.ingestion;

import java.util.List;

public record IngestionReport(int sources, int newItems, List<String> errors) {
    public boolean allFailed() {
        return sources > 0 && errors.size() == sources;
    }
}
```

- [ ] **Step 3: Run `ContentHasherTest`**

Run: `bash -lc './mvnw -q test -Dtest=ContentHasherTest'`
Expected: PASS.

- [ ] **Step 4: Write the failing `IngestionServiceTest`**

```java
package org.roncax.podcaster.ingestion;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.domain.Item;
import org.roncax.podcaster.domain.Show;
import org.roncax.podcaster.domain.Source;
import org.roncax.podcaster.support.*;

@QuarkusTest
@WithTestResource(WireMockResource.class)
class IngestionServiceTest {
    @InjectWireMock WireMockServer wm;
    @Inject IngestionService ingestion;

    @BeforeEach
    void setup() {
        TestData.cleanDb();
        WireMockResource.installDefaults(wm);
    }

    /** RSS feed whose items link to WireMock paths; each item is "title|path". */
    private String rss(String... items) {
        String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.now().atOffset(ZoneOffset.UTC));
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>t</title><link>http://x</link><description>d</description>");
        for (String it : items) {
            String[] p = it.split("\\|");
            sb.append("<item><title>").append(p[0]).append("</title><link>").append(wm.baseUrl()).append(p[1])
              .append("</link><pubDate>").append(date).append("</pubDate><description>Teaser</description></item>");
        }
        return sb.append("</channel></rss>").toString();
    }

    private void stubFeed(String path, String body) {
        wm.stubFor(get(path).willReturn(okXml(body)));
    }

    private void stubArticle(String path) {
        wm.stubFor(get(path).willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/html")
                .withBody(Fixtures.bytes("ilpost-article.html"))));
    }

    private Instant since() { return Instant.now().minus(1, ChronoUnit.DAYS); }

    @Test
    void ingestsAndExtractsFullText() {
        Show show = TestData.show("ing1");
        TestData.source(show.id, wm.baseUrl() + "/feed1");
        stubFeed("/feed1", rss("First story|/a1", "Second story|/a2"));
        stubArticle("/a1");
        stubArticle("/a2");

        IngestionReport report = ingestion.ingest(show.id, since());

        assertEquals(2, report.newItems());
        assertTrue(report.errors().isEmpty());
        List<Item> items = QuarkusTransaction.requiringNew().call(() -> Item.<Item>list("showId", show.id));
        assertEquals(2, items.size());
        assertTrue(items.get(0).fullText.contains("sciopero"));
        assertEquals("Teaser", items.get(0).summary);
    }

    @Test
    void secondIngestAddsNothingAndDoesNotRefetchArticles() {
        Show show = TestData.show("ing2");
        TestData.source(show.id, wm.baseUrl() + "/feed2");
        stubFeed("/feed2", rss("Story|/b1"));
        stubArticle("/b1");

        assertEquals(1, ingestion.ingest(show.id, since()).newItems());
        assertEquals(0, ingestion.ingest(show.id, since()).newItems());
        wm.verify(1, getRequestedFor(urlEqualTo("/b1")));
    }

    @Test
    void sameStoryUnderDifferentUrlIsDeduplicated() {
        Show show = TestData.show("ing3");
        TestData.source(show.id, wm.baseUrl() + "/feed3");
        stubFeed("/feed3", rss("Same story|/c1", "Same story|/c1-copy"));
        stubArticle("/c1");
        stubArticle("/c1-copy");

        assertEquals(1, ingestion.ingest(show.id, since()).newItems());
    }

    @Test
    void partialSourceFailureIsReportedButOthersSucceed() {
        Show show = TestData.show("ing4");
        TestData.source(show.id, wm.baseUrl() + "/feed4");
        Source broken = TestData.source(show.id, wm.baseUrl() + "/broken");
        stubFeed("/feed4", rss("Good story|/d1"));
        stubArticle("/d1");
        wm.stubFor(get("/broken").willReturn(serverError()));

        IngestionReport report = ingestion.ingest(show.id, since());

        assertEquals(1, report.newItems());
        assertEquals(1, report.errors().size());
        assertFalse(report.allFailed());
        Source reloaded = QuarkusTransaction.requiringNew().call(() -> Source.<Source>findById(broken.id));
        assertNotNull(reloaded.lastError);
    }

    @Test
    void allSourcesFailing() {
        Show show = TestData.show("ing5");
        TestData.source(show.id, wm.baseUrl() + "/broken5");
        wm.stubFor(get("/broken5").willReturn(notFound()));

        IngestionReport report = ingestion.ingest(show.id, since());

        assertTrue(report.allFailed());
        assertEquals(0, report.newItems());
    }

    @Test
    void articleExtractionFailureKeepsItemWithSummary() {
        Show show = TestData.show("ing6");
        TestData.source(show.id, wm.baseUrl() + "/feed6");
        stubFeed("/feed6", rss("Paywalled|/gone"));
        wm.stubFor(get("/gone").willReturn(forbidden()));

        assertEquals(1, ingestion.ingest(show.id, since()).newItems());
        Item item = QuarkusTransaction.requiringNew().call(() -> Item.<Item>find("showId", show.id).firstResult());
        assertNull(item.fullText);
        assertEquals("Teaser", item.bestText());
    }
}
```

- [ ] **Step 5: Run to verify failure**

Run: `bash -lc './mvnw -q test -Dtest=IngestionServiceTest'`
Expected: compilation FAIL (`IngestionService` not found).

- [ ] **Step 6: Implement `IngestionService`**

```java
package org.roncax.podcaster.ingestion;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jboss.logging.Logger;
import org.roncax.podcaster.domain.Item;
import org.roncax.podcaster.domain.Source;
import org.roncax.podcaster.extraction.ContentExtractionService;
import org.roncax.podcaster.util.Exceptions;

@ApplicationScoped
public class IngestionService {
    private static final Logger LOG = Logger.getLogger(IngestionService.class);
    private static final int MAX_URL = 2000;
    private static final int MAX_TITLE = 1000;

    @Inject ConnectorRegistry registry;
    @Inject ContentExtractionService extraction;

    public IngestionReport ingest(long showId, Instant since) {
        List<Source> sources = QuarkusTransaction.requiringNew()
                .call(() -> Source.<Source>list("showId = ?1 and enabled = true order by id", showId));
        int added = 0;
        List<String> errors = new ArrayList<>();
        for (Source source : sources) {
            try {
                added += ingestSource(showId, source, since);
                markSource(source.id, null);
            } catch (Exception e) {
                String message = Exceptions.rootMessage(e);
                LOG.warnf("Source %s failed: %s", source.label(), message);
                errors.add(source.label() + ": " + message);
                markSource(source.id, message);
            }
        }
        return new IngestionReport(sources.size(), added, errors);
    }

    private int ingestSource(long showId, Source source, Instant since) throws Exception {
        SourceConnector connector = registry.find(source.connectorType)
                .orElseThrow(() -> new IllegalStateException("Unknown connector type '" + source.connectorType + "'"));
        List<RawItem> raws = connector.fetch(new SourceConfig(source.config), since);
        int added = 0;
        for (RawItem raw : raws) {
            if (raw.url().length() > MAX_URL || exists(showId, raw.url())) continue;
            String fullText = raw.fullText();
            if (fullText == null && source.fetchFullText && !connector.providesFullText()) {
                try {
                    fullText = extraction.extract(raw.url()).orElse(null);
                } catch (Exception e) {
                    LOG.debugf("Extraction failed for %s: %s", raw.url(), e.getMessage());
                }
            }
            if (save(showId, source.id, raw, fullText)) added++;
        }
        return added;
    }

    private boolean exists(long showId, String url) {
        return QuarkusTransaction.requiringNew()
                .call(() -> Item.count("showId = ?1 and url = ?2", showId, url) > 0);
    }

    private boolean save(long showId, long sourceId, RawItem raw, String fullText) {
        String hash = ContentHasher.hash(raw.title(), fullText != null ? fullText : raw.summary());
        return QuarkusTransaction.requiringNew().call(() -> {
            if (Item.count("showId = ?1 and contentHash = ?2", showId, hash) > 0) return false;
            Item item = new Item();
            item.showId = showId;
            item.sourceId = sourceId;
            item.url = raw.url();
            item.contentHash = hash;
            item.title = raw.title().length() > MAX_TITLE ? raw.title().substring(0, MAX_TITLE) : raw.title();
            item.author = raw.author();
            item.publishedAt = raw.publishedAt();
            item.fetchedAt = Instant.now();
            item.summary = raw.summary();
            item.fullText = fullText;
            item.persist();
            return true;
        });
    }

    private void markSource(long sourceId, String error) {
        QuarkusTransaction.requiringNew().run(() -> {
            Source s = Source.findById(sourceId);
            if (s == null) return;
            s.lastFetchedAt = Instant.now();
            s.lastError = error;
        });
    }
}
```

- [ ] **Step 7: Run tests**

Run: `bash -lc './mvnw -q test -Dtest=ContentHasherTest,IngestionServiceTest'`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "Add ingestion service with dedupe and full-text extraction

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01LbcoMupq1VXnfzdqroFmyp"
```

---
### Task 7: LLM model registry and test doubles

**Files:**
- Create: `src/main/java/org/roncax/podcaster/llm/{ChatModelRegistry,UnknownModelException}.java`
- Create: `src/test/java/org/roncax/podcaster/support/{FakeChatModel,FakeChatModelRegistry}.java`
- Test: `src/test/java/org/roncax/podcaster/llm/ChatModelRegistryTest.java`, `src/test/java/org/roncax/podcaster/llm/ChatModelRegistryEnabledTest.java`

**Interfaces:**
- Produces:
  - `ChatModelRegistry#availableNames() → SortedSet<String>` (slots configured via `quarkus.langchain4j.<slot>.chat-model.provider` whose `quarkus.langchain4j.<prefix>.<slot>.enable-integration` is not `false`), `#isAvailable(String) → boolean`, `#get(String) → ChatModel` (throws `UnknownModelException`).
  - `UnknownModelException(String name, Set<String> available)` (unchecked).
  - Test: `FakeChatModel` (implements `ChatModel`): `respond(String...)` queues replies; `responder(Function<String,String>)` answers from the last user message when the queue is empty; `requests` (list of `ChatRequest`); `userMessage(int index) → String`; static `lastUser(ChatRequest) → String`.
  - Test: `FakeChatModelRegistry.install(FakeChatModel) → FakeChatModelRegistry` (via `QuarkusMock`, only model name `"fake"` is available).

- [ ] **Step 1: Write the failing tests**

```java
package org.roncax.podcaster.llm;

import static org.junit.jupiter.api.Assertions.*;

import dev.langchain4j.model.chat.ChatModel;
import io.quarkiverse.langchain4j.ModelName;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.support.WireMockResource;

@QuarkusTest
@WithTestResource(WireMockResource.class)
class ChatModelRegistryTest {
    @Inject ChatModelRegistry registry;
    @Inject @Any Instance<ChatModel> models;

    @Test
    void allSlotBeansExistEvenWhenDisabled() {
        for (String slot : new String[] {"gpt", "claude", "gemini", "local"}) {
            assertTrue(models.select(ModelName.Literal.of(slot)).isResolvable(), "slot bean missing: " + slot);
        }
    }

    @Test
    void disabledSlotsAreNotAvailable() {
        assertTrue(registry.availableNames().isEmpty(), registry.availableNames().toString());
        UnknownModelException ex = assertThrows(UnknownModelException.class, () -> registry.get("claude"));
        assertTrue(ex.getMessage().contains("claude"));
    }
}
```

```java
package org.roncax.podcaster.llm;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.support.WireMockResource;

@QuarkusTest
@TestProfile(ChatModelRegistryEnabledTest.LocalEnabled.class)
@WithTestResource(WireMockResource.class)
class ChatModelRegistryEnabledTest {

    public static class LocalEnabled implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.langchain4j.ollama.local.enable-integration", "true",
                    "quarkus.langchain4j.ollama.local.base-url", "http://localhost:1");
        }
    }

    @Inject ChatModelRegistry registry;

    @Test
    void enabledSlotIsAvailableAndResolvable() {
        assertEquals(Set.of("local"), registry.availableNames());
        assertTrue(registry.isAvailable("local"));
        assertNotNull(registry.get("local"));
        assertFalse(registry.isAvailable("gpt"));
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `bash -lc './mvnw -q test -Dtest="ChatModelRegistry*Test"'`
Expected: compilation FAIL (`ChatModelRegistry` not found).

- [ ] **Step 3: Implement**

```java
package org.roncax.podcaster.llm;

import java.util.Set;

public class UnknownModelException extends RuntimeException {
    public UnknownModelException(String name, Set<String> available) {
        super("Unknown or disabled model '" + name + "'. Available: " + available);
    }
}
```

```java
package org.roncax.podcaster.llm;

import dev.langchain4j.model.chat.ChatModel;
import io.quarkiverse.langchain4j.ModelName;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

/** Looks up quarkus-langchain4j named models ("slots") by name. */
@ApplicationScoped
public class ChatModelRegistry {
    private static final Pattern SLOT = Pattern.compile("^quarkus\\.langchain4j\\.([a-z0-9-]+)\\.chat-model\\.provider$");
    /** Provider id → config prefix under quarkus.langchain4j. */
    static final Map<String, String> PREFIXES = Map.of(
            "openai", "openai",
            "anthropic", "anthropic",
            "ollama", "ollama",
            "ai-gemini", "ai.gemini");

    @Inject @Any Instance<ChatModel> models;

    public SortedSet<String> availableNames() {
        Config config = ConfigProvider.getConfig();
        SortedSet<String> names = new TreeSet<>();
        for (String property : config.getPropertyNames()) {
            Matcher m = SLOT.matcher(property);
            if (!m.matches()) continue;
            String slot = m.group(1);
            String provider = config.getValue(property, String.class);
            String prefix = PREFIXES.getOrDefault(provider, provider);
            boolean enabled = config
                    .getOptionalValue("quarkus.langchain4j." + prefix + "." + slot + ".enable-integration", Boolean.class)
                    .orElse(true);
            if (enabled) names.add(slot);
        }
        return names;
    }

    public boolean isAvailable(String name) {
        return name != null && availableNames().contains(name);
    }

    public ChatModel get(String name) {
        if (!isAvailable(name)) throw new UnknownModelException(name, availableNames());
        return models.select(ModelName.Literal.of(name)).get();
    }
}
```

- [ ] **Step 4: Write the test doubles**

```java
package org.roncax.podcaster.support;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

public class FakeChatModel implements ChatModel {
    private final Deque<String> queued = new ArrayDeque<>();
    private Function<String, String> responder;
    public final List<ChatRequest> requests = new CopyOnWriteArrayList<>();

    public FakeChatModel respond(String... replies) {
        synchronized (queued) { queued.addAll(List.of(replies)); }
        return this;
    }

    public FakeChatModel responder(Function<String, String> responder) {
        this.responder = responder;
        return this;
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        requests.add(request);
        String text;
        synchronized (queued) { text = queued.poll(); }
        if (text == null) {
            if (responder == null) throw new IllegalStateException("FakeChatModel has no reply for: " + lastUser(request));
            text = responder.apply(lastUser(request));
        }
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }

    public String userMessage(int index) {
        return lastUser(requests.get(index));
    }

    public static String lastUser(ChatRequest request) {
        List<ChatMessage> messages = request.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage um) return um.singleText();
        }
        return "";
    }
}
```

```java
package org.roncax.podcaster.support;

import dev.langchain4j.model.chat.ChatModel;
import io.quarkus.test.junit.QuarkusMock;
import java.util.SortedSet;
import java.util.TreeSet;
import org.roncax.podcaster.llm.ChatModelRegistry;
import org.roncax.podcaster.llm.UnknownModelException;

public class FakeChatModelRegistry extends ChatModelRegistry {
    public final FakeChatModel model;

    public FakeChatModelRegistry(FakeChatModel model) {
        this.model = model;
    }

    /** Replaces the registry bean for the current test; only "fake" is available. */
    public static FakeChatModelRegistry install(FakeChatModel model) {
        FakeChatModelRegistry registry = new FakeChatModelRegistry(model);
        QuarkusMock.installMockForType(registry, ChatModelRegistry.class);
        return registry;
    }

    @Override
    public SortedSet<String> availableNames() {
        return new TreeSet<>(java.util.Set.of("fake"));
    }

    @Override
    public ChatModel get(String name) {
        if (!isAvailable(name)) throw new UnknownModelException(name, availableNames());
        return model;
    }
}
```

- [ ] **Step 5: Run tests**

Run: `bash -lc './mvnw -q test -Dtest="ChatModelRegistry*Test"'`
Expected: PASS. If `allSlotBeansExistEvenWhenDisabled` fails for one slot, the build-time provider id is wrong — read the startup log for the expected provider name and fix `application.yml` and `PREFIXES`.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Add LLM model registry over quarkus-langchain4j named slots

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01LbcoMupq1VXnfzdqroFmyp"
```

---

### Task 8: LLM output handling and story ranking (SELECT logic)

**Files:**
- Create: `src/main/java/org/roncax/podcaster/llm/{LlmText,JsonChat,GenerationException}.java`
- Create: `src/main/java/org/roncax/podcaster/generation/{Prompts,StoryRanker}.java`
- Test: `src/test/java/org/roncax/podcaster/llm/LlmTextTest.java`, `src/test/java/org/roncax/podcaster/generation/StoryRankerTest.java`

**Interfaces:**
- Consumes: `FakeChatModel` (Task 7), `Item`, `Selection`, `Cluster` (Task 2).
- Produces:
  - `LlmText.clean(String) → String` (removes `<think>…</think>`, unwraps a single fenced block, trims; null → `""`), `LlmText.jsonObject(String) → String` (first `{` … last `}` of the cleaned text; throws `IllegalArgumentException` if absent).
  - `JsonChat.ask(ChatModel, String prompt, Class<T>) → T` — one repair round-trip on parse failure, then `GenerationException`.
  - `GenerationException(String)` / `(String, Throwable)` (unchecked).
  - `Prompts.languageName(String tag) → String`, `Prompts.rank(String showName, String language, String focus, List<String> itemLines) → String`. (Task 10 adds `segment` and `framing`.) Every prompt's first line is `TASK: <NAME>`.
  - `StoryRanker#rank(ChatModel, String showName, String language, String focusPrompt, List<Item> candidates) → Selection` — clusters reference only candidate ids, each id at most once, importance clamped to 1..10, sorted by importance desc; throws `GenerationException` when no cluster survives.

- [ ] **Step 1: Write the failing `LlmTextTest`**

```java
package org.roncax.podcaster.llm;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LlmTextTest {
    @Test
    void stripsThinkBlocks() {
        assertEquals("Hello there.", LlmText.clean("<think>\nLet me reason...\n</think>\n\nHello there."));
    }

    @Test
    void unwrapsCodeFences() {
        assertEquals("{\"a\":1}", LlmText.clean("```json\n{\"a\":1}\n```"));
    }

    @Test
    void findsJsonInsideProseAndThinking() {
        assertEquals("{\"a\":{\"b\":2}}", LlmText.jsonObject("<think>{not this}</think>Sure! Here it is: {\"a\":{\"b\":2}} Hope it helps."));
    }

    @Test
    void missingJsonIsAnError() {
        assertThrows(IllegalArgumentException.class, () -> LlmText.jsonObject("no json here"));
    }

    @Test
    void nullIsEmpty() {
        assertEquals("", LlmText.clean(null));
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `bash -lc './mvnw -q test -Dtest=LlmTextTest'`
Expected: compilation FAIL.

- [ ] **Step 3: Implement `LlmText`, `GenerationException`, `JsonChat`**

```java
package org.roncax.podcaster.llm;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LlmText {
    private static final Pattern THINK = Pattern.compile("(?is)<think>.*?</think>");
    private static final Pattern FENCE = Pattern.compile("(?s)^```[a-zA-Z]*\\s*\\n?(.*?)\\n?```$");

    private LlmText() {}

    public static String clean(String raw) {
        if (raw == null) return "";
        String s = THINK.matcher(raw).replaceAll("").trim();
        Matcher m = FENCE.matcher(s);
        if (m.matches()) s = m.group(1).trim();
        return s;
    }

    public static String jsonObject(String raw) {
        String s = clean(raw);
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IllegalArgumentException("No JSON object found in model reply");
        return s.substring(start, end + 1);
    }
}
```

```java
package org.roncax.podcaster.llm;

public class GenerationException extends RuntimeException {
    public GenerationException(String message) { super(message); }
    public GenerationException(String message, Throwable cause) { super(message, cause); }
}
```

```java
package org.roncax.podcaster.llm;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import java.util.ArrayList;
import java.util.List;

/** Asks a model for a JSON object and maps it to a type, with one repair attempt. */
public final class JsonChat {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    static final String REPAIR = "Your previous reply could not be parsed as JSON (%s). "
            + "Reply again with ONLY the JSON object: no prose, no code fences.";

    private JsonChat() {}

    public static <T> T ask(ChatModel model, String prompt, Class<T> type) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from(prompt));
        for (int attempt = 1; ; attempt++) {
            String reply = model.chat(messages).aiMessage().text();
            try {
                return MAPPER.readValue(LlmText.jsonObject(reply), type);
            } catch (Exception e) {
                if (attempt >= 2) {
                    throw new GenerationException("Model did not return valid JSON after 2 attempts: " + e.getMessage(), e);
                }
                messages.add(AiMessage.from(reply == null || reply.isBlank() ? "(empty reply)" : reply));
                messages.add(UserMessage.from(REPAIR.formatted(e.getMessage())));
            }
        }
    }
}
```

- [ ] **Step 4: Run `LlmTextTest`**

Run: `bash -lc './mvnw -q test -Dtest=LlmTextTest'`
Expected: PASS.

- [ ] **Step 5: Write the failing `StoryRankerTest`**

```java
package org.roncax.podcaster.generation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.domain.Cluster;
import org.roncax.podcaster.domain.Item;
import org.roncax.podcaster.domain.Selection;
import org.roncax.podcaster.llm.GenerationException;
import org.roncax.podcaster.support.FakeChatModel;

class StoryRankerTest {
    StoryRanker ranker = new StoryRanker();

    static Item item(long id, String title) {
        Item i = new Item();
        i.id = id;
        i.title = title;
        i.summary = "Summary for " + title;
        return i;
    }

    List<Item> items = List.of(item(1, "Alpha"), item(2, "Beta"), item(3, "Gamma"));

    @Test
    void sanitizesAndSortsClusters() {
        FakeChatModel model = new FakeChatModel().respond("""
                ```json
                {"clusters":[
                  {"headline":"B story","itemIds":[2,99],"importance":5},
                  {"headline":"A story","itemIds":[1,2],"importance":12},
                  {"headline":"Nothing","itemIds":[42],"importance":9}
                ]}
                ```""");

        Selection s = ranker.rank(model, "Daily", "it", null, items);

        assertEquals(2, s.clusters().size());
        Cluster first = s.clusters().get(0);
        assertEquals("A story", first.headline());
        assertEquals(10, first.importance());
        assertEquals(List.of(1L), first.itemIds());
        assertEquals(List.of(2L), s.clusters().get(1).itemIds());
    }

    @Test
    void promptListsItemsLanguageAndFocus() {
        FakeChatModel model = new FakeChatModel().respond("{\"clusters\":[{\"headline\":\"x\",\"itemIds\":[3],\"importance\":3}]}");
        ranker.rank(model, "Daily", "it", "Prioritise AI news", items);
        String prompt = model.userMessage(0);
        assertTrue(prompt.startsWith("TASK: RANK"));
        assertTrue(prompt.contains("[id=1] Alpha"));
        assertTrue(prompt.contains("Italian"));
        assertTrue(prompt.contains("Prioritise AI news"));
    }

    @Test
    void repairsInvalidJsonOnce() {
        FakeChatModel model = new FakeChatModel().respond("I think the clusters are...",
                "{\"clusters\":[{\"headline\":\"x\",\"itemIds\":[1],\"importance\":3}]}");
        Selection s = ranker.rank(model, "Daily", "en", null, items);
        assertEquals(1, s.clusters().size());
        assertEquals(2, model.requests.size());
        assertTrue(model.userMessage(1).contains("could not be parsed as JSON"));
    }

    @Test
    void failsAfterTwoInvalidReplies() {
        FakeChatModel model = new FakeChatModel().respond("nope", "still nope");
        assertThrows(GenerationException.class, () -> ranker.rank(model, "Daily", "en", null, items));
    }

    @Test
    void failsWhenNoClusterReferencesCandidates() {
        FakeChatModel model = new FakeChatModel().respond("{\"clusters\":[{\"headline\":\"x\",\"itemIds\":[77],\"importance\":3}]}");
        assertThrows(GenerationException.class, () -> ranker.rank(model, "Daily", "en", null, items));
    }

    @Test
    void blankHeadlineFallsBackToFirstItemTitle() {
        FakeChatModel model = new FakeChatModel().respond("{\"clusters\":[{\"headline\":\"\",\"itemIds\":[3],\"importance\":3}]}");
        assertEquals("Gamma", ranker.rank(model, "Daily", "en", null, items).clusters().get(0).headline());
    }
}
```

- [ ] **Step 6: Run to verify failure**

Run: `bash -lc './mvnw -q test -Dtest=StoryRankerTest'`
Expected: compilation FAIL.

- [ ] **Step 7: Implement `Prompts` (rank part) and `StoryRanker`**

```java
package org.roncax.podcaster.generation;

import java.util.List;
import java.util.Locale;

/** Prompt templates. The first line of every prompt is "TASK: <NAME>". */
public final class Prompts {
    private Prompts() {}

    public static String languageName(String tag) {
        String name = Locale.forLanguageTag(tag).getDisplayLanguage(Locale.ENGLISH);
        return name.isBlank() ? tag : name;
    }

    static String focusLine(String focus) {
        return focus == null || focus.isBlank() ? "" : "Editorial focus from the show owner: " + focus.trim();
    }

    public static String rank(String showName, String language, String focus, List<String> itemLines) {
        return """
                TASK: RANK
                You are the editor of the news podcast "%s".
                Below are news items collected for the next episode. Group items that report the same story into clusters, then rate each cluster's importance for the listener from 1 (minor) to 10 (major).
                %s
                Rules:
                - Use only item ids from the list; an item belongs to at most one cluster.
                - Leave out items that are not news (ads, promotions, games, horoscopes).
                - Write every headline in %s.
                Return ONLY a JSON object, no prose, with this shape:
                {"clusters":[{"headline":"...","itemIds":[1,2],"importance":7}]}

                ITEMS:
                %s
                """.formatted(showName, focusLine(focus), languageName(language), String.join("\n", itemLines));
    }
}
```

```java
package org.roncax.podcaster.generation;

import dev.langchain4j.model.chat.ChatModel;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.*;
import org.roncax.podcaster.domain.Cluster;
import org.roncax.podcaster.domain.Item;
import org.roncax.podcaster.domain.Selection;
import org.roncax.podcaster.llm.GenerationException;
import org.roncax.podcaster.llm.JsonChat;

@ApplicationScoped
public class StoryRanker {
    private static final int SNIPPET_CHARS = 500;

    public Selection rank(ChatModel model, String showName, String language, String focusPrompt, List<Item> candidates) {
        List<String> lines = candidates.stream()
                .map(i -> "[id=" + i.id + "] " + i.title + " — " + snippet(i.bestText()))
                .toList();
        Selection raw = JsonChat.ask(model, Prompts.rank(showName, language, focusPrompt, lines), Selection.class);
        return sanitize(raw, candidates);
    }

    static Selection sanitize(Selection raw, List<Item> candidates) {
        Map<Long, Item> byId = new HashMap<>();
        candidates.forEach(i -> byId.put(i.id, i));
        Set<Long> used = new HashSet<>();
        List<Cluster> clusters = new ArrayList<>();
        if (raw != null && raw.clusters() != null) {
            for (Cluster c : raw.clusters()) {
                if (c == null || c.itemIds() == null) continue;
                List<Long> ids = c.itemIds().stream()
                        .filter(Objects::nonNull)
                        .filter(byId::containsKey)
                        .filter(used::add)
                        .toList();
                if (ids.isEmpty()) continue;
                String headline = c.headline() == null || c.headline().isBlank() ? byId.get(ids.get(0)).title : c.headline().trim();
                int importance = Math.max(1, Math.min(10, c.importance()));
                clusters.add(new Cluster(headline, ids, importance));
            }
        }
        if (clusters.isEmpty()) throw new GenerationException("Ranker returned no usable clusters");
        clusters.sort(Comparator.comparingInt(Cluster::importance).reversed());
        return new Selection(clusters);
    }

    private static String snippet(String text) {
        String s = text.replaceAll("\\s+", " ").trim();
        return s.length() > SNIPPET_CHARS ? s.substring(0, SNIPPET_CHARS) + "…" : s;
    }
}
```

- [ ] **Step 8: Run tests**

Run: `bash -lc './mvnw -q test -Dtest=LlmTextTest,StoryRankerTest'`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "Add LLM JSON handling and story ranking

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01LbcoMupq1VXnfzdqroFmyp"
```

---

### Task 9: Outline planning, TTS text normalization and chunking

Pure functions, no Quarkus.

**Files:**
- Create: `src/main/java/org/roncax/podcaster/generation/{OutlinePlanner,TtsTextNormalizer,ScriptChunker,TtsChunk}.java`
- Test: `src/test/java/org/roncax/podcaster/generation/{OutlinePlannerTest,TtsTextNormalizerTest,ScriptChunkerTest}.java`

**Interfaces:**
- Consumes: `Selection`, `Cluster`, `Outline`, `OutlineSegment` (Task 2); `LlmText.clean` (Task 8); `PodcasterConfig` (Task 1).
- Produces:
  - `OutlinePlanner#plan(Selection, int targetMinutes, double wordsPerMinute) → Outline`; public constructor `OutlinePlanner(int introOutroWords, int minSegmentWords, int maxSegmentWords)`.
  - `TtsTextNormalizer.normalize(String text, String language) → String` — paragraphs separated by exactly one blank line, no markdown/URLs, symbols spelled out for `it`/other.
  - `record TtsChunk(int index, String text, Duration pauseAfter)`.
  - `ScriptChunker.chunk(List<String> parts, int maxChars, Duration chunkPause, Duration segmentPause) → List<TtsChunk>` — chunk boundaries at paragraph ends and sentence ends; last chunk of each part gets `segmentPause`.

- [ ] **Step 1: Write the failing tests**

```java
package org.roncax.podcaster.generation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.domain.Cluster;
import org.roncax.podcaster.domain.Outline;
import org.roncax.podcaster.domain.OutlineSegment;
import org.roncax.podcaster.domain.Selection;

class OutlinePlannerTest {
    OutlinePlanner planner = new OutlinePlanner(200, 250, 900);

    static Cluster c(String h, int importance, long id) { return new Cluster(h, List.of(id), importance); }

    @Test
    void fewClustersGiveShorterEpisodeWithoutPadding() {
        Outline o = planner.plan(new Selection(List.of(c("A", 9, 1), c("B", 6, 2), c("C", 3, 3))), 20, 150);
        assertEquals(List.of(900, 900, 467), o.segments().stream().map(OutlineSegment::words).toList());
        assertEquals(200 + 900 + 900 + 467, o.totalWords());
    }

    @Test
    void manyClustersAreCappedByMinimumSegmentLength() {
        List<Cluster> clusters = IntStream.range(0, 20).mapToObj(i -> c("S" + i, 5, i)).toList();
        Outline o = planner.plan(new Selection(clusters), 20, 150);
        assertEquals(11, o.segments().size());
        assertTrue(o.segments().stream().allMatch(s -> s.words() >= 250));
        assertEquals("S0", o.segments().get(0).headline());
        assertTrue(Math.abs(o.totalWords() - 3000) <= 20, "total " + o.totalWords());
    }

    @Test
    void singleClusterIsCappedAtMaximum() {
        Outline o = planner.plan(new Selection(List.of(c("Only", 10, 1))), 20, 150);
        assertEquals(1, o.segments().size());
        assertEquals(900, o.segments().get(0).words());
        assertEquals(List.of(1L), o.itemIds());
    }

    @Test
    void wordsPerMinuteScalesBudget() {
        List<Cluster> clusters = IntStream.range(0, 20).mapToObj(i -> c("S" + i, 5, i)).toList();
        Outline slow = planner.plan(new Selection(clusters), 20, 120);
        Outline fast = planner.plan(new Selection(clusters), 20, 180);
        assertTrue(fast.totalWords() > slow.totalWords());
    }
}
```

```java
package org.roncax.podcaster.generation;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TtsTextNormalizerTest {
    @Test
    void stripsMarkdownLinksAndUrls() {
        String in = "## Titolo\n**Grassetto** e *corsivo* con [un link](http://x.com) e https://example.com/a?b=1 fine.";
        assertEquals("Titolo Grassetto e corsivo con un link e fine.", TtsTextNormalizer.normalize(in, "it"));
    }

    @Test
    void spellsSymbolsInItalian() {
        assertEquals("Il 45 per cento paga 30 euro e altro",
                TtsTextNormalizer.normalize("Il 45% paga €30 & altro", "it"));
    }

    @Test
    void spellsSymbolsInEnglish() {
        assertEquals("Up 5 percent at 10 dollars and more",
                TtsTextNormalizer.normalize("Up 5% at $10 & more", "en"));
    }

    @Test
    void keepsParagraphsAndJoinsWrappedLines() {
        assertEquals("Line one still one.\n\nSecond.",
                TtsTextNormalizer.normalize("Line one\nstill one.\n\n\n\n  Second.  ", "en"));
    }

    @Test
    void removesBulletsAndThinking() {
        assertEquals("first second", TtsTextNormalizer.normalize("<think>plan</think>- first\n- second", "en"));
    }
}
```

```java
package org.roncax.podcaster.generation;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ScriptChunkerTest {
    static final Duration CHUNK = Duration.ofMillis(400);
    static final Duration SEGMENT = Duration.ofMillis(1200);

    @Test
    void shortPartsBecomeOneChunkEach() {
        List<TtsChunk> chunks = ScriptChunker.chunk(List.of("Intro.", "Body one. Body two.", "Outro."), 500, CHUNK, SEGMENT);
        assertEquals(List.of("Intro.", "Body one. Body two.", "Outro."), chunks.stream().map(TtsChunk::text).toList());
        assertTrue(chunks.stream().allMatch(c -> c.pauseAfter().equals(SEGMENT)));
        assertEquals(List.of(0, 1, 2), chunks.stream().map(TtsChunk::index).toList());
    }

    @Test
    void packsSentencesUpToLimitAndPreservesText() {
        String paragraph = IntStream.range(0, 10)
                .mapToObj(i -> "Sentence number " + i + " is here and it is long enough to matter.")
                .collect(Collectors.joining(" "));
        List<TtsChunk> chunks = ScriptChunker.chunk(List.of(paragraph), 200, CHUNK, SEGMENT);
        assertTrue(chunks.size() > 1);
        assertTrue(chunks.stream().allMatch(c -> c.text().length() <= 200));
        assertEquals(paragraph, chunks.stream().map(TtsChunk::text).collect(Collectors.joining(" ")));
        assertEquals(SEGMENT, chunks.get(chunks.size() - 1).pauseAfter());
        assertEquals(CHUNK, chunks.get(0).pauseAfter());
    }

    @Test
    void paragraphBreakStartsNewChunk() {
        assertEquals(2, ScriptChunker.chunk(List.of("A short one.\n\nAnother short."), 500, CHUNK, SEGMENT).size());
    }

    @Test
    void overlongSentenceIsSplitWithoutLosingWords() {
        String sentence = IntStream.range(0, 150).mapToObj(i -> "word" + i + (i % 10 == 9 ? "," : ""))
                .collect(Collectors.joining(" ")) + ".";
        List<TtsChunk> chunks = ScriptChunker.chunk(List.of(sentence), 500, CHUNK, SEGMENT);
        assertTrue(chunks.size() >= 2);
        assertTrue(chunks.stream().allMatch(c -> c.text().length() <= 500));
        assertEquals(sentence, chunks.stream().map(TtsChunk::text).collect(Collectors.joining(" ")));
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `bash -lc './mvnw -q test -Dtest="OutlinePlannerTest,TtsTextNormalizerTest,ScriptChunkerTest"'`
Expected: compilation FAIL.

- [ ] **Step 3: Implement `OutlinePlanner`**

```java
package org.roncax.podcaster.generation;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import org.roncax.podcaster.config.PodcasterConfig;
import org.roncax.podcaster.domain.Cluster;
import org.roncax.podcaster.domain.Outline;
import org.roncax.podcaster.domain.OutlineSegment;
import org.roncax.podcaster.domain.Selection;

/** Deterministic outline: picks the most important clusters and allocates word budgets. */
@ApplicationScoped
public class OutlinePlanner {
    private final int introOutroWords;
    private final int minSegmentWords;
    private final int maxSegmentWords;

    @Inject
    public OutlinePlanner(PodcasterConfig config) {
        this(config.script().introOutroWords(), config.script().minSegmentWords(), config.script().maxSegmentWords());
    }

    public OutlinePlanner(int introOutroWords, int minSegmentWords, int maxSegmentWords) {
        this.introOutroWords = introOutroWords;
        this.minSegmentWords = minSegmentWords;
        this.maxSegmentWords = maxSegmentWords;
    }

    public Outline plan(Selection selection, int targetMinutes, double wordsPerMinute) {
        int total = (int) Math.round(targetMinutes * wordsPerMinute);
        int body = Math.max(minSegmentWords, total - introOutroWords);
        int maxCount = Math.max(1, body / minSegmentWords);
        List<Cluster> chosen = selection.clusters().stream().limit(maxCount).toList();
        double weightSum = chosen.stream().mapToInt(c -> Math.max(1, c.importance())).sum();
        List<OutlineSegment> segments = new ArrayList<>();
        int sum = 0;
        for (Cluster c : chosen) {
            int words = (int) Math.round(body * Math.max(1, c.importance()) / weightSum);
            words = Math.max(minSegmentWords, Math.min(maxSegmentWords, words));
            segments.add(new OutlineSegment(c.headline(), c.itemIds(), words));
            sum += words;
        }
        return new Outline(introOutroWords + sum, segments);
    }
}
```

- [ ] **Step 4: Implement `TtsTextNormalizer`**

```java
package org.roncax.podcaster.generation;

import java.util.Locale;
import java.util.regex.Pattern;
import org.roncax.podcaster.llm.LlmText;

/** Makes LLM prose safe to read aloud: no markdown, no URLs, symbols spelled out. */
public final class TtsTextNormalizer {
    private static final Pattern MD_LINK = Pattern.compile("\\[([^\\]]+)]\\([^)]*\\)");
    private static final Pattern URL = Pattern.compile("(https?://\\S+|www\\.\\S+)");
    private static final Pattern HEADING = Pattern.compile("(?m)^\\s{0,3}#{1,6}\\s*");
    private static final Pattern BULLET = Pattern.compile("(?m)^\\s*(?:[-*•]|\\d+[.)])\\s+");
    private static final Pattern EMPHASIS = Pattern.compile("\\*{1,3}([^*\\n]+)\\*{1,3}");
    private static final Pattern EURO_FIRST = Pattern.compile("€\\s?(\\d[\\d.,]*)");
    private static final Pattern DOLLAR_FIRST = Pattern.compile("\\$\\s?(\\d[\\d.,]*)");

    private TtsTextNormalizer() {}

    public static String normalize(String text, String language) {
        if (text == null) return "";
        boolean it = language != null && language.toLowerCase(Locale.ROOT).startsWith("it");
        String s = LlmText.clean(text);
        s = MD_LINK.matcher(s).replaceAll("$1");
        s = URL.matcher(s).replaceAll("");
        s = HEADING.matcher(s).replaceAll("");
        s = BULLET.matcher(s).replaceAll("");
        s = EMPHASIS.matcher(s).replaceAll("$1");
        s = s.replace("`", "");
        s = EURO_FIRST.matcher(s).replaceAll("$1 euro");
        s = DOLLAR_FIRST.matcher(s).replaceAll(it ? "$1 dollari" : "$1 dollars");
        s = s.replace("€", " euro")
             .replace("%", it ? " per cento" : " percent")
             .replace("&", it ? " e " : " and ")
             .replace("$", it ? " dollari" : " dollars");
        s = s.replaceAll("[ \\t\\x0B\\f\\r]+", " ");
        s = s.replaceAll(" *\\n *", "\n");
        s = s.replaceAll("\\n{3,}", "\n\n");
        s = s.replaceAll("(?<!\\n)\\n(?!\\n)", " ");
        s = s.replaceAll(" {2,}", " ");
        s = s.replaceAll(" ([.,;:!?])", "$1");
        return s.trim();
    }
}
```

Note the last replacement removes the space a deleted URL leaves before punctuation (`"e fine."` in the test comes from `"e https://… fine."` → `"e  fine."` → `"e fine."`).

- [ ] **Step 5: Implement `TtsChunk` and `ScriptChunker`**

```java
package org.roncax.podcaster.generation;

import java.time.Duration;

public record TtsChunk(int index, String text, Duration pauseAfter) {}
```

```java
package org.roncax.podcaster.generation;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Splits script parts into TTS-sized chunks on paragraph and sentence boundaries. */
public final class ScriptChunker {
    private static final Pattern PARAGRAPH = Pattern.compile("\\n\\s*\\n");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?…])\\s+");

    private ScriptChunker() {}

    public static List<TtsChunk> chunk(List<String> parts, int maxChars, Duration chunkPause, Duration segmentPause) {
        List<TtsChunk> chunks = new ArrayList<>();
        for (String part : parts) {
            List<String> texts = new ArrayList<>();
            for (String paragraph : PARAGRAPH.split(part)) {
                String p = paragraph.trim();
                if (p.isEmpty()) continue;
                StringBuilder current = new StringBuilder();
                for (String sentence : SENTENCE_END.split(p)) {
                    for (String piece : splitLong(sentence.trim(), maxChars)) {
                        if (!current.isEmpty() && current.length() + 1 + piece.length() > maxChars) {
                            texts.add(current.toString());
                            current.setLength(0);
                        }
                        if (!current.isEmpty()) current.append(' ');
                        current.append(piece);
                    }
                }
                if (!current.isEmpty()) texts.add(current.toString());
            }
            for (int i = 0; i < texts.size(); i++) {
                Duration pause = i == texts.size() - 1 ? segmentPause : chunkPause;
                chunks.add(new TtsChunk(chunks.size(), texts.get(i), pause));
            }
        }
        return chunks;
    }

    static List<String> splitLong(String sentence, int maxChars) {
        List<String> out = new ArrayList<>();
        String s = sentence;
        while (s.length() > maxChars) {
            int cut = s.lastIndexOf(", ", maxChars - 1);
            if (cut >= maxChars / 2) {
                cut += 1; // keep the comma with the first piece
            } else {
                cut = s.lastIndexOf(' ', maxChars);
                if (cut <= 0) cut = maxChars;
            }
            out.add(s.substring(0, cut).trim());
            s = s.substring(cut).trim();
        }
        if (!s.isEmpty()) out.add(s);
        return out;
    }
}
```

- [ ] **Step 6: Run tests**

Run: `bash -lc './mvnw -q test -Dtest="OutlinePlannerTest,TtsTextNormalizerTest,ScriptChunkerTest"'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "Add outline planning, TTS text normalization and chunking

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01LbcoMupq1VXnfzdqroFmyp"
```

---

### Task 10: Script writer (segments, intro/outro, show notes)

**Files:**
- Modify: `src/main/java/org/roncax/podcaster/generation/Prompts.java` (add `segment`, `framing`)
- Create: `src/main/java/org/roncax/podcaster/generation/{ScriptWriter,Script,Framing}.java`
- Test: `src/test/java/org/roncax/podcaster/generation/ScriptWriterTest.java`

**Interfaces:**
- Consumes: `JsonChat`, `GenerationException` (Task 8), `TtsTextNormalizer` (Task 9), `Show`, `Item`, `Outline`, `OutlineSegment` (Task 2), `FakeChatModel` (Task 7).
- Produces:
  - `record Script(String title, String description, List<String> parts)` with `joined() → String` (parts joined by blank line) and `wordCount() → int`.
  - `record Framing(String title, String description, String intro, String outro)`.
  - `Prompts.segment(String language, String headline, int words, String sources, String previousTail, String focus) → String`; `Prompts.framing(String showName, String language, LocalDate date, List<String> headlines) → String`.
  - `ScriptWriter#write(ChatModel, Show, Outline, Map<Long,Item>, LocalDate) → Script` — parts = `[intro, segment1..n, outro]`, all normalized; description = framing description + source list (`Fonti:` for Italian, `Sources:` otherwise). Public constructor `ScriptWriter(int maxSourceChars)`.

- [ ] **Step 1: Write the failing test**

```java
package org.roncax.podcaster.generation;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.domain.Item;
import org.roncax.podcaster.domain.Outline;
import org.roncax.podcaster.domain.OutlineSegment;
import org.roncax.podcaster.domain.Show;
import org.roncax.podcaster.llm.GenerationException;
import org.roncax.podcaster.support.FakeChatModel;

class ScriptWriterTest {
    static final String FRAMING = "{\"title\":\"Ep\",\"description\":\"Notes.\",\"intro\":\"Welcome.\",\"outro\":\"Bye.\"}";

    static Item item(long id, String text) {
        Item i = new Item();
        i.id = id;
        i.title = "Title " + id;
        i.url = "https://news.example/" + id;
        i.fullText = text;
        return i;
    }

    static Show show() {
        Show s = new Show();
        s.name = "Daily";
        s.language = "it";
        return s;
    }

    Map<Long, Item> items = Map.of(1L, item(1, "Alpha full text."), 2L, item(2, "Beta full text."), 3L, item(3, "Gamma full text."));
    Outline outline = new Outline(900, List.of(
            new OutlineSegment("Story A", List.of(1L, 2L), 400),
            new OutlineSegment("Story B", List.of(3L), 300)));

    @Test
    void writesSegmentsThenFraming() {
        FakeChatModel model = new FakeChatModel().respond(
                "Segment A text. **Bold** words.",
                "<think>hmm</think>Segment B text.",
                FRAMING);

        Script script = new ScriptWriter(12000).write(model, show(), outline, items, LocalDate.of(2026, 10, 6));

        assertEquals(List.of("Welcome.", "Segment A text. Bold words.", "Segment B text.", "Bye."), script.parts());
        assertEquals("Ep", script.title());
        assertTrue(script.description().startsWith("Notes."));
        assertTrue(script.description().contains("Fonti:"));
        assertTrue(script.description().contains("https://news.example/3"));

        String first = model.userMessage(0);
        assertTrue(first.startsWith("TASK: SEGMENT"));
        assertTrue(first.contains("about 400 words"));
        assertTrue(first.contains("Italian"));
        assertTrue(first.contains("Alpha full text.") && first.contains("Beta full text."));
        assertTrue(first.contains("first story"));
        assertTrue(model.userMessage(1).contains("Segment A text."), "second segment gets a transition from the first");
        String framing = model.userMessage(2);
        assertTrue(framing.startsWith("TASK: FRAMING"));
        assertTrue(framing.contains("1. Story A") && framing.contains("2. Story B"));
        assertTrue(framing.contains("ottobre"), "date is localized to the show language");
    }

    @Test
    void emptySegmentFails() {
        FakeChatModel model = new FakeChatModel().respond("   ", "x", FRAMING);
        assertThrows(GenerationException.class,
                () -> new ScriptWriter(12000).write(model, show(), outline, items, LocalDate.now()));
    }

    @Test
    void sourceTextIsTruncatedToBudget() {
        Map<Long, Item> big = Map.of(1L, item(1, "x".repeat(5000)), 2L, item(2, "y"), 3L, item(3, "z"));
        FakeChatModel model = new FakeChatModel().respond("A.", "B.", FRAMING);
        new ScriptWriter(1000).write(model, show(), outline, big, LocalDate.now());
        assertFalse(model.userMessage(0).contains("x".repeat(600)));
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `bash -lc './mvnw -q test -Dtest=ScriptWriterTest'`
Expected: compilation FAIL.

- [ ] **Step 3: Add `segment` and `framing` to `Prompts`**

Add these imports to `Prompts.java`: `java.time.LocalDate`, `java.time.format.DateTimeFormatter`, `java.time.format.FormatStyle`, `java.util.stream.IntStream`. Add the methods:

```java
    public static String segment(String language, String headline, int words, String sources, String previousTail, String focus) {
        String transition = previousTail == null
                ? "This is the first story of the episode."
                : "The previous segment ended with: \"" + previousTail + "\"\nOpen with a short, natural transition from it.";
        return """
                TASK: SEGMENT
                You write one segment of a spoken news podcast in %s. It will be read aloud by a single narrator using text-to-speech.
                Segment topic: %s
                Target length: about %d words.
                Style:
                - Plain spoken prose only: no markdown, lists, headings, URLs, emojis or stage directions.
                - Explain the story clearly for a listener who has not read the articles; name the sources when attributing claims.
                - Write numbers, dates and abbreviations the way a narrator would say them.
                - Do not greet the listener or close the episode: this segment sits in the middle of the episode.
                %s
                %s
                SOURCES:
                %s

                Write the segment now, in %s.
                """.formatted(languageName(language), headline, words, transition, focusLine(focus), sources, languageName(language));
    }

    public static String framing(String showName, String language, LocalDate date, List<String> headlines) {
        String dateText = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(Locale.forLanguageTag(language)));
        String stories = String.join("\n", IntStream.range(0, headlines.size())
                .mapToObj(i -> (i + 1) + ". " + headlines.get(i)).toList());
        return """
                TASK: FRAMING
                You write the opening and closing of an episode of the spoken news podcast "%s", in %s, read by a single narrator using text-to-speech. Episode date: %s.
                The episode covers these stories, in order:
                %s

                Return ONLY a JSON object, no prose, with these fields:
                - "title": short episode title, at most 80 characters
                - "description": 2-3 sentences of show notes
                - "intro": spoken opening of 80-120 words that greets the listener, says the date and previews the stories
                - "outro": spoken closing of 50-80 words
                intro and outro are plain spoken prose: no markdown, URLs or emojis.
                {"title":"...","description":"...","intro":"...","outro":"..."}
                """.formatted(showName, languageName(language), dateText, stories);
    }
```

- [ ] **Step 4: Implement `Script`, `Framing`, `ScriptWriter`**

```java
package org.roncax.podcaster.generation;

import java.util.List;

public record Script(String title, String description, List<String> parts) {
    public String joined() {
        return String.join("\n\n", parts);
    }

    public int wordCount() {
        return parts.stream().mapToInt(p -> p.isBlank() ? 0 : p.trim().split("\\s+").length).sum();
    }
}
```

```java
package org.roncax.podcaster.generation;

public record Framing(String title, String description, String intro, String outro) {}
```

```java
package org.roncax.podcaster.generation;

import dev.langchain4j.model.chat.ChatModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.util.*;
import org.roncax.podcaster.config.PodcasterConfig;
import org.roncax.podcaster.domain.Item;
import org.roncax.podcaster.domain.Outline;
import org.roncax.podcaster.domain.OutlineSegment;
import org.roncax.podcaster.domain.Show;
import org.roncax.podcaster.llm.GenerationException;
import org.roncax.podcaster.llm.JsonChat;

@ApplicationScoped
public class ScriptWriter {
    private static final int TAIL_CHARS = 300;
    private final int maxSourceChars;

    @Inject
    public ScriptWriter(PodcasterConfig config) {
        this(config.script().maxSourceChars());
    }

    public ScriptWriter(int maxSourceChars) {
        this.maxSourceChars = maxSourceChars;
    }

    public Script write(ChatModel model, Show show, Outline outline, Map<Long, Item> items, LocalDate date) {
        List<String> segments = new ArrayList<>();
        String previousTail = null;
        for (OutlineSegment seg : outline.segments()) {
            List<Item> sourceItems = seg.itemIds().stream().map(items::get).filter(Objects::nonNull).toList();
            String prompt = Prompts.segment(show.language, seg.headline(), seg.words(), sources(sourceItems), previousTail, show.focusPrompt);
            String text = TtsTextNormalizer.normalize(model.chat(prompt), show.language);
            if (text.isBlank()) throw new GenerationException("Model returned an empty segment for '" + seg.headline() + "'");
            segments.add(text);
            previousTail = tail(text);
        }
        List<String> headlines = outline.segments().stream().map(OutlineSegment::headline).toList();
        Framing framing = JsonChat.ask(model, Prompts.framing(show.name, show.language, date, headlines), Framing.class);
        String intro = TtsTextNormalizer.normalize(framing.intro(), show.language);
        String outro = TtsTextNormalizer.normalize(framing.outro(), show.language);
        if (intro.isBlank() || outro.isBlank()) throw new GenerationException("Model returned an empty intro or outro");
        String title = framing.title() == null || framing.title().isBlank() ? show.name + " – " + date : framing.title().trim();

        List<String> parts = new ArrayList<>();
        parts.add(intro);
        parts.addAll(segments);
        parts.add(outro);
        return new Script(title, showNotes(framing.description(), show.language, outline, items), parts);
    }

    String sources(List<Item> sourceItems) {
        int budget = maxSourceChars / Math.max(1, sourceItems.size());
        List<String> blocks = new ArrayList<>();
        for (Item item : sourceItems) {
            String text = item.bestText();
            if (text.length() > budget) text = text.substring(0, budget) + "…";
            blocks.add("TITLE: " + item.title + "\nURL: " + item.url + "\nTEXT:\n" + text);
        }
        return String.join("\n\n---\n\n", blocks);
    }

    static String tail(String text) {
        if (text.length() <= TAIL_CHARS) return text;
        String t = text.substring(text.length() - TAIL_CHARS);
        int space = t.indexOf(' ');
        return space >= 0 ? t.substring(space + 1) : t;
    }

    static String showNotes(String description, String language, Outline outline, Map<Long, Item> items) {
        String label = language != null && language.startsWith("it") ? "Fonti" : "Sources";
        StringBuilder sb = new StringBuilder(description == null ? "" : description.trim());
        sb.append("\n\n").append(label).append(":\n");
        for (Long id : outline.itemIds()) {
            Item item = items.get(id);
            if (item != null) sb.append("- ").append(item.title).append(" — ").append(item.url).append('\n');
        }
        return sb.toString().trim();
    }
}
```

- [ ] **Step 5: Run tests**

Run: `bash -lc './mvnw -q test -Dtest=ScriptWriterTest,StoryRankerTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Add script writer with segment, intro/outro and show notes

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01LbcoMupq1VXnfzdqroFmyp"
```

---
### Task 11: TTS — Piper client, WAV assembly, MP3 encoding, voice calibration

**Files:**
- Create: `src/main/java/org/roncax/podcaster/tts/{TtsEngine,VoiceConfig,PiperHttpTtsEngine,Wav,AudioAssembler,AssembledAudio,Mp3Tags,VoiceCalibrationService}.java`
- Test: `src/test/java/org/roncax/podcaster/tts/{PiperHttpTtsEngineTest,WavTest,AudioAssemblerTest,VoiceCalibrationServiceTest}.java`

**Interfaces:**
- Consumes: `Retries`, `RetryableException` (Task 3); `PodcasterConfig` (Task 1); `VoiceCalibration` (Task 2); `TestAudio` (Task 1).
- Produces:
  - `record VoiceConfig(String voiceId, double lengthScale)`.
  - `interface TtsEngine { byte[] synthesize(String text, VoiceConfig voice) throws Exception; Set<String> voices() throws Exception; }`
  - `PiperHttpTtsEngine` (public constructor `(String baseUrl, int attempts, Duration retryDelay)`): `POST {base}/synthesize`; retries 5xx and connection errors; throws `IllegalStateException` for other non-200 statuses and non-WAV bodies.
  - `record Wav(int sampleRate, int channels, int bitsPerSample, byte[] pcm)` with `parse(byte[])`, `looksLikeWav(byte[])`, `header(int sampleRate, int channels, int bits, long pcmLength) → byte[]`, `blockAlign()`, `bytesPerSecond()`, `sameFormat(Wav)`.
  - `record Mp3Tags(String title, String artist, String album, String date)`; `record AssembledAudio(Path file, double durationSeconds, long sizeBytes)`.
  - `AudioAssembler#assemble(List<Path> wavChunks, List<Duration> pausesAfter, Path outMp3, Mp3Tags) → AssembledAudio` (public constructor `(String ffmpeg, String bitrate)`).
  - `VoiceCalibrationService#wordsPerMinute(String voiceId, double lengthScale) → double`, `#record(String voiceId, double lengthScale, int words, double durationSeconds) → double` (new wpm).

- [ ] **Step 1: Write the failing `WavTest` and `PiperHttpTtsEngineTest`**

```java
package org.roncax.podcaster.tts;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.roncax.podcaster.support.TestAudio;

class WavTest {
    @Test
    void parsesPcmWav() {
        Wav wav = Wav.parse(TestAudio.sineWav(1.0));
        assertEquals(22050, wav.sampleRate());
        assertEquals(1, wav.channels());
        assertEquals(16, wav.bitsPerSample());
        assertEquals(44100, wav.pcm().length);
        assertEquals(44100, wav.bytesPerSecond());
    }

    @Test
    void headerRoundTrips() {
        byte[] pcm = new byte[400];
        byte[] header = Wav.header(16000, 1, 16, pcm.length);
        byte[] all = new byte[header.length + pcm.length];
        System.arraycopy(header, 0, all, 0, header.length);
        Wav wav = Wav.parse(all);
        assertEquals(16000, wav.sampleRate());
        assertEquals(400, wav.pcm().length);
    }

    @Test
    void detectsNonWav() {
        assertFalse(Wav.looksLikeWav("<html>error</html>".getBytes()));
        assertFalse(Wav.looksLikeWav(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> Wav.parse("<html>".getBytes()));
    }
}
```

```java
package org.roncax.podcaster.tts;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.*;
import org.roncax.podcaster.support.TestAudio;

class PiperHttpTtsEngineTest {
    static WireMockServer wm;
    PiperHttpTtsEngine engine;

    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }

    @BeforeEach
    void reset() {
        wm.resetAll();
        engine = new PiperHttpTtsEngine(wm.baseUrl() + "/", 3, Duration.ofMillis(5));
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
    void rejectsNonWavBody() {
        wm.stubFor(post("/synthesize").willReturn(aResponse().withStatus(200).withBody("<html>oops</html>")));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> engine.synthesize("x", new VoiceConfig("v", 1.0)));
        assertTrue(ex.getMessage().contains("non-WAV"), ex.getMessage());
    }

    @Test
    void clientErrorsAreNotRetried() {
        wm.stubFor(post("/synthesize").willReturn(aResponse().withStatus(400).withBody("bad voice")));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> engine.synthesize("x", new VoiceConfig("missing", 1.0)));
        assertTrue(ex.getMessage().contains("400"));
        wm.verify(1, postRequestedFor(urlEqualTo("/synthesize")));
    }

    @Test
    void listsVoices() throws Exception {
        wm.stubFor(get("/voices").willReturn(okJson("{\"it_IT-paola-medium\":{\"x\":1},\"en_US-lessac-medium\":{}}")));
        assertEquals(Set.of("it_IT-paola-medium", "en_US-lessac-medium"), engine.voices());
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `bash -lc './mvnw -q test -Dtest="WavTest,PiperHttpTtsEngineTest"'`
Expected: compilation FAIL.

- [ ] **Step 3: Implement `VoiceConfig`, `TtsEngine`, `Wav`**

```java
package org.roncax.podcaster.tts;

public record VoiceConfig(String voiceId, double lengthScale) {}
```

```java
package org.roncax.podcaster.tts;

import java.util.Set;

public interface TtsEngine {
    /** Returns a PCM WAV file. */
    byte[] synthesize(String text, VoiceConfig voice) throws Exception;

    Set<String> voices() throws Exception;
}
```

```java
package org.roncax.podcaster.tts;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public record Wav(int sampleRate, int channels, int bitsPerSample, byte[] pcm) {

    public static boolean looksLikeWav(byte[] b) {
        return b != null && b.length >= 12
                && new String(b, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                && new String(b, 8, 4, StandardCharsets.US_ASCII).equals("WAVE");
    }

    public static Wav parse(byte[] bytes) {
        if (!looksLikeWav(bytes)) throw new IllegalArgumentException("Not a WAV file");
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        Integer rate = null;
        int channels = 0;
        int bits = 0;
        byte[] pcm = null;
        int pos = 12;
        while (pos + 8 <= bytes.length) {
            String id = new String(bytes, pos, 4, StandardCharsets.US_ASCII);
            long size = Integer.toUnsignedLong(buf.getInt(pos + 4));
            int start = pos + 8;
            if (id.equals("fmt ")) {
                int format = buf.getShort(start) & 0xFFFF;
                if (format != 1) throw new IllegalArgumentException("Only PCM WAV is supported (format " + format + ")");
                channels = buf.getShort(start + 2);
                rate = buf.getInt(start + 4);
                bits = buf.getShort(start + 14);
            } else if (id.equals("data")) {
                long available = bytes.length - start;
                int len = (int) (size == 0 || size > available ? available : size);
                pcm = Arrays.copyOfRange(bytes, start, start + len);
                break;
            }
            pos = (int) (start + size + (size & 1));
        }
        if (rate == null || pcm == null) throw new IllegalArgumentException("WAV is missing fmt or data chunk");
        return new Wav(rate, channels, bits, pcm);
    }

    public static byte[] header(int sampleRate, int channels, int bits, long pcmLength) {
        int blockAlign = channels * bits / 8;
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt((int) (36 + pcmLength))
                .put("WAVE".getBytes(StandardCharsets.US_ASCII))
                .put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) channels)
                .putInt(sampleRate).putInt(sampleRate * blockAlign).putShort((short) blockAlign).putShort((short) bits)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt((int) pcmLength)
                .array();
    }

    public int blockAlign() { return channels * bitsPerSample / 8; }

    public int bytesPerSecond() { return sampleRate * blockAlign(); }

    public boolean sameFormat(Wav other) {
        return sampleRate == other.sampleRate && channels == other.channels && bitsPerSample == other.bitsPerSample;
    }
}
```

- [ ] **Step 4: Implement `PiperHttpTtsEngine`**

```java
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
```

- [ ] **Step 5: Run tests**

Run: `bash -lc './mvnw -q test -Dtest="WavTest,PiperHttpTtsEngineTest"'`
Expected: PASS.

- [ ] **Step 6: Write the failing `AudioAssemblerTest` (requires ffmpeg from Task 0)**

```java
package org.roncax.podcaster.tts;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roncax.podcaster.support.TestAudio;

class AudioAssemblerTest {
    AudioAssembler assembler = new AudioAssembler("ffmpeg", "64k");

    @Test
    void concatenatesWithPausesAndEncodesMp3(@TempDir Path dir) throws Exception {
        Path a = Files.write(dir.resolve("a.wav"), TestAudio.sineWav(1.0));
        Path b = Files.write(dir.resolve("b.wav"), TestAudio.sineWav(1.0));
        Path out = dir.resolve("episode.mp3");

        AssembledAudio audio = assembler.assemble(List.of(a, b),
                List.of(Duration.ofMillis(500), Duration.ofMillis(500)), out,
                new Mp3Tags("Title", "Podcaster", "Daily", "2026-10-06"));

        assertEquals(3.0, audio.durationSeconds(), 0.01);
        assertTrue(Files.exists(out));
        assertEquals(Files.size(out), audio.sizeBytes());
        byte[] head = Files.readAllBytes(out);
        assertEquals("ID3", new String(head, 0, 3));
        assertFalse(Files.exists(dir.resolve("episode.mp3.wav")), "temporary WAV is removed");
    }

    @Test
    void rejectsMixedFormats(@TempDir Path dir) throws Exception {
        Path a = Files.write(dir.resolve("a.wav"), TestAudio.sineWav(0.2));
        Path b = Files.write(dir.resolve("b.wav"), TestAudio.wav(new byte[3200], 16000, 1, 16));
        assertThrows(IllegalStateException.class, () -> assembler.assemble(List.of(a, b),
                List.of(Duration.ZERO, Duration.ZERO), dir.resolve("x.mp3"), new Mp3Tags("t", "a", "b", "d")));
    }
}
```

- [ ] **Step 7: Implement `Mp3Tags`, `AssembledAudio`, `AudioAssembler`**

```java
package org.roncax.podcaster.tts;

public record Mp3Tags(String title, String artist, String album, String date) {}
```

```java
package org.roncax.podcaster.tts;

import java.nio.file.Path;

public record AssembledAudio(Path file, double durationSeconds, long sizeBytes) {}
```

```java
package org.roncax.podcaster.tts;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.roncax.podcaster.config.PodcasterConfig;

/** Joins WAV chunks with silence, computes exact duration, encodes MP3 with ffmpeg. */
@ApplicationScoped
public class AudioAssembler {
    private final String ffmpeg;
    private final String bitrate;

    @Inject
    public AudioAssembler(PodcasterConfig config) {
        this(config.tts().ffmpeg(), config.tts().bitrate());
    }

    public AudioAssembler(String ffmpeg, String bitrate) {
        this.ffmpeg = ffmpeg;
        this.bitrate = bitrate;
    }

    public AssembledAudio assemble(List<Path> wavChunks, List<Duration> pausesAfter, Path outMp3, Mp3Tags tags)
            throws IOException, InterruptedException {
        if (wavChunks.isEmpty()) throw new IllegalArgumentException("No audio chunks to assemble");
        Wav first = null;
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        for (int i = 0; i < wavChunks.size(); i++) {
            Wav wav = Wav.parse(Files.readAllBytes(wavChunks.get(i)));
            if (first == null) {
                first = wav;
            } else if (!first.sameFormat(wav)) {
                throw new IllegalStateException("Chunk " + wavChunks.get(i).getFileName() + " has a different audio format");
            }
            pcm.writeBytes(wav.pcm());
            long silence = Math.round(pausesAfter.get(i).toMillis() / 1000.0 * first.bytesPerSecond());
            silence -= silence % first.blockAlign();
            pcm.writeBytes(new byte[(int) silence]);
        }
        byte[] data = pcm.toByteArray();
        Path wavFile = outMp3.resolveSibling(outMp3.getFileName() + ".wav");
        try (OutputStream out = Files.newOutputStream(wavFile)) {
            out.write(Wav.header(first.sampleRate(), first.channels(), first.bitsPerSample(), data.length));
            out.write(data);
        }
        double duration = (double) data.length / first.bytesPerSecond();
        try {
            encode(wavFile, outMp3, tags);
        } finally {
            Files.deleteIfExists(wavFile);
        }
        return new AssembledAudio(outMp3, duration, Files.size(outMp3));
    }

    private void encode(Path wav, Path mp3, Mp3Tags tags) throws IOException, InterruptedException {
        List<String> command = List.of(ffmpeg, "-y", "-hide_banner", "-loglevel", "error",
                "-i", wav.toString(), "-ac", "1", "-codec:a", "libmp3lame", "-b:a", bitrate,
                "-id3v2_version", "3",
                "-metadata", "title=" + tags.title(),
                "-metadata", "artist=" + tags.artist(),
                "-metadata", "album=" + tags.album(),
                "-metadata", "date=" + tags.date(),
                mp3.toString());
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(10, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IOException("ffmpeg timed out");
        }
        if (process.exitValue() != 0) throw new IOException("ffmpeg failed (exit " + process.exitValue() + "): " + output);
    }
}
```

- [ ] **Step 8: Write the failing `VoiceCalibrationServiceTest`**

```java
package org.roncax.podcaster.tts;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.support.TestData;
import org.roncax.podcaster.support.WireMockResource;

@QuarkusTest
@WithTestResource(WireMockResource.class)
class VoiceCalibrationServiceTest {
    @Inject VoiceCalibrationService calibration;

    @BeforeEach
    void clean() { TestData.cleanDb(); }

    @Test
    void defaultsThenLearnsFromEpisodes() {
        assertEquals(150.0, calibration.wordsPerMinute("v1", 1.0), 0.001);
        assertEquals(180.0, calibration.record("v1", 1.0, 300, 100), 0.001); // first sample replaces default
        assertEquals(0.7 * 180 + 0.3 * 150, calibration.record("v1", 1.0, 300, 120), 0.001);
        assertEquals(171.0, calibration.wordsPerMinute("v1", 1.0), 0.001);
    }

    @Test
    void lengthScalesAreCalibratedSeparately() {
        calibration.record("v2", 1.0, 300, 100);
        assertEquals(150.0, calibration.wordsPerMinute("v2", 1.2), 0.001);
    }

    @Test
    void ignoresDegenerateSamples() {
        assertEquals(150.0, calibration.record("v3", 1.0, 0, 100), 0.001);
        assertEquals(150.0, calibration.record("v3", 1.0, 100, 0), 0.001);
    }
}
```

- [ ] **Step 9: Implement `VoiceCalibrationService`**

```java
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
```

- [ ] **Step 10: Run all TTS tests**

Run: `bash -lc './mvnw -q test -Dtest="WavTest,PiperHttpTtsEngineTest,AudioAssemblerTest,VoiceCalibrationServiceTest"'`
Expected: PASS.

- [ ] **Step 11: Commit**

```bash
git add -A
git commit -m "Add Piper TTS client, WAV assembly, MP3 encoding and voice calibration

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01LbcoMupq1VXnfzdqroFmyp"
```

---

### Task 12: Publishing — storage, media serving, podcast feed, retention

**Files:**
- Create: `src/main/java/org/roncax/podcaster/publishing/{AudioStorage,LocalAudioStorage,MediaRoutes,PodcastFeedRenderer,FeedResource,RetentionService}.java`
- Test: `src/test/java/org/roncax/podcaster/publishing/PublishingTest.java`

**Interfaces:**
- Consumes: `Show`, `Run`, `Episode` (Task 2); `PodcasterConfig` (Task 1).
- Produces:
  - `interface AudioStorage { void store(String key, Path source) throws IOException; Path resolve(String key); boolean exists(String key); void delete(String key) throws IOException; }` — keys look like `<show-slug>/<episode-id>.mp3`; `store` moves the file.
  - `LocalAudioStorage#root() → Path`.
  - HTTP: `GET /media/{key}` (Vert.x static handler with Range support), `GET /feeds/{slug}.xml` (RSS 2.0 + iTunes tags).
  - `PodcastFeedRenderer#render(Show, List<Episode>, String baseUrl) → String`.
  - `RetentionService#apply(long showId) → int` (number of episodes whose audio was removed).

- [ ] **Step 1: Write the failing test**

```java
package org.roncax.podcaster.publishing;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.domain.*;
import org.roncax.podcaster.support.TestData;
import org.roncax.podcaster.support.WireMockResource;

@QuarkusTest
@WithTestResource(WireMockResource.class)
class PublishingTest {
    @Inject AudioStorage storage;
    @Inject RetentionService retention;

    @BeforeEach
    void clean() { TestData.cleanDb(); }

    private Episode episode(Show show, String title, String audioPath, Instant publishedAt) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Run run = new Run();
            run.showId = show.id;
            run.trigger = RunTrigger.MANUAL;
            run.status = RunStatus.DONE;
            run.stage = RunStage.PUBLISH;
            run.since = Instant.now();
            run.persist();
            Episode e = new Episode();
            e.runId = run.id;
            e.showId = show.id;
            e.title = title;
            e.description = "Notes";
            e.audioPath = audioPath;
            e.sizeBytes = 1234L;
            e.durationSeconds = 61.4;
            e.publishedAt = publishedAt;
            e.persist();
            return e;
        });
    }

    private String storeBytes(String key, int size) throws Exception {
        Path tmp = Files.createTempFile("ep", ".mp3");
        Files.write(tmp, new byte[size]);
        storage.store(key, tmp);
        return key;
    }

    @Test
    void feedListsPublishedEpisodesOnly() {
        Show show = TestData.show("feed1");
        episode(show, "News & Views", "feed1/1.mp3", Instant.now());
        episode(show, "Draft", null, null);

        String xml = given().get("/feeds/feed1.xml").then().statusCode(200)
                .contentType(containsString("rss+xml")).extract().asString();

        assertTrue(xml.contains("News &amp; Views"));
        assertTrue(xml.contains("url=\"http://podcaster.test/media/feed1/1.mp3\""));
        assertTrue(xml.contains("length=\"1234\""));
        assertTrue(xml.contains("<itunes:duration>61</itunes:duration>"));
        assertEquals(1, xml.split("<item>", -1).length - 1);
        assertFalse(xml.contains("Draft"));
    }

    @Test
    void unknownFeedIs404() {
        given().get("/feeds/nope.xml").then().statusCode(404);
    }

    @Test
    void mediaIsServedWithRangeSupport() throws Exception {
        storeBytes("media1/ep.mp3", 100);
        given().get("/media/media1/ep.mp3").then().statusCode(200).header("Content-Length", "100");
        given().header("Range", "bytes=0-9").get("/media/media1/ep.mp3").then().statusCode(206).header("Content-Length", "10");
    }

    @Test
    void storageRejectsPathTraversal() {
        assertThrows(IllegalArgumentException.class, () -> storage.resolve("../etc/passwd"));
    }

    @Test
    void retentionKeepsNewestEpisodes() throws Exception {
        Show show = TestData.show("ret1");
        QuarkusTransaction.requiringNew().run(() -> Show.<Show>findById(show.id).retainEpisodes = 2);
        Instant now = Instant.now();
        Episode oldest = episode(show, "e1", storeBytes("ret1/e1.mp3", 10), now.minus(3, ChronoUnit.DAYS));
        episode(show, "e2", storeBytes("ret1/e2.mp3", 10), now.minus(2, ChronoUnit.DAYS));
        episode(show, "e3", storeBytes("ret1/e3.mp3", 10), now.minus(1, ChronoUnit.DAYS));

        assertEquals(1, retention.apply(show.id));

        Episode reloaded = QuarkusTransaction.requiringNew().call(() -> Episode.<Episode>findById(oldest.id));
        assertNull(reloaded.audioPath);
        assertFalse(storage.exists("ret1/e1.mp3"));
        assertTrue(storage.exists("ret1/e3.mp3"));
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `bash -lc './mvnw -q test -Dtest=PublishingTest'`
Expected: compilation FAIL.

- [ ] **Step 3: Implement storage and media routes**

```java
package org.roncax.podcaster.publishing;

import java.io.IOException;
import java.nio.file.Path;

public interface AudioStorage {
    /** Moves {@code source} into storage under {@code key} (e.g. "daily/42.mp3"). */
    void store(String key, Path source) throws IOException;

    Path resolve(String key);

    boolean exists(String key);

    void delete(String key) throws IOException;
}
```

```java
package org.roncax.podcaster.publishing;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.roncax.podcaster.config.PodcasterConfig;

@ApplicationScoped
public class LocalAudioStorage implements AudioStorage {
    private final Path root;

    @Inject
    public LocalAudioStorage(PodcasterConfig config) {
        this(Path.of(config.storage().root()));
    }

    public LocalAudioStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() { return root; }

    @Override
    public Path resolve(String key) {
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root) || path.equals(root)) throw new IllegalArgumentException("Invalid storage key: " + key);
        return path;
    }

    @Override
    public void store(String key, Path source) throws IOException {
        Path target = resolve(key);
        Files.createDirectories(target.getParent());
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public boolean exists(String key) {
        return Files.exists(resolve(key));
    }

    @Override
    public void delete(String key) throws IOException {
        Files.deleteIfExists(resolve(key));
    }
}
```

```java
package org.roncax.podcaster.publishing;

import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.FileSystemAccess;
import io.vertx.ext.web.handler.StaticHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;

/** Serves stored audio under /media/* with HTTP Range support (needed by podcast players). */
@ApplicationScoped
public class MediaRoutes {
    @Inject LocalAudioStorage storage;

    void init(@Observes Router router) throws IOException {
        Files.createDirectories(storage.root());
        router.route("/media/*").handler(StaticHandler.create(FileSystemAccess.ROOT, storage.root().toString())
                .setCachingEnabled(false)
                .setDirectoryListing(false)
                .setIncludeHidden(false));
    }
}
```

- [ ] **Step 4: Implement feed renderer and resource**

```java
package org.roncax.podcaster.publishing;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.StringWriter;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import org.roncax.podcaster.domain.Episode;
import org.roncax.podcaster.domain.Show;

@ApplicationScoped
public class PodcastFeedRenderer {
    private static final String ITUNES = "http://www.itunes.com/dtds/podcast-1.0.dtd";

    public String render(Show show, List<Episode> episodes, String baseUrl) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        StringWriter out = new StringWriter();
        try {
            XMLStreamWriter w = XMLOutputFactory.newFactory().createXMLStreamWriter(out);
            w.writeStartDocument("UTF-8", "1.0");
            w.writeStartElement("rss");
            w.writeNamespace("itunes", ITUNES);
            w.writeAttribute("version", "2.0");
            w.writeStartElement("channel");
            element(w, "title", show.name);
            element(w, "link", base + "/feeds/" + show.slug + ".xml");
            element(w, "description", show.description == null || show.description.isBlank() ? show.name : show.description);
            element(w, "language", show.language);
            itunes(w, "author", "Podcaster");
            itunes(w, "explicit", "false");
            for (Episode e : episodes) {
                if (e.audioPath == null || e.publishedAt == null) continue;
                w.writeStartElement("item");
                element(w, "title", e.title == null ? "Episode " + e.id : e.title);
                element(w, "description", e.description == null ? "" : e.description);
                w.writeStartElement("guid");
                w.writeAttribute("isPermaLink", "false");
                w.writeCharacters("podcaster-episode-" + e.id);
                w.writeEndElement();
                element(w, "pubDate", DateTimeFormatter.RFC_1123_DATE_TIME.format(e.publishedAt.atOffset(ZoneOffset.UTC)));
                w.writeEmptyElement("enclosure");
                w.writeAttribute("url", base + "/media/" + e.audioPath);
                w.writeAttribute("length", String.valueOf(e.sizeBytes == null ? 0 : e.sizeBytes));
                w.writeAttribute("type", "audio/mpeg");
                itunes(w, "duration", String.valueOf(Math.round(e.durationSeconds == null ? 0 : e.durationSeconds)));
                w.writeEndElement();
            }
            w.writeEndElement();
            w.writeEndElement();
            w.writeEndDocument();
            w.close();
        } catch (XMLStreamException ex) {
            throw new IllegalStateException("Could not render feed", ex);
        }
        return out.toString();
    }

    private static void element(XMLStreamWriter w, String name, String text) throws XMLStreamException {
        w.writeStartElement(name);
        w.writeCharacters(text == null ? "" : text);
        w.writeEndElement();
    }

    private static void itunes(XMLStreamWriter w, String name, String text) throws XMLStreamException {
        w.writeStartElement("itunes", name, ITUNES);
        w.writeCharacters(text);
        w.writeEndElement();
    }
}
```

```java
package org.roncax.podcaster.publishing;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import java.util.List;
import org.roncax.podcaster.config.PodcasterConfig;
import org.roncax.podcaster.domain.Episode;
import org.roncax.podcaster.domain.Show;

@Path("/feeds")
public class FeedResource {
    @Inject PodcastFeedRenderer renderer;
    @Inject PodcasterConfig config;

    @GET
    @Path("/{slug}.xml")
    @Produces("application/rss+xml; charset=UTF-8")
    public String feed(@PathParam("slug") String slug) {
        Show show = Show.findBySlug(slug).orElseThrow(NotFoundException::new);
        List<Episode> episodes = Episode.list(
                "showId = ?1 and publishedAt is not null and audioPath is not null order by publishedAt desc", show.id);
        return renderer.render(show, episodes, config.baseUrl());
    }
}
```

- [ ] **Step 5: Implement `RetentionService`**

```java
package org.roncax.podcaster.publishing;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.List;
import org.jboss.logging.Logger;
import org.roncax.podcaster.domain.Episode;
import org.roncax.podcaster.domain.Show;

@ApplicationScoped
public class RetentionService {
    private static final Logger LOG = Logger.getLogger(RetentionService.class);

    @Inject AudioStorage storage;

    public int apply(long showId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Show show = Show.findById(showId);
            if (show == null) return 0;
            List<Episode> withAudio = Episode.list(
                    "showId = ?1 and audioPath is not null order by publishedAt desc nulls last, id desc", showId);
            int removed = 0;
            for (int i = Math.max(0, show.retainEpisodes); i < withAudio.size(); i++) {
                Episode e = withAudio.get(i);
                try {
                    storage.delete(e.audioPath);
                } catch (IOException ex) {
                    LOG.warnf("Could not delete %s: %s", e.audioPath, ex.getMessage());
                }
                e.audioPath = null;
                removed++;
            }
            return removed;
        });
    }
}
```

- [ ] **Step 6: Run tests**

Run: `bash -lc './mvnw -q test -Dtest=PublishingTest'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "Add audio storage, media serving, podcast feed and retention

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01LbcoMupq1VXnfzdqroFmyp"
```

---

### Task 13: Telegram notifications

**Files:**
- Create: `src/main/java/org/roncax/podcaster/notify/{Notifier,TelegramNotifier}.java`
- Test: `src/test/java/org/roncax/podcaster/notify/TelegramNotifierTest.java`

**Interfaces:**
- Consumes: `Show`, `Run` (Task 2), `PodcasterConfig` (Task 1).
- Produces: `interface Notifier { void runFailed(Show show, Run run, String error); void warning(Show show, String message); }` — implementations never throw. `TelegramNotifier` posts `{"chat_id","text"}` to `{apiUrl}/bot{token}/sendMessage`; disabled (no-op) when token or chat id is missing.

- [ ] **Step 1: Write the failing test**

```java
package org.roncax.podcaster.notify;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.domain.Run;
import org.roncax.podcaster.domain.RunStage;
import org.roncax.podcaster.domain.Show;
import org.roncax.podcaster.support.InjectWireMock;
import org.roncax.podcaster.support.WireMockResource;

@QuarkusTest
@WithTestResource(WireMockResource.class)
class TelegramNotifierTest {
    @InjectWireMock WireMockServer wm;
    @Inject Notifier notifier;

    @BeforeEach
    void reset() { WireMockResource.installDefaults(wm); }

    static Show show() {
        Show s = new Show();
        s.id = 7L;
        s.name = "Daily";
        return s;
    }

    @Test
    void sendsRunFailure() {
        Run run = new Run();
        run.id = 99L;
        run.stage = RunStage.TTS;
        notifier.runFailed(show(), run, "Piper unreachable");
        wm.verify(postRequestedFor(urlEqualTo("/bottest-token/sendMessage"))
                .withRequestBody(matchingJsonPath("$.chat_id", equalTo("42")))
                .withRequestBody(matchingJsonPath("$.text", containing("TTS")))
                .withRequestBody(matchingJsonPath("$.text", containing("Piper unreachable")))
                .withRequestBody(matchingJsonPath("$.text", containing("Daily"))));
    }

    @Test
    void sendsWarning() {
        notifier.warning(show(), "1 of 3 sources failed");
        wm.verify(postRequestedFor(urlEqualTo("/bottest-token/sendMessage"))
                .withRequestBody(matchingJsonPath("$.text", containing("1 of 3 sources failed"))));
    }

    @Test
    void telegramErrorsAreSwallowed() {
        wm.stubFor(post("/bottest-token/sendMessage").willReturn(serverError()));
        assertDoesNotThrow(() -> notifier.warning(show(), "x"));
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `bash -lc './mvnw -q test -Dtest=TelegramNotifierTest'`
Expected: compilation FAIL.

- [ ] **Step 3: Implement**

```java
package org.roncax.podcaster.notify;

import org.roncax.podcaster.domain.Run;
import org.roncax.podcaster.domain.Show;

/** Out-of-band alerts. Implementations must never throw. */
public interface Notifier {
    void runFailed(Show show, Run run, String error);

    void warning(Show show, String message);
}
```

```java
package org.roncax.podcaster.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.jboss.logging.Logger;
import org.roncax.podcaster.config.PodcasterConfig;
import org.roncax.podcaster.domain.Run;
import org.roncax.podcaster.domain.Show;

@ApplicationScoped
public class TelegramNotifier implements Notifier {
    private static final Logger LOG = Logger.getLogger(TelegramNotifier.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_TEXT = 4000;

    @Inject PodcasterConfig config;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    void onStart(@Observes StartupEvent event) {
        if (!enabled()) LOG.info("Telegram notifications disabled (TELEGRAM_BOT_TOKEN / TELEGRAM_CHAT_ID not set)");
    }

    boolean enabled() {
        return config.telegram().botToken().isPresent() && config.telegram().chatId().isPresent();
    }

    @Override
    public void runFailed(Show show, Run run, String error) {
        send("❌ Podcaster: run #" + run.id + " of \"" + show.name + "\" failed at " + run.stage + "\n"
                + error + "\n" + config.baseUrl() + "/admin/shows/" + show.id);
    }

    @Override
    public void warning(Show show, String message) {
        send("⚠️ Podcaster: \"" + show.name + "\"\n" + message);
    }

    private void send(String text) {
        if (!enabled()) return;
        try {
            String body = MAPPER.writeValueAsString(Map.of(
                    "chat_id", config.telegram().chatId().get(),
                    "text", text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text));
            HttpRequest request = HttpRequest.newBuilder(URI.create(
                            config.telegram().apiUrl() + "/bot" + config.telegram().botToken().get() + "/sendMessage"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) LOG.warnf("Telegram returned HTTP %d: %s", response.statusCode(), response.body());
        } catch (Exception e) {
            LOG.warnf("Telegram notification failed: %s", e.getMessage());
        }
    }
}
```

- [ ] **Step 4: Run tests**

Run: `bash -lc './mvnw -q test -Dtest=TelegramNotifierTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Add Telegram notifier

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01LbcoMupq1VXnfzdqroFmyp"
```

---
### Task 14: Pipeline stages

**Files:**
- Create: `src/main/java/org/roncax/podcaster/runs/{Stage,StageResult,StageException,IngestStage,SelectStage,ScriptStage,TtsStage,PublishStage}.java`
- Create: `src/test/java/org/roncax/podcaster/support/FakeResponses.java`
- Test: `src/test/java/org/roncax/podcaster/runs/SelectStageTest.java`

**Interfaces:**
- Consumes: `IngestionService`, `IngestionReport` (Task 6); `ChatModelRegistry` (Task 7); `StoryRanker` (Task 8); `OutlinePlanner`, `ScriptChunker`, `TtsChunk` (Task 9); `ScriptWriter`, `Script` (Task 10); `TtsEngine`, `VoiceConfig`, `AudioAssembler`, `AssembledAudio`, `Mp3Tags`, `VoiceCalibrationService` (Task 11); `AudioStorage`, `RetentionService` (Task 12); `Notifier` (Task 13); `TestData.run` (Task 2).
- Produces:
  - `interface Stage { RunStage stage(); StageResult execute(Run run) throws Exception; }` — `run` is a detached snapshot; stages open their own short transactions and never hold one across an external call.
  - `enum StageResult { CONTINUE, SKIP }`; `StageException(String)` (unchecked).
  - `TtsStage.workDir(PodcasterConfig, long runId) → Path` (`<work-dir>/run-<id>`; holds `chunk-NNNN.wav` and `episode.mp3`).
  - Stage behavior: INGEST fails when the show has no enabled sources or all fail, warns on partial failure. SELECT returns SKIP when unused candidates since `run.since` are fewer than `show.minItems`, creates the run's `Episode` with `selection`. SCRIPT stores `outline`, `title`, `description`, `scriptParts`, `script`. TTS reuses existing chunk files, writes `episode.mp3`, stores duration/size, records calibration. PUBLISH moves audio to storage key `<slug>/<episodeId>.mp3`, sets `publishedAt`, marks outline items used, applies retention, deletes the work dir.
  - Test: `FakeResponses.pipeline(String prompt) → String` answers RANK (one cluster with every `[id=N]`), SEGMENT and FRAMING prompts.

- [ ] **Step 1: Write `FakeResponses` and the failing `SelectStageTest`**

```java
package org.roncax.podcaster.support;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Canned LLM replies keyed on the "TASK: X" first line of each prompt. */
public final class FakeResponses {
    private static final Pattern ID = Pattern.compile("\\[id=(\\d+)]");

    private FakeResponses() {}

    public static List<Long> ids(String prompt) {
        List<Long> ids = new ArrayList<>();
        Matcher m = ID.matcher(prompt);
        while (m.find()) ids.add(Long.parseLong(m.group(1)));
        return ids;
    }

    public static String pipeline(String prompt) {
        if (prompt.startsWith("TASK: RANK")) {
            return "{\"clusters\":[{\"headline\":\"Top story\",\"itemIds\":" + ids(prompt) + ",\"importance\":8}]}";
        }
        if (prompt.startsWith("TASK: SEGMENT")) return "This is the segment text. It has two sentences.";
        if (prompt.startsWith("TASK: FRAMING")) {
            return "{\"title\":\"Test episode\",\"description\":\"Notes.\",\"intro\":\"Welcome to the show.\",\"outro\":\"Thanks for listening.\"}";
        }
        throw new IllegalStateException("Unexpected prompt: " + prompt.lines().findFirst().orElse(""));
    }
}
```

```java
package org.roncax.podcaster.runs;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.domain.*;
import org.roncax.podcaster.support.*;

@QuarkusTest
@WithTestResource(WireMockResource.class)
class SelectStageTest {
    @Inject SelectStage stage;
    FakeChatModel model;
    Show show;
    Source source;
    Instant since = Instant.now().minus(1, ChronoUnit.HOURS);

    @BeforeEach
    void setup() {
        TestData.cleanDb();
        model = new FakeChatModel().responder(FakeResponses::pipeline);
        FakeChatModelRegistry.install(model);
        show = TestData.show("sel");
        source = TestData.source(show.id, "http://unused/feed");
    }

    private Item item(String url, Instant publishedAt) {
        return TestData.item(show.id, source.id, url, publishedAt);
    }

    @Test
    void storesSelectionOnEpisode() throws Exception {
        Item a = item("http://a", Instant.now());
        Run run = TestData.run(show.id, since);

        assertEquals(StageResult.CONTINUE, stage.execute(run));

        Episode episode = QuarkusTransaction.requiringNew().call(() -> Episode.findByRun(run.id).orElseThrow());
        assertEquals(java.util.List.of(a.id), episode.selection.clusters().get(0).itemIds());
    }

    @Test
    void skipsWhenTooFewItems() throws Exception {
        QuarkusTransaction.requiringNew().run(() -> Show.<Show>findById(show.id).minItems = 3);
        item("http://a", Instant.now());
        item("http://b", Instant.now());
        Run run = TestData.run(show.id, since);

        assertEquals(StageResult.SKIP, stage.execute(run));
        assertTrue(model.requests.isEmpty());
    }

    @Test
    void excludesUsedAndOldItems() throws Exception {
        Item fresh = item("http://fresh", Instant.now());
        item("http://old", Instant.now().minus(2, ChronoUnit.DAYS));
        Item used = item("http://used", Instant.now());
        Run previous = QuarkusTransaction.requiringNew().call(() -> {
            Run r = new Run();
            r.showId = show.id;
            r.trigger = RunTrigger.MANUAL;
            r.status = RunStatus.DONE;
            r.since = since;
            r.persist();
            Episode e = new Episode();
            e.runId = r.id;
            e.showId = show.id;
            e.persist();
            Item.update("usedInEpisodeId = ?1 where id = ?2", e.id, used.id);
            return r;
        });
        Run run = TestData.run(show.id, since);

        stage.execute(run);

        assertEquals(java.util.List.of(fresh.id), FakeResponses.ids(model.userMessage(0)));
    }

    @Test
    void usesFetchedAtWhenNoPublishDate() throws Exception {
        Item undated = item("http://undated", null);
        Run run = TestData.run(show.id, since);

        stage.execute(run);

        assertEquals(java.util.List.of(undated.id), FakeResponses.ids(model.userMessage(0)));
    }

    @Test
    void capsCandidates() throws Exception {
        for (int i = 0; i < 90; i++) item("http://bulk/" + i, Instant.now().minusSeconds(i));
        Run run = TestData.run(show.id, since);

        stage.execute(run);

        assertEquals(80, FakeResponses.ids(model.userMessage(0)).size());
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `bash -lc './mvnw -q test -Dtest=SelectStageTest'`
Expected: compilation FAIL.

- [ ] **Step 3: Implement the stage contract and INGEST/SELECT**

```java
package org.roncax.podcaster.runs;

import org.roncax.podcaster.domain.Run;
import org.roncax.podcaster.domain.RunStage;

/** One step of a run. Stages persist their output so a retry can resume at the failed stage. */
public interface Stage {
    RunStage stage();

    StageResult execute(Run run) throws Exception;
}
```

```java
package org.roncax.podcaster.runs;

public enum StageResult { CONTINUE, SKIP }
```

```java
package org.roncax.podcaster.runs;

public class StageException extends RuntimeException {
    public StageException(String message) { super(message); }
}
```

```java
package org.roncax.podcaster.runs;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.roncax.podcaster.domain.Run;
import org.roncax.podcaster.domain.RunStage;
import org.roncax.podcaster.domain.Show;
import org.roncax.podcaster.ingestion.IngestionReport;
import org.roncax.podcaster.ingestion.IngestionService;
import org.roncax.podcaster.notify.Notifier;

@ApplicationScoped
public class IngestStage implements Stage {
    @Inject IngestionService ingestion;
    @Inject Notifier notifier;

    @Override
    public RunStage stage() { return RunStage.INGEST; }

    @Override
    public StageResult execute(Run run) {
        Show show = QuarkusTransaction.requiringNew().call(() -> Show.<Show>findById(run.showId));
        IngestionReport report = ingestion.ingest(run.showId, run.since);
        if (report.sources() == 0) throw new StageException("Show has no enabled sources");
        if (report.allFailed()) throw new StageException("All sources failed: " + String.join("; ", report.errors()));
        if (!report.errors().isEmpty()) {
            notifier.warning(show, report.errors().size() + " of " + report.sources() + " sources failed:\n"
                    + String.join("\n", report.errors()));
        }
        return StageResult.CONTINUE;
    }
}
```

```java
package org.roncax.podcaster.runs;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.roncax.podcaster.config.PodcasterConfig;
import org.roncax.podcaster.domain.*;
import org.roncax.podcaster.generation.StoryRanker;
import org.roncax.podcaster.llm.ChatModelRegistry;

@ApplicationScoped
public class SelectStage implements Stage {
    @Inject ChatModelRegistry models;
    @Inject StoryRanker ranker;
    @Inject PodcasterConfig config;

    @Override
    public RunStage stage() { return RunStage.SELECT; }

    @Override
    public StageResult execute(Run run) {
        Show show = QuarkusTransaction.requiringNew().call(() -> Show.<Show>findById(run.showId));
        List<Item> candidates = QuarkusTransaction.requiringNew().call(() -> Item.<Item>find(
                        "showId = ?1 and usedInEpisodeId is null and coalesce(publishedAt, fetchedAt) >= ?2 "
                                + "order by coalesce(publishedAt, fetchedAt) desc", show.id, run.since)
                .page(0, config.selection().maxCandidates())
                .list());
        if (candidates.size() < show.minItems) return StageResult.SKIP;

        Selection selection = ranker.rank(models.get(show.effectiveRankerModel()),
                show.name, show.language, show.focusPrompt, candidates);

        QuarkusTransaction.requiringNew().run(() -> {
            Episode episode = Episode.findByRun(run.id).orElseGet(() -> {
                Episode e = new Episode();
                e.runId = run.id;
                e.showId = show.id;
                e.persist();
                return e;
            });
            episode.selection = selection;
        });
        return StageResult.CONTINUE;
    }
}
```

- [ ] **Step 4: Run `SelectStageTest`**

Run: `bash -lc './mvnw -q test -Dtest=SelectStageTest'`
Expected: PASS.

- [ ] **Step 5: Implement SCRIPT, TTS and PUBLISH stages**

These are exercised end-to-end in Task 15's `RunPipelineTest`.

```java
package org.roncax.podcaster.runs;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.roncax.podcaster.domain.*;
import org.roncax.podcaster.generation.OutlinePlanner;
import org.roncax.podcaster.generation.Script;
import org.roncax.podcaster.generation.ScriptWriter;
import org.roncax.podcaster.llm.ChatModelRegistry;
import org.roncax.podcaster.tts.VoiceCalibrationService;

@ApplicationScoped
public class ScriptStage implements Stage {
    @Inject ChatModelRegistry models;
    @Inject OutlinePlanner planner;
    @Inject ScriptWriter writer;
    @Inject VoiceCalibrationService calibration;

    @Override
    public RunStage stage() { return RunStage.SCRIPT; }

    @Override
    public StageResult execute(Run run) {
        Show show = QuarkusTransaction.requiringNew().call(() -> Show.<Show>findById(run.showId));
        Episode episode = QuarkusTransaction.requiringNew().call(() -> Episode.findByRun(run.id)
                .orElseThrow(() -> new StageException("Run " + run.id + " has no selection")));
        double wpm = calibration.wordsPerMinute(show.voiceId, show.lengthScale);
        Outline outline = planner.plan(episode.selection, show.targetDurationMinutes, wpm);
        Map<Long, Item> items = QuarkusTransaction.requiringNew().call(() -> Item.<Item>list("id in ?1", outline.itemIds())
                .stream().collect(Collectors.toMap(i -> i.id, Function.identity())));

        Script script = writer.write(models.get(show.writerModel), show, outline, items, LocalDate.now());

        QuarkusTransaction.requiringNew().run(() -> {
            Episode e = Episode.findById(episode.id);
            e.outline = outline;
            e.title = script.title();
            e.description = script.description();
            e.scriptParts = script.parts();
            e.script = script.joined();
        });
        return StageResult.CONTINUE;
    }
}
```

```java
package org.roncax.podcaster.runs;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.roncax.podcaster.config.PodcasterConfig;
import org.roncax.podcaster.domain.Episode;
import org.roncax.podcaster.domain.Run;
import org.roncax.podcaster.domain.RunStage;
import org.roncax.podcaster.domain.Show;
import org.roncax.podcaster.generation.ScriptChunker;
import org.roncax.podcaster.generation.TtsChunk;
import org.roncax.podcaster.tts.*;

@ApplicationScoped
public class TtsStage implements Stage {
    @Inject TtsEngine tts;
    @Inject AudioAssembler assembler;
    @Inject VoiceCalibrationService calibration;
    @Inject PodcasterConfig config;

    public static Path workDir(PodcasterConfig config, long runId) {
        return Path.of(config.storage().workDir()).toAbsolutePath().resolve("run-" + runId);
    }

    @Override
    public RunStage stage() { return RunStage.TTS; }

    @Override
    public StageResult execute(Run run) throws Exception {
        Show show = QuarkusTransaction.requiringNew().call(() -> Show.<Show>findById(run.showId));
        Episode episode = QuarkusTransaction.requiringNew().call(() -> Episode.findByRun(run.id)
                .orElseThrow(() -> new StageException("Run " + run.id + " has no episode")));
        if (episode.scriptParts == null || episode.scriptParts.isEmpty()) throw new StageException("Episode has no script");

        Path dir = workDir(config, run.id);
        Files.createDirectories(dir);
        PodcasterConfig.Tts cfg = config.tts();
        List<TtsChunk> chunks = ScriptChunker.chunk(episode.scriptParts, cfg.chunkChars(), cfg.chunkPause(), cfg.segmentPause());
        VoiceConfig voice = new VoiceConfig(show.voiceId, show.lengthScale);

        List<Path> files = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, cfg.parallelism()));
        try {
            List<Future<Path>> futures = new ArrayList<>();
            for (TtsChunk chunk : chunks) futures.add(pool.submit(() -> synthesize(dir, chunk, voice)));
            for (Future<Path> future : futures) {
                try {
                    files.add(future.get());
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof Exception ex) throw ex;
                    throw new IllegalStateException(e.getCause());
                }
            }
        } finally {
            pool.shutdownNow();
        }

        AssembledAudio audio = assembler.assemble(files, chunks.stream().map(TtsChunk::pauseAfter).toList(),
                dir.resolve("episode.mp3"),
                new Mp3Tags(episode.title, "Podcaster", show.name, LocalDate.now().toString()));

        QuarkusTransaction.requiringNew().run(() -> {
            Episode e = Episode.findById(episode.id);
            e.durationSeconds = audio.durationSeconds();
            e.sizeBytes = audio.sizeBytes();
        });
        int words = episode.scriptParts.stream().mapToInt(p -> p.isBlank() ? 0 : p.trim().split("\\s+").length).sum();
        calibration.record(show.voiceId, show.lengthScale, words, audio.durationSeconds());
        return StageResult.CONTINUE;
    }

    private Path synthesize(Path dir, TtsChunk chunk, VoiceConfig voice) throws Exception {
        Path file = dir.resolve("chunk-%04d.wav".formatted(chunk.index()));
        if (Files.exists(file) && Files.size(file) > 44) return file; // resume: already synthesized
        byte[] wav = tts.synthesize(chunk.text(), voice);
        Path tmp = dir.resolve(file.getFileName() + ".tmp");
        Files.write(tmp, wav);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return file;
    }
}
```

```java
package org.roncax.podcaster.runs;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.roncax.podcaster.config.PodcasterConfig;
import org.roncax.podcaster.domain.*;
import org.roncax.podcaster.publishing.AudioStorage;
import org.roncax.podcaster.publishing.RetentionService;

@ApplicationScoped
public class PublishStage implements Stage {
    @Inject AudioStorage storage;
    @Inject RetentionService retention;
    @Inject PodcasterConfig config;

    @Override
    public RunStage stage() { return RunStage.PUBLISH; }

    @Override
    public StageResult execute(Run run) throws Exception {
        Show show = QuarkusTransaction.requiringNew().call(() -> Show.<Show>findById(run.showId));
        Episode episode = QuarkusTransaction.requiringNew().call(() -> Episode.findByRun(run.id)
                .orElseThrow(() -> new StageException("Run " + run.id + " has no episode")));
        String key = show.slug + "/" + episode.id + ".mp3";
        Path dir = TtsStage.workDir(config, run.id);
        Path mp3 = dir.resolve("episode.mp3");
        if (Files.exists(mp3)) {
            storage.store(key, mp3);
        } else if (!storage.exists(key)) {
            throw new StageException("No audio to publish for run " + run.id);
        }
        long size = Files.size(storage.resolve(key));

        QuarkusTransaction.requiringNew().run(() -> {
            Episode e = Episode.findById(episode.id);
            e.audioPath = key;
            e.sizeBytes = size;
            e.publishedAt = Instant.now();
            List<Long> used = e.outline == null ? List.of() : e.outline.itemIds();
            if (!used.isEmpty()) Item.update("usedInEpisodeId = ?1 where id in ?2", e.id, used);
        });
        retention.apply(show.id);
        deleteRecursively(dir);
        return StageResult.CONTINUE;
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
```

- [ ] **Step 6: Compile and re-run the stage test**

Run: `bash -lc './mvnw -q test -Dtest=SelectStageTest'`
Expected: PASS (and everything compiles).

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "Add pipeline stages: ingest, select, script, tts, publish

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01LbcoMupq1VXnfzdqroFmyp"
```

---

### Task 15: Run orchestration — launcher, orchestrator, recovery, end-to-end pipeline

**Files:**
- Create: `src/main/java/org/roncax/podcaster/runs/{RunOrchestrator,RunLauncher,RunAlreadyActiveException,RunRecovery}.java`
- Test: `src/test/java/org/roncax/podcaster/runs/RunPipelineTest.java`, `src/test/java/org/roncax/podcaster/runs/RunRecoveryTest.java`

**Interfaces:**
- Consumes: all `Stage` beans (Task 14), `Notifier` (Task 13), `Exceptions.isUniqueViolation` / `Exceptions.message` (Task 2), `FakeResponses` (Task 14).
- Produces:
  - `RunLauncher#launch(long showId, RunTrigger) → long runId` — creates the RUNNING run (since = last DONE run's `startedAt` − 1 h, or now − `firstRunWindow`) and executes it asynchronously; throws `RunAlreadyActiveException` (show already has a RUNNING run) or `jakarta.ws.rs.NotFoundException`.
  - `RunLauncher#retry(long runId)` — only `FAILED` runs; resumes at `run.stage`, `attempt++`; throws `IllegalStateException` for other statuses.
  - `RunAlreadyActiveException(long showId)` (unchecked).
  - `RunOrchestrator#execute(long runId)` — runs stages until DONE/SKIPPED/FAILED; on failure calls `Notifier.runFailed` then marks FAILED with `error = "<STAGE>: <message>"`.
  - `RunRecovery#recover() → int` — marks every RUNNING run FAILED ("Interrupted by restart"); also runs on startup.

- [ ] **Step 1: Write the failing end-to-end test**

```java
package org.roncax.podcaster.runs;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.domain.*;
import org.roncax.podcaster.publishing.AudioStorage;
import org.roncax.podcaster.support.*;

@QuarkusTest
@WithTestResource(WireMockResource.class)
class RunPipelineTest {
    @InjectWireMock WireMockServer wm;
    @Inject RunLauncher launcher;
    @Inject AudioStorage storage;
    FakeChatModel model;
    Show show;

    @BeforeEach
    void setup() {
        TestData.cleanDb();
        WireMockResource.installDefaults(wm);
        model = new FakeChatModel().responder(FakeResponses::pipeline);
        FakeChatModelRegistry.install(model);
        show = TestData.show("pipe");
        TestData.source(show.id, wm.baseUrl() + "/pfeed");
    }

    private void stubFeed(String... paths) {
        String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.now().atOffset(ZoneOffset.UTC));
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>t</title><link>http://x</link><description>d</description>");
        for (String p : paths) {
            sb.append("<item><title>Story ").append(p).append("</title><link>").append(wm.baseUrl()).append(p)
              .append("</link><pubDate>").append(date).append("</pubDate></item>");
            wm.stubFor(get(p).willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/html")
                    .withBody(Fixtures.bytes("ilpost-article.html"))));
        }
        wm.stubFor(get("/pfeed").willReturn(okXml(sb.append("</channel></rss>").toString())));
    }

    @Test
    void producesAndPublishesAnEpisode() {
        stubFeed("/p1", "/p2", "/p3");

        Run run = TestData.awaitRun(launcher.launch(show.id, RunTrigger.MANUAL));

        assertEquals(RunStatus.DONE, run.status, run.error);
        Episode episode = QuarkusTransaction.requiringNew().call(() -> Episode.findByRun(run.id).orElseThrow());
        assertEquals("Test episode", episode.title);
        assertEquals(3, episode.scriptParts.size()); // intro + 1 segment + outro
        assertTrue(episode.durationSeconds > 0);
        assertTrue(storage.exists(episode.audioPath));
        assertEquals("pipe/" + episode.id + ".mp3", episode.audioPath);
        long unused = QuarkusTransaction.requiringNew().call(() -> Item.count("showId = ?1 and usedInEpisodeId is null", show.id));
        assertEquals(0, unused);
        String feed = given().get("/feeds/pipe.xml").then().statusCode(200).extract().asString();
        assertTrue(feed.contains("Test episode"));
        int calibrationSamples = QuarkusTransaction.requiringNew().call(() ->
                VoiceCalibration.<VoiceCalibration>find("voiceId", show.voiceId).firstResult().samples);
        assertEquals(1, calibrationSamples);

        Run second = TestData.awaitRun(launcher.launch(show.id, RunTrigger.MANUAL));
        assertEquals(RunStatus.SKIPPED, second.status, "no new items -> skipped");
    }

    @Test
    void retryResumesAtFailedStageWithoutNewLlmCalls() {
        stubFeed("/p1");
        wm.stubFor(post("/synthesize").willReturn(serviceUnavailable()));

        Run failed = TestData.awaitRun(launcher.launch(show.id, RunTrigger.MANUAL));

        assertEquals(RunStatus.FAILED, failed.status);
        assertEquals(RunStage.TTS, failed.stage);
        assertTrue(failed.error.startsWith("TTS: "), failed.error);
        wm.verify(postRequestedFor(urlEqualTo("/bottest-token/sendMessage"))
                .withRequestBody(matchingJsonPath("$.text", containing("TTS"))));
        int llmCalls = model.requests.size();

        WireMockResource.installDefaults(wm); // Piper is back; feed stubs are gone, so INGEST must not rerun
        launcher.retry(failed.id);
        Run resumed = TestData.awaitRun(failed.id);

        assertEquals(RunStatus.DONE, resumed.status, resumed.error);
        assertEquals(2, resumed.attempt);
        assertEquals(llmCalls, model.requests.size());
    }

    @Test
    void secondLaunchWhileRunningIsRejected() {
        stubFeed("/p1");
        wm.stubFor(post("/synthesize").willReturn(aResponse().withStatus(200).withFixedDelay(1500)
                .withBody(TestAudio.sineWav(0.2))));

        long first = launcher.launch(show.id, RunTrigger.MANUAL);
        assertThrows(RunAlreadyActiveException.class, () -> launcher.launch(show.id, RunTrigger.MANUAL));
        assertEquals(RunStatus.DONE, TestData.awaitRun(first).status);
    }

    @Test
    void emptyFeedSkips() {
        stubFeed();
        Run run = TestData.awaitRun(launcher.launch(show.id, RunTrigger.MANUAL));
        assertEquals(RunStatus.SKIPPED, run.status);
        assertTrue(model.requests.isEmpty());
    }

    @Test
    void onlyFailedRunsCanBeRetried() {
        stubFeed();
        long id = launcher.launch(show.id, RunTrigger.MANUAL);
        TestData.awaitRun(id);
        assertThrows(IllegalStateException.class, () -> launcher.retry(id));
    }
}
```

- [ ] **Step 2: Write the failing `RunRecoveryTest`**

```java
package org.roncax.podcaster.runs;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.domain.*;
import org.roncax.podcaster.support.*;

@QuarkusTest
@WithTestResource(WireMockResource.class)
class RunRecoveryTest {
    @InjectWireMock com.github.tomakehurst.wiremock.WireMockServer wm;
    @Inject RunRecovery recovery;
    @Inject RunLauncher launcher;

    @BeforeEach
    void setup() {
        TestData.cleanDb();
        WireMockResource.installDefaults(wm);
        FakeChatModelRegistry.install(new FakeChatModel().responder(FakeResponses::pipeline));
    }

    @Test
    void interruptedRunIsFailedAndResumesAtItsStage() {
        Show show = TestData.show("rec");
        Source source = TestData.source(show.id, "http://localhost:1/unreachable-feed"); // INGEST would fail
        TestData.item(show.id, source.id, "http://story", Instant.now());
        Run stuck = TestData.run(show.id, Instant.now().minus(1, ChronoUnit.HOURS));
        QuarkusTransaction.requiringNew().run(() -> Run.<Run>findById(stuck.id).stage = RunStage.SELECT);

        assertEquals(1, recovery.recover());
        Run failed = QuarkusTransaction.requiringNew().call(() -> Run.<Run>findById(stuck.id));
        assertEquals(RunStatus.FAILED, failed.status);
        assertEquals("Interrupted by restart", failed.error);

        launcher.retry(stuck.id);
        Run done = TestData.awaitRun(stuck.id);
        assertEquals(RunStatus.DONE, done.status, done.error);
    }
}
```

- [ ] **Step 3: Run to verify failure**

Run: `bash -lc './mvnw -q test -Dtest="RunPipelineTest,RunRecoveryTest"'`
Expected: compilation FAIL.

- [ ] **Step 4: Implement `RunAlreadyActiveException` and `RunOrchestrator`**

```java
package org.roncax.podcaster.runs;

public class RunAlreadyActiveException extends RuntimeException {
    public RunAlreadyActiveException(long showId) {
        super("Show " + showId + " already has a running run");
    }
}
```

```java
package org.roncax.podcaster.runs;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import org.jboss.logging.Logger;
import org.roncax.podcaster.domain.*;
import org.roncax.podcaster.notify.Notifier;
import org.roncax.podcaster.util.Exceptions;

@ApplicationScoped
public class RunOrchestrator {
    private static final Logger LOG = Logger.getLogger(RunOrchestrator.class);

    @Inject Instance<Stage> allStages;
    @Inject Notifier notifier;
    private final Map<RunStage, Stage> stages = new EnumMap<>(RunStage.class);

    @PostConstruct
    void init() {
        allStages.forEach(s -> stages.put(s.stage(), s));
        for (RunStage stage : RunStage.values()) {
            if (!stages.containsKey(stage)) throw new IllegalStateException("No Stage bean for " + stage);
        }
    }

    @ActivateRequestContext
    public void execute(long runId) {
        while (true) {
            Run run = QuarkusTransaction.requiringNew().call(() -> Run.<Run>findById(runId));
            if (run == null || run.status != RunStatus.RUNNING) return;
            StageResult result;
            try {
                LOG.infof("Run %d (show %d): %s", runId, run.showId, run.stage);
                result = stages.get(run.stage).execute(run);
            } catch (Exception e) {
                fail(run, e);
                return;
            }
            if (result == StageResult.SKIP) {
                finish(runId, RunStatus.SKIPPED, null);
                return;
            }
            if (run.stage == RunStage.PUBLISH) {
                finish(runId, RunStatus.DONE, null);
                return;
            }
            RunStage next = run.stage.next();
            QuarkusTransaction.requiringNew().run(() -> {
                Run r = Run.findById(runId);
                if (r != null) r.stage = next;
            });
        }
    }

    private void fail(Run run, Exception e) {
        String error = run.stage + ": " + Exceptions.message(e);
        LOG.errorf(e, "Run %d failed", run.id);
        Show show = QuarkusTransaction.requiringNew().call(() -> Show.<Show>findById(run.showId));
        if (show != null) notifier.runFailed(show, run, error);
        finish(run.id, RunStatus.FAILED, error);
    }

    private void finish(long runId, RunStatus status, String error) {
        QuarkusTransaction.requiringNew().run(() -> {
            Run r = Run.findById(runId);
            if (r == null) return;
            r.status = status;
            r.error = error;
            r.finishedAt = Instant.now();
        });
    }
}
```

- [ ] **Step 5: Implement `RunLauncher` and `RunRecovery`**

```java
package org.roncax.podcaster.runs;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.roncax.podcaster.config.PodcasterConfig;
import org.roncax.podcaster.domain.*;
import org.roncax.podcaster.util.Exceptions;

@ApplicationScoped
public class RunLauncher {
    /** Re-read the hour before the previous run so items published while it ran are not missed (dedupe prevents repeats). */
    static final Duration OVERLAP = Duration.ofHours(1);

    @Inject RunOrchestrator orchestrator;
    @Inject PodcasterConfig config;
    private ExecutorService executor;

    @PostConstruct
    void start() {
        executor = Executors.newFixedThreadPool(Math.max(1, config.runs().workers()), r -> {
            Thread t = new Thread(r, "podcaster-run");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }

    public long launch(long showId, RunTrigger trigger) {
        long runId;
        try {
            runId = QuarkusTransaction.requiringNew().call(() -> {
                Show show = Show.findById(showId);
                if (show == null) throw new NotFoundException("Show " + showId + " not found");
                Run run = new Run();
                run.showId = showId;
                run.trigger = trigger;
                run.since = since(showId);
                run.persistAndFlush();
                return run.id;
            });
        } catch (RuntimeException e) {
            if (Exceptions.isUniqueViolation(e)) throw new RunAlreadyActiveException(showId);
            throw e;
        }
        executor.submit(() -> orchestrator.execute(runId));
        return runId;
    }

    public void retry(long runId) {
        long[] showId = new long[1];
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                Run run = Run.findById(runId);
                if (run == null) throw new NotFoundException("Run " + runId + " not found");
                if (run.status != RunStatus.FAILED) {
                    throw new IllegalStateException("Only failed runs can be retried (run " + runId + " is " + run.status + ")");
                }
                showId[0] = run.showId;
                run.status = RunStatus.RUNNING;
                run.attempt++;
                run.error = null;
                run.finishedAt = null;
                Run.flush();
            });
        } catch (RuntimeException e) {
            if (Exceptions.isUniqueViolation(e)) throw new RunAlreadyActiveException(showId[0]);
            throw e;
        }
        executor.submit(() -> orchestrator.execute(runId));
    }

    private Instant since(long showId) {
        return Run.<Run>find("showId = ?1 and status = ?2 order by startedAt desc", showId, RunStatus.DONE)
                .firstResultOptional()
                .map(r -> r.startedAt.minus(OVERLAP))
                .orElse(Instant.now().minus(config.selection().firstRunWindow()));
    }
}
```

```java
package org.roncax.podcaster.runs;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.time.Instant;
import org.jboss.logging.Logger;
import org.roncax.podcaster.domain.Run;
import org.roncax.podcaster.domain.RunStatus;

@ApplicationScoped
public class RunRecovery {
    private static final Logger LOG = Logger.getLogger(RunRecovery.class);

    void onStart(@Observes StartupEvent event) {
        int recovered = recover();
        if (recovered > 0) LOG.warnf("Marked %d interrupted run(s) as FAILED", recovered);
    }

    public int recover() {
        return QuarkusTransaction.requiringNew().call(() -> Run.update(
                "status = ?1, error = ?2, finishedAt = ?3 where status = ?4",
                RunStatus.FAILED, "Interrupted by restart", Instant.now(), RunStatus.RUNNING));
    }
}
```

- [ ] **Step 6: Run tests**

Run: `bash -lc './mvnw -q test -Dtest="RunPipelineTest,RunRecoveryTest"'`
Expected: PASS.

- [ ] **Step 7: Run the whole suite so far**

Run: `bash -lc './mvnw -q test'`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "Add run orchestration with resumable stages and restart recovery

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01LbcoMupq1VXnfzdqroFmyp"
```

---

### Task 16: Cron scheduling per Show

**Files:**
- Create: `src/main/java/org/roncax/podcaster/runs/{CronValidator,ShowScheduler}.java`
- Test: `src/test/java/org/roncax/podcaster/runs/CronValidatorTest.java`, `src/test/java/org/roncax/podcaster/runs/ShowSchedulerTest.java`

**Interfaces:**
- Consumes: `RunLauncher`, `RunAlreadyActiveException` (Task 15); `Show` (Task 2); Quarkus `io.quarkus.scheduler.Scheduler`; cron-utils (transitive via `quarkus-scheduler`).
- Produces:
  - `CronValidator.validate(String expr) → Optional<String>` (error message; empty when valid or blank).
  - `ShowScheduler#reschedule(Show)` (removes then re-adds job `show-<id>` if enabled with a cron), `#unschedule(long showId)`, `#scheduledJobIds() → Set<String>`; schedules all enabled shows on startup.

- [ ] **Step 1: Write the failing tests**

```java
package org.roncax.podcaster.runs;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CronValidatorTest {
    @Test
    void acceptsUnixCron() {
        assertTrue(CronValidator.validate("0 7 * * *").isEmpty());
        assertTrue(CronValidator.validate("*/30 6-22 * * 1-5").isEmpty());
        assertTrue(CronValidator.validate("").isEmpty());
        assertTrue(CronValidator.validate(null).isEmpty());
    }

    @Test
    void rejectsInvalidCron() {
        assertTrue(CronValidator.validate("0 7 * *").isPresent());
        assertTrue(CronValidator.validate("61 * * * *").isPresent());
        assertTrue(CronValidator.validate("0 0 7 * * ?").isPresent(), "Quartz syntax is not accepted");
    }
}
```

```java
package org.roncax.podcaster.runs;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.domain.Show;
import org.roncax.podcaster.support.TestData;
import org.roncax.podcaster.support.WireMockResource;

@QuarkusTest
@WithTestResource(WireMockResource.class)
class ShowSchedulerTest {
    @Inject ShowScheduler scheduler;

    @BeforeEach
    void clean() { TestData.cleanDb(); }

    @Test
    void schedulesAndUnschedulesShows() {
        Show show = TestData.show("cron");
        show.cron = "0 7 * * *";

        scheduler.reschedule(show);
        assertTrue(scheduler.scheduledJobIds().contains("show-" + show.id));

        show.enabled = false;
        scheduler.reschedule(show);
        assertFalse(scheduler.scheduledJobIds().contains("show-" + show.id));

        show.enabled = true;
        scheduler.reschedule(show);
        scheduler.unschedule(show.id);
        assertFalse(scheduler.scheduledJobIds().contains("show-" + show.id));
    }

    @Test
    void showWithoutCronIsNotScheduled() {
        Show show = TestData.show("nocron");
        scheduler.reschedule(show);
        assertFalse(scheduler.scheduledJobIds().contains("show-" + show.id));
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `bash -lc './mvnw -q test -Dtest="CronValidatorTest,ShowSchedulerTest"'`
Expected: compilation FAIL.

- [ ] **Step 3: Implement**

```java
package org.roncax.podcaster.runs;

import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;
import java.util.Optional;

public final class CronValidator {
    private static final CronParser PARSER = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX));

    private CronValidator() {}

    public static Optional<String> validate(String expression) {
        if (expression == null || expression.isBlank()) return Optional.empty();
        try {
            PARSER.parse(expression.trim()).validate();
            return Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.of("Invalid cron expression '" + expression + "' (use 5-field Unix syntax, e.g. '0 7 * * *'): " + e.getMessage());
        }
    }
}
```

```java
package org.roncax.podcaster.runs;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduler;
import io.quarkus.scheduler.Trigger;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;
import org.roncax.podcaster.domain.RunTrigger;
import org.roncax.podcaster.domain.Show;

@ApplicationScoped
public class ShowScheduler {
    private static final Logger LOG = Logger.getLogger(ShowScheduler.class);
    private static final String PREFIX = "show-";

    @Inject Scheduler scheduler;
    @Inject RunLauncher launcher;

    void onStart(@Observes StartupEvent event) {
        List<Show> shows = QuarkusTransaction.requiringNew().call(() -> Show.<Show>list("enabled = true and cron is not null"));
        shows.forEach(this::reschedule);
        LOG.infof("Scheduled %d show(s)", scheduledJobIds().size());
    }

    public void reschedule(Show show) {
        unschedule(show.id);
        if (!show.enabled || show.cron == null || show.cron.isBlank()) return;
        long showId = show.id;
        scheduler.newJob(PREFIX + showId)
                .setCron(show.cron.trim())
                .setTask(execution -> launchQuietly(showId))
                .schedule();
    }

    public void unschedule(long showId) {
        scheduler.unscheduleJob(PREFIX + showId);
    }

    public Set<String> scheduledJobIds() {
        return scheduler.getScheduledJobs().stream()
                .map(Trigger::getId)
                .filter(id -> id.startsWith(PREFIX))
                .collect(Collectors.toSet());
    }

    private void launchQuietly(long showId) {
        try {
            launcher.launch(showId, RunTrigger.SCHEDULED);
        } catch (RunAlreadyActiveException e) {
            LOG.infof("Skipping scheduled run: %s", e.getMessage());
        } catch (Exception e) {
            LOG.errorf(e, "Scheduled run for show %d could not start", showId);
        }
    }
}
```

- [ ] **Step 4: Run tests**

Run: `bash -lc './mvnw -q test -Dtest="CronValidatorTest,ShowSchedulerTest"'`
Expected: PASS. (If `0 0 7 * * ?` is accepted by cron-utils' UNIX definition, delete that single assertion — the other two cover invalid input.)

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Add per-show cron scheduling

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01LbcoMupq1VXnfzdqroFmyp"
```

---
### Task 17: Show management service and REST API

**Files:**
- Create: `src/main/java/org/roncax/podcaster/api/{ApiKeyFilter,ApiExceptionMappers,InvalidRequestException,ShowRequest,SourceRequest,SourceTestResult,RunCreated,ShowService,ShowResource,SourceResource,RunResource,EpisodeResource,MetaResource}.java`
- Test: `src/test/java/org/roncax/podcaster/api/ApiTest.java`

**Interfaces:**
- Consumes: `ChatModelRegistry` (Task 7), `ConnectorRegistry`, `SourceConfig`, `RawItem` (Task 4), `ContentExtractionService` (Task 5), `TtsEngine` (Task 11), `AudioStorage` (Task 12), `RunLauncher`, `RunAlreadyActiveException` (Task 15), `ShowScheduler`, `CronValidator` (Task 16).
- Produces:
  - `record ShowRequest(name, slug, description, language, voiceId, Double lengthScale, writerModel, rankerModel, focusPrompt, Integer targetDurationMinutes, Integer minItems, cron, Boolean enabled, Integer retainEpisodes)` with Bean Validation; null optional fields take the defaults (1.0, 20, 3, enabled, 30).
  - `record SourceRequest(String connectorType, Map<String,String> config, Boolean fetchFullText, Boolean enabled)`.
  - `record SourceTestResult(List<RawItem> items, String sampleText, String error)`; `record RunCreated(long runId)`.
  - `InvalidRequestException(List<String> errors)` → HTTP 400 `{"error":"Invalid request","details":[...]}`; `RunAlreadyActiveException` → 409.
  - `ShowService`: `create(ShowRequest) → Show`, `update(long, ShowRequest) → Show`, `delete(long)`, `addSource(long showId, SourceRequest) → Source`, `updateSource(long, SourceRequest) → Source`, `deleteSource(long)`, `testSource(long) → SourceTestResult`, `validationErrors(ShowRequest, Long excludeId) → List<String>`. Create/update/delete keep the scheduler in sync.
  - REST (all under `/api`, `X-API-Key` header or `podcaster_key` cookie): see table in spec §6.1.
  - `ApiKeyFilter` also guards `/admin/**` (except `/admin/login`), redirecting to `/admin/login` (303) when the cookie is missing.

- [ ] **Step 1: Write the failing test**

```java
package org.roncax.podcaster.api;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.runs.ShowScheduler;
import org.roncax.podcaster.support.*;

@QuarkusTest
@WithTestResource(WireMockResource.class)
class ApiTest {
    @InjectWireMock WireMockServer wm;
    @Inject ShowScheduler scheduler;

    @BeforeEach
    void setup() {
        TestData.cleanDb();
        WireMockResource.installDefaults(wm);
        FakeChatModelRegistry.install(new FakeChatModel().responder(FakeResponses::pipeline));
    }

    private RequestSpecification api() {
        return given().header("X-API-Key", "test-key").contentType(ContentType.JSON);
    }

    private Map<String, Object> showJson(String slug) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", "Show " + slug);
        m.put("slug", slug);
        m.put("language", "it");
        m.put("voiceId", "it_IT-paola-medium");
        m.put("writerModel", "fake");
        m.put("minItems", 1);
        return m;
    }

    private long createShow(String slug) {
        return api().body(showJson(slug)).post("/api/shows").then().statusCode(201).extract().jsonPath().getLong("id");
    }

    private String rss(String... paths) {
        String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.now().atOffset(ZoneOffset.UTC));
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>t</title><link>http://x</link><description>d</description>");
        for (String p : paths) {
            sb.append("<item><title>Story ").append(p).append("</title><link>").append(wm.baseUrl()).append(p)
              .append("</link><pubDate>").append(date).append("</pubDate></item>");
        }
        return sb.append("</channel></rss>").toString();
    }

    @Test
    void requiresApiKey() {
        given().get("/api/shows").then().statusCode(401);
        given().header("X-API-Key", "wrong").get("/api/shows").then().statusCode(401);
        api().get("/api/shows").then().statusCode(200);
        given().get("/q/health").then().statusCode(200);
    }

    @Test
    void createsShowWithSourcesAndSchedule() {
        Map<String, Object> json = showJson("daily");
        json.put("cron", "0 7 * * *");
        long id = api().body(json).post("/api/shows").then().statusCode(201)
                .body("slug", is("daily")).body("targetDurationMinutes", is(20)).body("persistent", nullValue())
                .extract().jsonPath().getLong("id");

        assertTrue(scheduler.scheduledJobIds().contains("show-" + id));
        api().body(Map.of("connectorType", "rss", "config", Map.of("url", "https://www.ansa.it/sito/ansait_rss.xml")))
                .post("/api/shows/" + id + "/sources").then().statusCode(201).body("fetchFullText", is(true));
        api().get("/api/shows/" + id + "/sources").then().statusCode(200).body("size()", is(1));
        api().get("/api/shows/" + id).then().statusCode(200).body("name", is("Show daily"));
    }

    @Test
    void rejectsSemanticallyInvalidShow() {
        Map<String, Object> json = showJson("bad");
        json.put("writerModel", "gpt");
        json.put("cron", "every morning");
        json.put("voiceId", "xx_XX-nobody-low");
        api().body(json).post("/api/shows").then().statusCode(400)
                .body("details", hasItems(containsString("writerModel 'gpt'"), containsString("Invalid cron"), containsString("voiceId 'xx_XX-nobody-low'")));
    }

    @Test
    void rejectsBeanValidationErrors() {
        Map<String, Object> json = showJson("Bad Slug");
        api().body(json).post("/api/shows").then().statusCode(400);
    }

    @Test
    void rejectsDuplicateSlug() {
        createShow("dup");
        api().body(showJson("dup")).post("/api/shows").then().statusCode(400)
                .body("details", hasItem(containsString("already used")));
    }

    @Test
    void rejectsUnknownConnector() {
        long id = createShow("conn");
        api().body(Map.of("connectorType", "site:nope", "config", Map.of()))
                .post("/api/shows/" + id + "/sources").then().statusCode(400)
                .body("details", hasItem(containsString("Unknown connectorType")));
        api().body(Map.of("connectorType", "rss", "config", Map.of()))
                .post("/api/shows/" + id + "/sources").then().statusCode(400)
                .body("details", hasItem(containsString("config.url")));
    }

    @Test
    void triggersRunAndRejectsRetryOfNonFailedRun() {
        long id = createShow("trig");
        wm.stubFor(get("/tfeed").willReturn(okXml(rss())));
        api().body(Map.of("connectorType", "rss", "config", Map.of("url", wm.baseUrl() + "/tfeed")))
                .post("/api/shows/" + id + "/sources").then().statusCode(201);

        long runId = api().post("/api/shows/" + id + "/runs").then().statusCode(202).extract().jsonPath().getLong("runId");
        TestData.awaitRun(runId);

        api().get("/api/runs?showId=" + id).then().statusCode(200).body("[0].status", is("SKIPPED"));
        api().post("/api/runs/" + runId + "/retry").then().statusCode(409);
    }

    @Test
    void testsSourceWithoutPersisting() {
        long id = createShow("srctest");
        wm.stubFor(get("/sfeed").willReturn(okXml(rss("/s1", "/s2"))));
        wm.stubFor(get("/s1").willReturn(aResponse().withStatus(200).withBody(Fixtures.bytes("ilpost-article.html"))));
        long sourceId = api().body(Map.of("connectorType", "rss", "config", Map.of("url", wm.baseUrl() + "/sfeed")))
                .post("/api/shows/" + id + "/sources").then().statusCode(201).extract().jsonPath().getLong("id");

        api().post("/api/sources/" + sourceId + "/test").then().statusCode(200)
                .body("items.size()", is(2))
                .body("sampleText", containsString("sciopero"))
                .body("error", nullValue());
        assertEquals(0L, (long) io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
                .call(() -> org.roncax.podcaster.domain.Item.count()));
    }

    @Test
    void metaEndpoints() {
        api().get("/api/meta/models").then().statusCode(200).body("$", hasItem("fake"));
        api().get("/api/meta/connectors").then().statusCode(200).body("$", hasItem("rss"));
        api().get("/api/meta/voices").then().statusCode(200).body("$", hasItem("it_IT-paola-medium"));
    }

    @Test
    void deletingShowRemovesItAndItsSchedule() {
        Map<String, Object> json = showJson("gone");
        json.put("cron", "0 7 * * *");
        long id = api().body(json).post("/api/shows").then().statusCode(201).extract().jsonPath().getLong("id");
        api().delete("/api/shows/" + id).then().statusCode(204);
        api().get("/api/shows/" + id).then().statusCode(404);
        assertFalse(scheduler.scheduledJobIds().contains("show-" + id));
    }

    @Test
    void unknownEpisodeAudioIs404() {
        api().get("/api/episodes/999999/audio").then().statusCode(404);
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `bash -lc './mvnw -q test -Dtest=ApiTest'`
Expected: compilation FAIL.

- [ ] **Step 3: Implement DTOs, exceptions, mappers and the auth filter**

```java
package org.roncax.podcaster.api;

import jakarta.validation.constraints.*;

public record ShowRequest(
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{0,99}", message = "use lowercase letters, digits and dashes") String slug,
        String description,
        @NotBlank @Size(max = 20) String language,
        @NotBlank String voiceId,
        @DecimalMin("0.5") @DecimalMax("2.0") Double lengthScale,
        @NotBlank String writerModel,
        String rankerModel,
        String focusPrompt,
        @Min(1) @Max(120) Integer targetDurationMinutes,
        @Min(1) @Max(100) Integer minItems,
        String cron,
        Boolean enabled,
        @Min(1) @Max(1000) Integer retainEpisodes) {}
```

```java
package org.roncax.podcaster.api;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

public record SourceRequest(@NotBlank String connectorType, Map<String, String> config, Boolean fetchFullText, Boolean enabled) {}
```

```java
package org.roncax.podcaster.api;

import java.util.List;
import org.roncax.podcaster.ingestion.RawItem;

public record SourceTestResult(List<RawItem> items, String sampleText, String error) {}
```

```java
package org.roncax.podcaster.api;

public record RunCreated(long runId) {}
```

```java
package org.roncax.podcaster.api;

import java.util.List;

public class InvalidRequestException extends RuntimeException {
    private final List<String> errors;

    public InvalidRequestException(List<String> errors) {
        super(String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() { return errors; }
}
```

```java
package org.roncax.podcaster.api;

import jakarta.ws.rs.core.Response;
import java.util.List;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import org.roncax.podcaster.runs.RunAlreadyActiveException;

public class ApiExceptionMappers {
    public record ErrorBody(String error, List<String> details) {}

    @ServerExceptionMapper
    public RestResponse<ErrorBody> invalid(InvalidRequestException e) {
        return RestResponse.status(Response.Status.BAD_REQUEST, new ErrorBody("Invalid request", e.errors()));
    }

    @ServerExceptionMapper
    public RestResponse<ErrorBody> active(RunAlreadyActiveException e) {
        return RestResponse.status(Response.Status.CONFLICT, new ErrorBody(e.getMessage(), List.of()));
    }
}
```

```java
package org.roncax.podcaster.api;

import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;
import org.roncax.podcaster.config.PodcasterConfig;

public class ApiKeyFilter {
    public static final String COOKIE = "podcaster_key";

    @Inject PodcasterConfig config;

    @ServerRequestFilter(preMatching = true)
    public Optional<Response> filter(ContainerRequestContext ctx) {
        String path = ctx.getUriInfo().getPath();
        boolean api = path.startsWith("/api/") || path.equals("/api");
        boolean admin = path.equals("/admin") || path.startsWith("/admin/");
        if (!api && !admin) return Optional.empty();
        if (path.equals("/admin/login")) return Optional.empty();
        if (isAuthorized(ctx)) return Optional.empty();
        if (api) return Optional.of(Response.status(Response.Status.UNAUTHORIZED).build());
        return Optional.of(Response.seeOther(URI.create("/admin/login")).build());
    }

    private boolean isAuthorized(ContainerRequestContext ctx) {
        String key = ctx.getHeaderString("X-API-Key");
        if (key == null) {
            Cookie cookie = ctx.getCookies().get(COOKIE);
            if (cookie != null) key = cookie.getValue();
        }
        return key != null && MessageDigest.isEqual(
                key.getBytes(StandardCharsets.UTF_8), config.apiKey().getBytes(StandardCharsets.UTF_8));
    }
}
```

- [ ] **Step 4: Implement `ShowService`**

```java
package org.roncax.podcaster.api;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;
import org.jboss.logging.Logger;
import org.roncax.podcaster.domain.Show;
import org.roncax.podcaster.domain.Source;
import org.roncax.podcaster.extraction.ContentExtractionService;
import org.roncax.podcaster.ingestion.*;
import org.roncax.podcaster.llm.ChatModelRegistry;
import org.roncax.podcaster.publishing.AudioStorage;
import org.roncax.podcaster.runs.CronValidator;
import org.roncax.podcaster.runs.ShowScheduler;
import org.roncax.podcaster.tts.TtsEngine;
import org.roncax.podcaster.util.Exceptions;

@ApplicationScoped
public class ShowService {
    private static final Logger LOG = Logger.getLogger(ShowService.class);
    private static final Duration TEST_WINDOW = Duration.ofDays(7);
    private static final int TEST_ITEMS = 10;

    @Inject ChatModelRegistry models;
    @Inject ConnectorRegistry connectors;
    @Inject ContentExtractionService extraction;
    @Inject TtsEngine tts;
    @Inject ShowScheduler scheduler;
    @Inject AudioStorage storage;

    public Show create(ShowRequest r) {
        throwIfInvalid(validationErrors(r, null));
        Show show = QuarkusTransaction.requiringNew().call(() -> {
            Show s = new Show();
            apply(s, r);
            s.persist();
            return s;
        });
        scheduler.reschedule(show);
        return show;
    }

    public Show update(long id, ShowRequest r) {
        throwIfInvalid(validationErrors(r, id));
        Show show = QuarkusTransaction.requiringNew().call(() -> {
            Show s = Show.<Show>findByIdOptional(id).orElseThrow(NotFoundException::new);
            apply(s, r);
            return s;
        });
        scheduler.reschedule(show);
        return show;
    }

    public void delete(long id) {
        String slug = QuarkusTransaction.requiringNew().call(() -> {
            Show s = Show.<Show>findByIdOptional(id).orElseThrow(NotFoundException::new);
            String sl = s.slug;
            s.delete();
            return sl;
        });
        scheduler.unschedule(id);
        try {
            Path dir = storage.resolve(slug);
            if (Files.exists(dir)) {
                try (Stream<Path> paths = Files.walk(dir)) {
                    for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
                }
            }
        } catch (IOException e) {
            LOG.warnf("Could not delete audio of show %s: %s", slug, e.getMessage());
        }
    }

    public List<String> validationErrors(ShowRequest r, Long excludeId) {
        List<String> errors = new ArrayList<>();
        if (!models.isAvailable(r.writerModel())) {
            errors.add("writerModel '" + r.writerModel() + "' is not an enabled model; available: " + models.availableNames());
        }
        if (r.rankerModel() != null && !r.rankerModel().isBlank() && !models.isAvailable(r.rankerModel())) {
            errors.add("rankerModel '" + r.rankerModel() + "' is not an enabled model; available: " + models.availableNames());
        }
        CronValidator.validate(r.cron()).ifPresent(errors::add);
        try {
            Set<String> voices = tts.voices();
            if (!voices.contains(r.voiceId())) {
                errors.add("voiceId '" + r.voiceId() + "' is not installed in Piper; available: " + voices);
            }
        } catch (Exception e) {
            LOG.warnf("Could not verify voice, Piper unreachable: %s", e.getMessage());
        }
        long clash = QuarkusTransaction.requiringNew().call(() -> excludeId == null
                ? Show.count("slug", r.slug())
                : Show.count("slug = ?1 and id <> ?2", r.slug(), excludeId));
        if (clash > 0) errors.add("slug '" + r.slug() + "' is already used by another show");
        return errors;
    }

    public Source addSource(long showId, SourceRequest r) {
        validateSource(r);
        return QuarkusTransaction.requiringNew().call(() -> {
            if (Show.findById(showId) == null) throw new NotFoundException();
            Source s = new Source();
            s.showId = showId;
            applySource(s, r);
            s.persist();
            return s;
        });
    }

    public Source updateSource(long id, SourceRequest r) {
        validateSource(r);
        return QuarkusTransaction.requiringNew().call(() -> {
            Source s = Source.<Source>findByIdOptional(id).orElseThrow(NotFoundException::new);
            applySource(s, r);
            return s;
        });
    }

    public void deleteSource(long id) {
        QuarkusTransaction.requiringNew().run(() -> {
            Source s = Source.<Source>findByIdOptional(id).orElseThrow(NotFoundException::new);
            s.delete();
        });
    }

    public SourceTestResult testSource(long id) {
        Source source = QuarkusTransaction.requiringNew().call(() -> Source.<Source>findByIdOptional(id).orElseThrow(NotFoundException::new));
        SourceConnector connector = connectors.find(source.connectorType)
                .orElseThrow(() -> new InvalidRequestException(List.of("Unknown connectorType '" + source.connectorType + "'")));
        try {
            List<RawItem> items = connector.fetch(new SourceConfig(source.config), Instant.now().minus(TEST_WINDOW));
            items = items.subList(0, Math.min(TEST_ITEMS, items.size()));
            String sample = null;
            if (!items.isEmpty()) {
                RawItem first = items.get(0);
                sample = first.fullText() != null || connector.providesFullText()
                        ? first.fullText()
                        : extraction.extract(first.url()).orElse(null);
            }
            return new SourceTestResult(items, sample, null);
        } catch (Exception e) {
            return new SourceTestResult(List.of(), null, Exceptions.message(e));
        }
    }

    private void validateSource(SourceRequest r) {
        List<String> errors = new ArrayList<>();
        if (connectors.find(r.connectorType()).isEmpty()) {
            errors.add("Unknown connectorType '" + r.connectorType() + "'; available: " + connectors.types());
        }
        if (RssSourceConnector.TYPE.equals(r.connectorType())
                && (r.config() == null || r.config().getOrDefault("url", "").isBlank())) {
            errors.add("rss sources need config.url");
        }
        throwIfInvalid(errors);
    }

    private static void throwIfInvalid(List<String> errors) {
        if (!errors.isEmpty()) throw new InvalidRequestException(errors);
    }

    static void apply(Show s, ShowRequest r) {
        s.name = r.name().trim();
        s.slug = r.slug();
        s.description = blankToNull(r.description());
        s.language = r.language().trim();
        s.voiceId = r.voiceId().trim();
        s.lengthScale = r.lengthScale() == null ? 1.0 : r.lengthScale();
        s.writerModel = r.writerModel();
        s.rankerModel = blankToNull(r.rankerModel());
        s.focusPrompt = blankToNull(r.focusPrompt());
        s.targetDurationMinutes = r.targetDurationMinutes() == null ? 20 : r.targetDurationMinutes();
        s.minItems = r.minItems() == null ? 3 : r.minItems();
        s.cron = blankToNull(r.cron());
        s.enabled = r.enabled() == null || r.enabled();
        s.retainEpisodes = r.retainEpisodes() == null ? 30 : r.retainEpisodes();
    }

    static void applySource(Source s, SourceRequest r) {
        s.connectorType = r.connectorType();
        s.config = r.config() == null ? new HashMap<>() : new HashMap<>(r.config());
        s.fetchFullText = r.fetchFullText() == null || r.fetchFullText();
        s.enabled = r.enabled() == null || r.enabled();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
```

- [ ] **Step 5: Implement the resources**

```java
package org.roncax.podcaster.api;

import io.quarkus.panache.common.Sort;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestResponse;
import org.roncax.podcaster.domain.RunTrigger;
import org.roncax.podcaster.domain.Show;
import org.roncax.podcaster.domain.Source;
import org.roncax.podcaster.runs.RunLauncher;

@Path("/api/shows")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ShowResource {
    @Inject ShowService shows;
    @Inject RunLauncher launcher;

    @GET
    public List<Show> list() {
        return Show.listAll(Sort.by("name"));
    }

    @POST
    public RestResponse<Show> create(@Valid ShowRequest request) {
        return RestResponse.status(Response.Status.CREATED, shows.create(request));
    }

    @GET
    @Path("/{id}")
    public Show get(@RestPath long id) {
        return Show.<Show>findByIdOptional(id).orElseThrow(NotFoundException::new);
    }

    @PUT
    @Path("/{id}")
    public Show update(@RestPath long id, @Valid ShowRequest request) {
        return shows.update(id, request);
    }

    @DELETE
    @Path("/{id}")
    public void delete(@RestPath long id) {
        shows.delete(id);
    }

    @GET
    @Path("/{id}/sources")
    public List<Source> sources(@RestPath long id) {
        return Source.list("showId = ?1 order by id", id);
    }

    @POST
    @Path("/{id}/sources")
    public RestResponse<Source> addSource(@RestPath long id, @Valid SourceRequest request) {
        return RestResponse.status(Response.Status.CREATED, shows.addSource(id, request));
    }

    @POST
    @Path("/{id}/runs")
    public RestResponse<RunCreated> run(@RestPath long id) {
        return RestResponse.status(Response.Status.ACCEPTED, new RunCreated(launcher.launch(id, RunTrigger.MANUAL)));
    }
}
```

```java
package org.roncax.podcaster.api;

import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestPath;
import org.roncax.podcaster.domain.Source;

@Path("/api/sources")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SourceResource {
    @Inject ShowService shows;

    @PUT
    @Path("/{id}")
    public Source update(@RestPath long id, @Valid SourceRequest request) {
        return shows.updateSource(id, request);
    }

    @DELETE
    @Path("/{id}")
    public void delete(@RestPath long id) {
        shows.deleteSource(id);
    }

    @POST
    @Path("/{id}/test")
    public SourceTestResult test(@RestPath long id) {
        return shows.testSource(id);
    }
}
```

```java
package org.roncax.podcaster.api;

import io.quarkus.panache.common.Sort;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.*;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestQuery;
import org.jboss.resteasy.reactive.RestResponse;
import org.roncax.podcaster.domain.Run;
import org.roncax.podcaster.domain.RunStatus;
import org.roncax.podcaster.runs.RunLauncher;

@Path("/api/runs")
@Produces(MediaType.APPLICATION_JSON)
public class RunResource {
    private static final int LIMIT = 100;

    @Inject RunLauncher launcher;

    @GET
    public List<Run> list(@RestQuery Long showId, @RestQuery RunStatus status) {
        List<String> conditions = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        if (showId != null) { conditions.add("showId = :showId"); params.put("showId", showId); }
        if (status != null) { conditions.add("status = :status"); params.put("status", status); }
        Sort sort = Sort.descending("startedAt");
        return (conditions.isEmpty() ? Run.<Run>findAll(sort) : Run.<Run>find(String.join(" and ", conditions), sort, params))
                .page(0, LIMIT).list();
    }

    @GET
    @Path("/{id}")
    public Run get(@RestPath long id) {
        return Run.<Run>findByIdOptional(id).orElseThrow(NotFoundException::new);
    }

    @POST
    @Path("/{id}/retry")
    public RestResponse<RunCreated> retry(@RestPath long id) {
        try {
            launcher.retry(id);
        } catch (IllegalStateException e) {
            throw new ClientErrorException(e.getMessage(), Response.Status.CONFLICT);
        }
        return RestResponse.status(Response.Status.ACCEPTED, new RunCreated(id));
    }
}
```

```java
package org.roncax.podcaster.api;

import io.quarkus.panache.common.Sort;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.nio.file.Files;
import java.util.List;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestQuery;
import org.roncax.podcaster.domain.Episode;
import org.roncax.podcaster.publishing.AudioStorage;

@Path("/api/episodes")
@Produces(MediaType.APPLICATION_JSON)
public class EpisodeResource {
    private static final int LIMIT = 100;

    @Inject AudioStorage storage;

    @GET
    public List<Episode> list(@RestQuery Long showId) {
        Sort sort = Sort.descending("createdAt");
        return (showId == null ? Episode.<Episode>findAll(sort) : Episode.<Episode>find("showId", sort, showId))
                .page(0, LIMIT).list();
    }

    @GET
    @Path("/{id}")
    public Episode get(@RestPath long id) {
        return Episode.<Episode>findByIdOptional(id).orElseThrow(NotFoundException::new);
    }

    @GET
    @Path("/{id}/audio")
    @Produces("audio/mpeg")
    public Response audio(@RestPath long id) {
        Episode episode = Episode.<Episode>findByIdOptional(id).orElseThrow(NotFoundException::new);
        if (episode.audioPath == null || !storage.exists(episode.audioPath)) throw new NotFoundException();
        java.nio.file.Path file = storage.resolve(episode.audioPath);
        return Response.ok(file.toFile(), "audio/mpeg")
                .header("Content-Disposition", "attachment; filename=\"" + file.getFileName() + "\"")
                .build();
    }
}
```

```java
package org.roncax.podcaster.api;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.ServiceUnavailableException;
import jakarta.ws.rs.core.MediaType;
import java.util.Set;
import org.roncax.podcaster.ingestion.ConnectorRegistry;
import org.roncax.podcaster.llm.ChatModelRegistry;
import org.roncax.podcaster.tts.TtsEngine;

@Path("/api/meta")
@Produces(MediaType.APPLICATION_JSON)
public class MetaResource {
    @Inject ChatModelRegistry models;
    @Inject ConnectorRegistry connectors;
    @Inject TtsEngine tts;

    @GET
    @Path("/models")
    public Set<String> models() { return models.availableNames(); }

    @GET
    @Path("/connectors")
    public Set<String> connectors() { return connectors.types(); }

    @GET
    @Path("/voices")
    public Set<String> voices() {
        try {
            return tts.voices();
        } catch (Exception e) {
            throw new ServiceUnavailableException("Piper unreachable: " + e.getMessage());
        }
    }
}
```

- [ ] **Step 6: Run tests**

Run: `bash -lc './mvnw -q test -Dtest=ApiTest'`
Expected: PASS.

- [ ] **Step 7: Run the full suite**

Run: `bash -lc './mvnw -q test'`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "Add show management service and REST API with API-key auth

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01LbcoMupq1VXnfzdqroFmyp"
```

---

### Task 18: Admin web UI (Qute + htmx)

**Files:**
- Create: `src/main/java/org/roncax/podcaster/admin/{AdminResource,ShowForm,TemplateTypes}.java`
- Create: `src/main/resources/templates/base.html`
- Create: `src/main/resources/templates/tags/{showForm,runTable}.html`
- Create: `src/main/resources/templates/AdminResource/{login,shows,show,runs,sourceTest,episode}.html`
- Modify: `src/main/java/org/roncax/podcaster/domain/Run.java` (add `isFailed()`)
- Test: `src/test/java/org/roncax/podcaster/admin/AdminTest.java`

**Interfaces:**
- Consumes: `ShowService`, `ShowRequest`, `SourceRequest`, `SourceTestResult`, `InvalidRequestException`, `ApiKeyFilter.COOKIE` (Task 17); `RunLauncher`, `RunAlreadyActiveException` (Task 15); `ChatModelRegistry` (Task 7); `ConnectorRegistry` (Task 4); `TtsEngine` (Task 11).
- Produces: HTML pages under `/admin`: `GET /admin` → 303 `/admin/shows`; `GET|POST /admin/login`; `POST /admin/logout`; `GET|POST /admin/shows`; `GET|POST /admin/shows/{id}`; `POST /admin/shows/{id}/delete`; `POST /admin/shows/{id}/sources`; `POST /admin/sources/{id}/delete`; `POST /admin/sources/{id}/test` (fragment); `POST /admin/shows/{id}/run` (fragment); `GET /admin/shows/{id}/runs` (fragment); `POST /admin/runs/{id}/retry` (fragment); `GET /admin/episodes/{id}`.

- [ ] **Step 1: Write the failing test**

```java
package org.roncax.podcaster.admin;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.domain.Show;
import org.roncax.podcaster.support.*;

@QuarkusTest
@WithTestResource(WireMockResource.class)
class AdminTest {
    @InjectWireMock WireMockServer wm;

    @BeforeEach
    void setup() {
        TestData.cleanDb();
        WireMockResource.installDefaults(wm);
        FakeChatModelRegistry.install(new FakeChatModel().responder(FakeResponses::pipeline));
    }

    private RequestSpecification admin() {
        return given().cookie("podcaster_key", "test-key").redirects().follow(false);
    }

    @Test
    void redirectsToLoginWithoutCookie() {
        given().redirects().follow(false).get("/admin/shows").then().statusCode(303).header("Location", endsWith("/admin/login"));
    }

    @Test
    void loginSetsCookie() {
        given().redirects().follow(false).formParam("key", "test-key").post("/admin/login")
                .then().statusCode(303).cookie("podcaster_key", "test-key");
        given().formParam("key", "nope").post("/admin/login").then().statusCode(200).body(containsString("Wrong key"));
    }

    @Test
    void listsShows() {
        TestData.show("listed");
        admin().get("/admin/shows").then().statusCode(200).body(containsString("Show listed")).body(containsString("/feeds/listed.xml"));
    }

    @Test
    void createsShowFromForm() {
        String location = admin()
                .formParam("name", "Morning News").formParam("slug", "morning").formParam("language", "it")
                .formParam("voiceId", "it_IT-paola-medium").formParam("writerModel", "fake").formParam("rankerModel", "")
                .formParam("lengthScale", "1.0").formParam("targetDurationMinutes", "20").formParam("minItems", "3")
                .formParam("retainEpisodes", "30").formParam("cron", "0 7 * * *").formParam("enabled", "on")
                .post("/admin/shows").then().statusCode(303).extract().header("Location");
        admin().get(location).then().statusCode(200).body(containsString("Morning News")).body(containsString("Run now"));
    }

    @Test
    void invalidFormIsRerenderedWithErrors() {
        admin().formParam("name", "X").formParam("slug", "x").formParam("language", "it")
                .formParam("voiceId", "it_IT-paola-medium").formParam("writerModel", "gpt")
                .formParam("targetDurationMinutes", "abc")
                .post("/admin/shows").then().statusCode(200)
                .body(containsString("writerModel &#39;gpt&#39;"))
                .body(containsString("targetDurationMinutes must be a number"));
    }

    @Test
    void addsSourceAndTriggersRun() {
        Show show = TestData.show("ui");
        admin().formParam("connectorType", "rss").formParam("config", "url=" + wm.baseUrl() + "/uifeed")
                .formParam("fetchFullText", "on")
                .post("/admin/shows/" + show.id + "/sources").then().statusCode(303);
        admin().get("/admin/shows/" + show.id).then().statusCode(200).body(containsString(wm.baseUrl() + "/uifeed"));
        admin().post("/admin/shows/" + show.id + "/run").then().statusCode(200).body(containsString("id=\"runs\""));
        admin().get("/admin/shows/" + show.id + "/runs").then().statusCode(200).body(containsString("<table"));
    }
}
```

Note: Qute escapes `'` as `&#39;` in HTML templates; if the escaped form differs in your Qute version, assert on `writerModel` and `gpt` separately.

- [ ] **Step 2: Run to verify failure**

Run: `bash -lc './mvnw -q test -Dtest=AdminTest'`
Expected: FAIL (404s / compilation errors).

- [ ] **Step 3: Add `isFailed()` to `Run`**

Add to `Run.java` (import `com.fasterxml.jackson.annotation.JsonIgnore`):

```java
    @JsonIgnore
    public boolean isFailed() {
        return status == RunStatus.FAILED;
    }
```

- [ ] **Step 4: Implement `ShowForm` and `TemplateTypes`**

```java
package org.roncax.podcaster.admin;

import java.util.ArrayList;
import java.util.List;
import org.jboss.resteasy.reactive.RestForm;
import org.roncax.podcaster.api.ShowRequest;
import org.roncax.podcaster.domain.Show;

/** HTML form backing bean; all fields are strings so invalid input can be re-rendered. */
public class ShowForm {
    @RestForm public String name;
    @RestForm public String slug;
    @RestForm public String description;
    @RestForm public String language;
    @RestForm public String voiceId;
    @RestForm public String lengthScale;
    @RestForm public String writerModel;
    @RestForm public String rankerModel;
    @RestForm public String focusPrompt;
    @RestForm public String targetDurationMinutes;
    @RestForm public String minItems;
    @RestForm public String cron;
    @RestForm public String enabled;
    @RestForm public String retainEpisodes;

    public static ShowForm defaults() {
        ShowForm f = new ShowForm();
        f.language = "it";
        f.lengthScale = "1.0";
        f.targetDurationMinutes = "20";
        f.minItems = "3";
        f.retainEpisodes = "30";
        f.enabled = "on";
        return f;
    }

    public static ShowForm from(Show s) {
        ShowForm f = new ShowForm();
        f.name = s.name;
        f.slug = s.slug;
        f.description = s.description;
        f.language = s.language;
        f.voiceId = s.voiceId;
        f.lengthScale = String.valueOf(s.lengthScale);
        f.writerModel = s.writerModel;
        f.rankerModel = s.rankerModel;
        f.focusPrompt = s.focusPrompt;
        f.targetDurationMinutes = String.valueOf(s.targetDurationMinutes);
        f.minItems = String.valueOf(s.minItems);
        f.cron = s.cron;
        f.enabled = s.enabled ? "on" : null;
        f.retainEpisodes = String.valueOf(s.retainEpisodes);
        return f;
    }

    /** Converts to a request, collecting number-format errors into {@code errors}. */
    public ShowRequest toRequest(List<String> errors) {
        return new ShowRequest(trim(name), trim(slug), trim(description), trim(language), trim(voiceId),
                decimal("lengthScale", lengthScale, errors), trim(writerModel), trim(rankerModel), trim(focusPrompt),
                integer("targetDurationMinutes", targetDurationMinutes, errors), integer("minItems", minItems, errors),
                trim(cron), enabled != null, integer("retainEpisodes", retainEpisodes, errors));
    }

    private static String trim(String s) { return s == null ? null : s.trim(); }

    private static Integer integer(String field, String value, List<String> errors) {
        if (value == null || value.isBlank()) return null;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            errors.add(field + " must be a number");
            return null;
        }
    }

    private static Double decimal(String field, String value, List<String> errors) {
        if (value == null || value.isBlank()) return null;
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            errors.add(field + " must be a number");
            return null;
        }
    }

    public static List<String> newErrors() { return new ArrayList<>(); }
}
```

```java
package org.roncax.podcaster.admin;

import io.quarkus.qute.TemplateData;
import org.roncax.podcaster.api.SourceTestResult;
import org.roncax.podcaster.domain.*;
import org.roncax.podcaster.ingestion.RawItem;

/** Generates Qute value resolvers for types used inside untyped tag templates. */
@TemplateData(target = Show.class)
@TemplateData(target = Source.class)
@TemplateData(target = Run.class)
@TemplateData(target = Episode.class)
@TemplateData(target = RawItem.class)
@TemplateData(target = SourceTestResult.class)
@TemplateData(target = ShowForm.class)
public class TemplateTypes {}
```

- [ ] **Step 5: Implement `AdminResource`**

```java
package org.roncax.podcaster.admin;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.*;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.RestPath;
import org.roncax.podcaster.api.*;
import org.roncax.podcaster.config.PodcasterConfig;
import org.roncax.podcaster.domain.*;
import org.roncax.podcaster.ingestion.ConnectorRegistry;
import org.roncax.podcaster.llm.ChatModelRegistry;
import org.roncax.podcaster.runs.RunAlreadyActiveException;
import org.roncax.podcaster.runs.RunLauncher;
import org.roncax.podcaster.tts.TtsEngine;

@Path("/admin")
@Produces(MediaType.TEXT_HTML)
public class AdminResource {

    @CheckedTemplate
    static class Templates {
        static native TemplateInstance login(String error);
        static native TemplateInstance shows(List<Show> shows, ShowForm form, List<String> errors, Set<String> models, Set<String> voices);
        static native TemplateInstance show(Show show, ShowForm form, String formAction, List<Source> sources, List<Run> runs,
                                            List<Episode> episodes, List<String> errors, Set<String> models, Set<String> voices,
                                            Set<String> connectors);
        static native TemplateInstance runs(long showId, List<Run> runs);
        static native TemplateInstance sourceTest(SourceTestResult result);
        static native TemplateInstance episode(Show show, Episode episode);
    }

    @Inject PodcasterConfig config;
    @Inject ShowService shows;
    @Inject RunLauncher launcher;
    @Inject ChatModelRegistry models;
    @Inject ConnectorRegistry connectors;
    @Inject TtsEngine tts;
    @Inject Validator validator;

    @GET
    public Response index() {
        return Response.seeOther(URI.create("/admin/shows")).build();
    }

    @GET
    @Path("/login")
    public TemplateInstance loginPage() {
        return Templates.login(null);
    }

    @POST
    @Path("/login")
    public Response login(@RestForm String key) {
        if (key == null || !key.equals(config.apiKey())) {
            return Response.ok(Templates.login("Wrong key")).build();
        }
        NewCookie cookie = new NewCookie.Builder(ApiKeyFilter.COOKIE).value(key).path("/")
                .httpOnly(true).sameSite(NewCookie.SameSite.STRICT).maxAge(60 * 60 * 24 * 30).build();
        return Response.seeOther(URI.create("/admin/shows")).cookie(cookie).build();
    }

    @POST
    @Path("/logout")
    public Response logout() {
        NewCookie cookie = new NewCookie.Builder(ApiKeyFilter.COOKIE).value("").path("/").maxAge(0).build();
        return Response.seeOther(URI.create("/admin/login")).cookie(cookie).build();
    }

    @GET
    @Path("/shows")
    public TemplateInstance listShows() {
        return Templates.shows(Show.listAll(), ShowForm.defaults(), List.of(), models.availableNames(), voices());
    }

    @POST
    @Path("/shows")
    public Response createShow(@BeanParam ShowForm form) {
        List<String> errors = ShowForm.newErrors();
        ShowRequest request = form.toRequest(errors);
        errors.addAll(beanErrors(request));
        errors.addAll(shows.validationErrors(request, null));
        if (errors.isEmpty()) {
            try {
                Show show = shows.create(request);
                return Response.seeOther(URI.create("/admin/shows/" + show.id)).build();
            } catch (InvalidRequestException e) {
                errors.addAll(e.errors());
            }
        }
        return Response.ok(Templates.shows(Show.listAll(), form, errors, models.availableNames(), voices())).build();
    }

    @GET
    @Path("/shows/{id}")
    public TemplateInstance showPage(@RestPath long id) {
        Show show = Show.<Show>findByIdOptional(id).orElseThrow(NotFoundException::new);
        return showTemplate(show, ShowForm.from(show), List.of());
    }

    @POST
    @Path("/shows/{id}")
    public Response updateShow(@RestPath long id, @BeanParam ShowForm form) {
        Show existing = Show.<Show>findByIdOptional(id).orElseThrow(NotFoundException::new);
        List<String> errors = ShowForm.newErrors();
        ShowRequest request = form.toRequest(errors);
        errors.addAll(beanErrors(request));
        errors.addAll(shows.validationErrors(request, id));
        if (errors.isEmpty()) {
            try {
                shows.update(id, request);
                return Response.seeOther(URI.create("/admin/shows/" + id)).build();
            } catch (InvalidRequestException e) {
                errors.addAll(e.errors());
            }
        }
        return Response.ok(showTemplate(existing, form, errors)).build();
    }

    @POST
    @Path("/shows/{id}/delete")
    public Response deleteShow(@RestPath long id) {
        shows.delete(id);
        return Response.seeOther(URI.create("/admin/shows")).build();
    }

    @POST
    @Path("/shows/{id}/sources")
    public Response addSource(@RestPath long id, @RestForm String connectorType, @RestForm String config,
                              @RestForm String fetchFullText) {
        try {
            shows.addSource(id, new SourceRequest(connectorType, parseConfig(config), fetchFullText != null, true));
            return Response.seeOther(URI.create("/admin/shows/" + id)).build();
        } catch (InvalidRequestException e) {
            Show show = Show.<Show>findByIdOptional(id).orElseThrow(NotFoundException::new);
            return Response.ok(showTemplate(show, ShowForm.from(show), e.errors())).build();
        }
    }

    @POST
    @Path("/sources/{id}/delete")
    public Response deleteSource(@RestPath long id) {
        Source source = Source.<Source>findByIdOptional(id).orElseThrow(NotFoundException::new);
        shows.deleteSource(id);
        return Response.seeOther(URI.create("/admin/shows/" + source.showId)).build();
    }

    @POST
    @Path("/sources/{id}/test")
    public TemplateInstance testSource(@RestPath long id) {
        return Templates.sourceTest(shows.testSource(id));
    }

    @POST
    @Path("/shows/{id}/run")
    public TemplateInstance runNow(@RestPath long id) {
        try {
            launcher.launch(id, RunTrigger.MANUAL);
        } catch (RunAlreadyActiveException ignored) {
            // the runs table already shows the active run
        }
        return Templates.runs(id, recentRuns(id));
    }

    @GET
    @Path("/shows/{id}/runs")
    public TemplateInstance runsFragment(@RestPath long id) {
        return Templates.runs(id, recentRuns(id));
    }

    @POST
    @Path("/runs/{id}/retry")
    public TemplateInstance retry(@RestPath long id) {
        Run run = Run.<Run>findByIdOptional(id).orElseThrow(NotFoundException::new);
        try {
            launcher.retry(id);
        } catch (IllegalStateException | RunAlreadyActiveException ignored) {
            // status is visible in the refreshed table
        }
        return Templates.runs(run.showId, recentRuns(run.showId));
    }

    @GET
    @Path("/episodes/{id}")
    public TemplateInstance episodePage(@RestPath long id) {
        Episode episode = Episode.<Episode>findByIdOptional(id).orElseThrow(NotFoundException::new);
        return Templates.episode(Show.findById(episode.showId), episode);
    }

    private TemplateInstance showTemplate(Show show, ShowForm form, List<String> errors) {
        List<Source> sources = Source.list("showId = ?1 order by id", show.id);
        List<Episode> episodes = Episode.find("showId = ?1 order by createdAt desc", show.id).page(0, 20).list();
        return Templates.show(show, form, "/admin/shows/" + show.id, sources, recentRuns(show.id), episodes, errors,
                models.availableNames(), voices(), connectors.types());
    }

    private List<Run> recentRuns(long showId) {
        return QuarkusTransaction.requiringNew().call(() ->
                Run.<Run>find("showId = ?1 order by startedAt desc", showId).page(0, 15).list());
    }

    private Set<String> voices() {
        try {
            return tts.voices();
        } catch (Exception e) {
            return Set.of();
        }
    }

    private List<String> beanErrors(ShowRequest request) {
        List<String> errors = new ArrayList<>();
        for (ConstraintViolation<ShowRequest> v : validator.validate(request)) {
            errors.add(v.getPropertyPath() + " " + v.getMessage());
        }
        Collections.sort(errors);
        return errors;
    }

    static Map<String, String> parseConfig(String text) {
        Map<String, String> config = new LinkedHashMap<>();
        if (text == null) return config;
        for (String line : text.split("\\R")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            config.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        return config;
    }
}
```

- [ ] **Step 6: Write the templates**

`src/main/resources/templates/base.html`:

```html
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>{#insert title}Podcaster{/}</title>
  <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/@picocss/pico@2/css/pico.min.css">
  <script src="https://unpkg.com/htmx.org@2.0.4"></script>
  <style>pre, .script { white-space: pre-wrap; } td small { color: var(--pico-muted-color); }</style>
</head>
<body>
<header class="container">
  <nav>
    <ul><li><strong><a href="/admin/shows">Podcaster</a></strong></li></ul>
    <ul>
      <li><a href="/q/swagger-ui">API</a></li>
      <li><form method="post" action="/admin/logout" style="margin:0"><button class="secondary outline" type="submit">Log out</button></form></li>
    </ul>
  </nav>
</header>
<main class="container">
{#insert body}{/}
</main>
</body>
</html>
```

`src/main/resources/templates/tags/showForm.html`:

```html
{#if errors}
<article role="alert"><ul>{#for e in errors}<li>{e}</li>{/for}</ul></article>
{/if}
<form method="post" action="{action}">
  <div class="grid">
    <label>Name <input name="name" value="{form.name ?: ''}" required></label>
    <label>Slug <input name="slug" value="{form.slug ?: ''}" required></label>
  </div>
  <label>Description <input name="description" value="{form.description ?: ''}"></label>
  <div class="grid">
    <label>Language <input name="language" value="{form.language ?: ''}" placeholder="it" required></label>
    <label>Voice
      <select name="voiceId" required>
        {#for v in voices}<option value="{v}" {#if v == form.voiceId}selected{/if}>{v}</option>{/for}
        {#if voices.isEmpty}<option value="{form.voiceId ?: ''}" selected>{form.voiceId ?: 'Piper unreachable'}</option>{/if}
      </select>
    </label>
    <label>Speed (length scale) <input name="lengthScale" type="number" step="0.05" min="0.5" max="2" value="{form.lengthScale ?: '1.0'}"></label>
  </div>
  <div class="grid">
    <label>Writer model
      <select name="writerModel" required>
        {#for m in models}<option value="{m}" {#if m == form.writerModel}selected{/if}>{m}</option>{/for}
      </select>
    </label>
    <label>Ranker model
      <select name="rankerModel">
        <option value="">(same as writer)</option>
        {#for m in models}<option value="{m}" {#if m == form.rankerModel}selected{/if}>{m}</option>{/for}
      </select>
    </label>
  </div>
  <label>Editorial focus <textarea name="focusPrompt" rows="2">{form.focusPrompt ?: ''}</textarea></label>
  <div class="grid">
    <label>Target minutes <input name="targetDurationMinutes" value="{form.targetDurationMinutes ?: '20'}"></label>
    <label>Min items <input name="minItems" value="{form.minItems ?: '3'}"></label>
    <label>Keep episodes <input name="retainEpisodes" value="{form.retainEpisodes ?: '30'}"></label>
  </div>
  <div class="grid">
    <label>Schedule (cron, e.g. <code>0 7 * * *</code>) <input name="cron" value="{form.cron ?: ''}"></label>
    <label><input type="checkbox" name="enabled" {#if form.enabled}checked{/if}> Enabled</label>
  </div>
  <button type="submit">{submit}</button>
</form>
```

`src/main/resources/templates/tags/runTable.html`:

```html
<table>
  <thead><tr><th>#</th><th>Trigger</th><th>Stage</th><th>Status</th><th>Started</th><th>Error</th><th></th></tr></thead>
  <tbody>
  {#for r in runs}
    <tr>
      <td>{r.id}</td><td>{r.trigger}</td><td>{r.stage}</td><td>{r.status}</td><td>{r.startedAt}</td>
      <td><small>{r.error ?: ''}</small></td>
      <td>{#if r.failed}<button class="outline" hx-post="/admin/runs/{r.id}/retry" hx-target="#runs" hx-swap="outerHTML">Retry</button>{/if}</td>
    </tr>
  {/for}
  </tbody>
</table>
```

`src/main/resources/templates/AdminResource/login.html`:

```html
{#include base}
{#title}Log in · Podcaster{/title}
{#body}
<article>
  <h1>Podcaster</h1>
  {#if error}<p role="alert">{error}</p>{/if}
  <form method="post" action="/admin/login">
    <label>API key <input type="password" name="key" required autofocus></label>
    <button type="submit">Log in</button>
  </form>
</article>
{/body}
{/include}
```

`src/main/resources/templates/AdminResource/shows.html`:

```html
{#include base}
{#title}Shows · Podcaster{/title}
{#body}
<h1>Shows</h1>
{#if shows.isEmpty}
<p>No shows yet. Create one below.</p>
{#else}
<table>
  <thead><tr><th>Name</th><th>Language</th><th>Model</th><th>Schedule</th><th>Feed</th></tr></thead>
  <tbody>
  {#for s in shows}
    <tr>
      <td><a href="/admin/shows/{s.id}">{s.name}</a>{#if !s.enabled} <small>(disabled)</small>{/if}</td>
      <td>{s.language}</td><td>{s.writerModel}</td><td><code>{s.cron ?: '—'}</code></td>
      <td><a href="/feeds/{s.slug}.xml">/feeds/{s.slug}.xml</a></td>
    </tr>
  {/for}
  </tbody>
</table>
{/if}
<h2>New show</h2>
{#showForm form=form errors=errors models=models voices=voices action='/admin/shows' submit='Create show' /}
{/body}
{/include}
```

`src/main/resources/templates/AdminResource/show.html`:

```html
{#include base}
{#title}{show.name} · Podcaster{/title}
{#body}
<hgroup>
  <h1>{show.name}</h1>
  <p>Podcast feed: <a href="/feeds/{show.slug}.xml">/feeds/{show.slug}.xml</a></p>
</hgroup>
<p><button hx-post="/admin/shows/{show.id}/run" hx-target="#runs" hx-swap="outerHTML">Run now</button></p>

<h2>Runs</h2>
<div id="runs" hx-get="/admin/shows/{show.id}/runs" hx-trigger="every 5s" hx-swap="outerHTML">
  {#runTable runs=runs /}
</div>

<h2>Episodes</h2>
{#if episodes.isEmpty}<p>No episodes yet.</p>{/if}
<ul>
{#for e in episodes}
  <li><a href="/admin/episodes/{e.id}">{e.title ?: 'Untitled episode'}</a> <small>{e.publishedAt ?: 'not published'}</small></li>
{/for}
</ul>

<h2>Sources</h2>
<table>
  <thead><tr><th>Type</th><th>Config</th><th>Last fetch</th><th>Last error</th><th></th></tr></thead>
  <tbody>
  {#for src in sources}
    <tr>
      <td>{src.connectorType}</td>
      <td><small>{#for entry in src.config.entrySet}{entry.key}={entry.value} {/for}</small></td>
      <td><small>{src.lastFetchedAt ?: '—'}</small></td>
      <td><small>{src.lastError ?: ''}</small></td>
      <td>
        <button class="outline" hx-post="/admin/sources/{src.id}/test" hx-target="#source-test">Test</button>
        <form method="post" action="/admin/sources/{src.id}/delete" style="display:inline"><button class="secondary outline" type="submit">Delete</button></form>
      </td>
    </tr>
  {/for}
  </tbody>
</table>
<div id="source-test"></div>
<details>
  <summary>Add source</summary>
  <form method="post" action="/admin/shows/{show.id}/sources">
    <label>Connector
      <select name="connectorType">{#for c in connectors}<option value="{c}">{c}</option>{/for}</select>
    </label>
    <label>Config (one <code>key=value</code> per line) <textarea name="config" rows="3" placeholder="url=https://www.ilpost.it/italia/feed/"></textarea></label>
    <label><input type="checkbox" name="fetchFullText" checked> Fetch full article text</label>
    <button type="submit">Add source</button>
  </form>
</details>

<h2>Settings</h2>
{#showForm form=form errors=errors models=models voices=voices action=formAction submit='Save' /}
<form method="post" action="/admin/shows/{show.id}/delete" onsubmit="return confirm('Delete this show, its sources and episodes?')">
  <button class="secondary" type="submit">Delete show</button>
</form>
{/body}
{/include}
```

`src/main/resources/templates/AdminResource/runs.html`:

```html
<div id="runs" hx-get="/admin/shows/{showId}/runs" hx-trigger="every 5s" hx-swap="outerHTML">
  {#runTable runs=runs /}
</div>
```

`src/main/resources/templates/AdminResource/sourceTest.html`:

```html
<article>
  {#if result.error}<p role="alert"><strong>Error:</strong> {result.error}</p>{/if}
  <p>{result.items.size} item(s) in the last 7 days</p>
  <ul>{#for i in result.items}<li><a href="{i.url}">{i.title}</a> <small>{i.publishedAt ?: ''}</small></li>{/for}</ul>
  {#if result.sampleText}
  <details><summary>Extracted text of the first item</summary><p class="script">{result.sampleText}</p></details>
  {/if}
</article>
```

`src/main/resources/templates/AdminResource/episode.html`:

```html
{#include base}
{#title}{episode.title ?: 'Episode'} · {show.name}{/title}
{#body}
<p><a href="/admin/shows/{show.id}">← {show.name}</a></p>
<h1>{episode.title ?: 'Untitled episode'}</h1>
{#if episode.audioPath}
<audio controls preload="none" src="/media/{episode.audioPath}" style="width:100%"></audio>
<p><small>{episode.durationSeconds ?: 0} s · <a href="/api/episodes/{episode.id}/audio">Download MP3</a></small></p>
{/if}
<h2>Show notes</h2>
<pre>{episode.description ?: ''}</pre>
<h2>Script</h2>
<div class="script">{episode.script ?: ''}</div>
{/body}
{/include}
```

- [ ] **Step 7: Run tests**

Run: `bash -lc './mvnw -q test -Dtest=AdminTest'`
Expected: PASS. Qute validates checked templates at build time — fix any reported expression errors in the template it names.

- [ ] **Step 8: Run the full suite**

Run: `bash -lc './mvnw -q test'`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "Add admin web UI with Qute and htmx

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01LbcoMupq1VXnfzdqroFmyp"
```

---

### Task 19: Docker Compose deployment and README

**Files:**
- Create: `docker/podcaster/Dockerfile`, `docker/piper/Dockerfile`, `docker/piper/entrypoint.sh`, `docker-compose.yml`, `.env.example`
- Replace: `.dockerignore`, `README.md`

**Interfaces:**
- Consumes: the whole application; env var names from `application.yml` (Task 1).
- Produces: `docker compose up -d --build` starts `podcaster` (port `${PODCASTER_PORT:-8080}`), `postgres`, `piper`, and optionally `ollama` (`--profile ollama`).

- [ ] **Step 1: Write `docker/podcaster/Dockerfile`**

```dockerfile
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline || true
COPY src src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:25-jre
RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /build/target/quarkus-app/lib/ lib/
COPY --from=build /build/target/quarkus-app/*.jar ./
COPY --from=build /build/target/quarkus-app/app/ app/
COPY --from=build /build/target/quarkus-app/quarkus/ quarkus/
RUN useradd --system --uid 185 podcaster \
 && mkdir -p /data/audio /data/work \
 && chown -R 185 /data /app
USER 185
EXPOSE 8080
ENV JAVA_OPTS="-Dquarkus.http.host=0.0.0.0"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/quarkus-run.jar"]
```

If the `maven:3.9-eclipse-temurin-25` tag is unavailable, use the newest `maven:*-eclipse-temurin-25` tag listed on Docker Hub.

- [ ] **Step 2: Write the Piper image**

`docker/piper/Dockerfile`:

```dockerfile
FROM python:3.12-slim
RUN pip install --no-cache-dir "piper-tts[http]==1.8.0"
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh
VOLUME /voices
EXPOSE 5000
ENTRYPOINT ["/entrypoint.sh"]
```

`docker/piper/entrypoint.sh`:

```sh
#!/bin/sh
set -e
VOICE="${PIPER_DEFAULT_VOICE:-it_IT-paola-medium}"
for v in $VOICE $PIPER_EXTRA_VOICES; do
  if [ ! -f "/voices/$v.onnx" ]; then
    echo "Downloading Piper voice $v"
    python -m piper.download_voices "$v" --data-dir /voices
  fi
done
exec python -m piper.http_server --host 0.0.0.0 --port 5000 --data-dir /voices -m "$VOICE"
```

- [ ] **Step 3: Write `docker-compose.yml`**

```yaml
services:
  podcaster:
    build:
      context: .
      dockerfile: docker/podcaster/Dockerfile
    env_file: .env
    environment:
      DB_URL: jdbc:postgresql://postgres:5432/podcaster
      PIPER_URL: http://piper:5000
    ports:
      - "${PODCASTER_PORT:-8080}:8080"
    volumes:
      - audio:/data/audio
      - work:/data/work
    depends_on:
      postgres:
        condition: service_healthy
      piper:
        condition: service_started
    restart: unless-stopped

  postgres:
    image: postgres:17
    environment:
      POSTGRES_DB: podcaster
      POSTGRES_USER: ${DB_USER:-podcaster}
      POSTGRES_PASSWORD: ${DB_PASSWORD:-podcaster}
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${DB_USER:-podcaster} -d podcaster"]
      interval: 5s
      retries: 20
    restart: unless-stopped

  piper:
    build: docker/piper
    environment:
      PIPER_DEFAULT_VOICE: ${PIPER_DEFAULT_VOICE:-it_IT-paola-medium}
      PIPER_EXTRA_VOICES: ${PIPER_EXTRA_VOICES:-}
    volumes:
      - voices:/voices
    restart: unless-stopped

  ollama:
    image: ollama/ollama
    profiles: ["ollama"]
    volumes:
      - ollama:/root/.ollama
    restart: unless-stopped

volumes:
  audio: {}
  work: {}
  pgdata: {}
  voices: {}
  ollama: {}
```

- [ ] **Step 4: Write `.env.example` and `.dockerignore`**

`.env.example`:

```bash
# --- required ---
PODCASTER_API_KEY=change-me-to-a-long-random-string
# URL your phone uses to reach this server (used in feed/enclosure links)
PODCASTER_BASE_URL=http://192.168.1.10:8080
PODCASTER_PORT=8080

# --- database ---
DB_USER=podcaster
DB_PASSWORD=change-me

# --- Piper voices (downloaded on first start) ---
PIPER_DEFAULT_VOICE=it_IT-paola-medium
PIPER_EXTRA_VOICES=en_US-lessac-medium

# --- LLM slots: enable at least one ---
# gpt: any OpenAI-compatible API (OpenAI, OpenRouter, vLLM, LM Studio...)
GPT_ENABLED=false
GPT_API_KEY=
GPT_BASE_URL=https://api.openai.com/v1/
GPT_MODEL=gpt-5
# claude: Anthropic
CLAUDE_ENABLED=false
ANTHROPIC_API_KEY=
CLAUDE_MODEL=claude-sonnet-5-5
# gemini: Google AI
GEMINI_ENABLED=false
GEMINI_API_KEY=
GEMINI_MODEL=gemini-2.5-flash
# local: Ollama (start with: docker compose --profile ollama up -d)
LOCAL_ENABLED=false
OLLAMA_BASE_URL=http://ollama:11434
LOCAL_MODEL=qwen3:14b

# --- Telegram failure alerts (optional) ---
TELEGRAM_BOT_TOKEN=
TELEGRAM_CHAT_ID=
```

`.dockerignore`:

```
target/
.git/
.idea/
.vscode/
*.iml
docs/
docker/piper/
.env
```

- [ ] **Step 5: Replace `README.md`**

```markdown
# Podcaster

Self-hosted service that turns news sources into a daily ~20-minute podcast episode.

**Pipeline per Show:** ingest (RSS + pluggable connectors, full-text extraction) → rank & cluster stories (LLM) → write a single-narrator script (LLM) → synthesize with Piper TTS → publish to a private podcast feed.

## Quick start

1. `cp .env.example .env` and set `PODCASTER_API_KEY`, `PODCASTER_BASE_URL`, DB password, and enable at least one LLM slot.
2. `docker compose up -d --build` (add `--profile ollama` for a local Ollama).
3. Open `http://<server>:8080/admin`, log in with the API key, create a Show, add sources, press **Run now**.
4. Subscribe to `http://<server>:8080/feeds/<slug>.xml` in your podcast app (AntennaPod, Pocket Casts, …). Keep the service on your LAN/VPN: feeds and audio are not authenticated.

Example sources: ANSA `https://www.ansa.it/sito/ansait_rss.xml`, Il Post sections `https://www.ilpost.it/italia/feed/`, `https://www.ilpost.it/mondo/feed/`.

## LLM slots

| Slot | Provider | Env |
|---|---|---|
| `gpt` | OpenAI-compatible | `GPT_ENABLED`, `GPT_API_KEY`, `GPT_BASE_URL`, `GPT_MODEL` |
| `claude` | Anthropic | `CLAUDE_ENABLED`, `ANTHROPIC_API_KEY`, `CLAUDE_MODEL` |
| `gemini` | Google AI Gemini | `GEMINI_ENABLED`, `GEMINI_API_KEY`, `GEMINI_MODEL` |
| `local` | Ollama | `LOCAL_ENABLED`, `OLLAMA_BASE_URL`, `LOCAL_MODEL` |

Each Show picks a writer model and optionally a cheaper ranker model by slot name. Changing the model behind a slot = edit `.env` + restart. Adding a new slot = add it to `application.yml` and rebuild.

## Voices

Piper voices are downloaded on first start (`PIPER_DEFAULT_VOICE`, `PIPER_EXTRA_VOICES`). Browse voices at https://huggingface.co/rhasspy/piper-voices. Episode length self-calibrates per voice after a couple of episodes.

## Adding a source connector

Implement `org.roncax.podcaster.ingestion.SourceConnector` as an `@ApplicationScoped` bean:

```java
@ApplicationScoped
public class ExampleSiteConnector implements SourceConnector {
    @Inject HttpFetcher fetcher;
    public String type() { return "site:example"; }
    public List<RawItem> fetch(SourceConfig config, Instant since) throws Exception {
        // fetch listing page(s), return RawItem(url, title, author, publishedAt, summary, fullText)
    }
}
```

It then appears in the admin UI connector list. For cleaner article text on a specific site, implement `ContentExtractor` instead (see `AnsaContentExtractor`).

## API

REST API under `/api` (header `X-API-Key`), documented at `/q/swagger-ui`.

## Development

Requires JDK 25, Docker (Dev Services PostgreSQL) and ffmpeg.

- `./mvnw quarkus:dev` — dev mode (Dev UI at `/q/dev-ui`); point `PIPER_URL` at a running Piper.
- `./mvnw test` — full test suite (no real LLM/Piper/network calls).
```

- [ ] **Step 6: Validate compose and build images**

Run: `bash -lc 'cp -n .env.example .env; docker compose config -q && docker compose build'`
Expected: config validates; both images build.

- [ ] **Step 7: Smoke-test the stack**

```bash
bash -lc 'docker compose up -d && for i in $(seq 1 60); do curl -fs localhost:8080/q/health >/dev/null && break; sleep 2; done; \
  curl -s localhost:8080/q/health; echo; \
  curl -s -H "X-API-Key: $(grep ^PODCASTER_API_KEY .env | cut -d= -f2)" localhost:8080/api/meta/voices; echo'
```

Expected: health `{"status":"UP",...}`; voices list contains `it_IT-paola-medium` (the first Piper start downloads it — retry after a minute if empty/503).

- [ ] **Step 8: Optional end-to-end run with a real model (only with the user's API key)**

Ask the user whether to enable a slot in `.env` (e.g. `CLAUDE_ENABLED=true` + key). If yes: `docker compose up -d`, create a Show in the admin UI with an ANSA and an Il Post source, press **Run now**, and confirm the run reaches `DONE` and the episode plays from the feed. Otherwise skip and say so.

- [ ] **Step 9: Stop the stack and commit**

```bash
bash -lc 'docker compose down'
git add docker docker-compose.yml .env.example .dockerignore README.md
git commit -m "Add Docker Compose deployment and README

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01LbcoMupq1VXnfzdqroFmyp"
```
