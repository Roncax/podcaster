# Podcaster — Design Spec

Date: 2026-10-06
Status: Approved design, pending implementation plan

## 1. Purpose

A self-hosted service that ingests news from pluggable sources (RSS, per-site scrapers, extensible), uses a selectable LLM to write a ~20-minute single-narrator news episode, synthesizes it with self-hosted Piper TTS, and publishes it as a private podcast feed that a phone podcast app subscribes to.

Single user, personal use, runs on the owner's servers via Docker Compose, reachable only over LAN/VPN.

### Success criteria

- Each configured **Show** produces an episode on its cron schedule and on manual trigger.
- Episodes land within ±15% of the target duration after voice calibration converges (2–3 episodes).
- No news item is used in more than one episode of the same Show.
- A new RSS source requires only a DB row; a new scraped site requires one Java class + a DB row; a new LLM requires only config.
- Failures are reported via Telegram and runs can be resumed from the failed stage without repeating completed stages.

## 2. Key decisions

| Topic | Decision |
|---|---|
| Unit of output | **Show**: own sources, language, voice, models, cron; plus manual trigger |
| Delivery | Podcast RSS feed per Show + REST download |
| Language | Per Show (sources may be any language; LLM writes in the Show's language) |
| LLM providers (v1) | OpenAI-compatible, Anthropic, Google Gemini, Ollama |
| LLM config | Native quarkus-langchain4j **named models** in `application.yml`; Show references model names |
| Show/source management | PostgreSQL + REST API + simple admin UI (Qute + htmx) |
| Ingestion depth | Full article text extraction (Jsoup, static HTML only) |
| Scraping | One Java class per site implementing `SourceConnector` |
| Format | Single narrator, news-bulletin style |
| TTS | Piper HTTP server, bundled as a container |
| Network | LAN/VPN only |
| Storage | Local Docker volume behind `AudioStorage` interface |
| Retention | Keep last N episodes per Show (audio deleted, metadata kept) |
| DB | PostgreSQL + Flyway |
| Failure alerts | Telegram |
| Intermediate artifacts | Stored and viewable; publishing fully automatic |
| Low news | Shorter episode; skip run below `minItems` |
| Architecture | Modular monolith, persisted resumable stage machine |

## 3. Architecture

```
docker-compose
 ├─ podcaster (Quarkus, JVM image + ffmpeg)
 │   ├─ ingestion   SourceConnector SPI: rss, site:<name> plugins
 │   ├─ extraction  ContentExtractor (Jsoup readability-style)
 │   ├─ generation  rank/cluster → outline → segments → intro/outro
 │   ├─ llm         ChatModelRegistry over quarkus-langchain4j named models
 │   ├─ tts         TtsEngine SPI: PiperHttpTtsEngine; ffmpeg concat/encode
 │   ├─ publishing  AudioStorage, podcast RSS feed, retention
 │   ├─ runs        scheduler + persisted stage machine + worker pool
 │   ├─ notify      Notifier SPI: TelegramNotifier
 │   └─ admin       REST API (OpenAPI) + Qute/htmx UI
 ├─ postgres:17
 ├─ piper  (custom image: python-slim + piper-tts[http], /voices volume)
 └─ ollama (optional, compose profile)
```

Base package: `org.roncax.podcaster`, one sub-package per module. Existing scaffold code (`Bot`, `GreetingConfig`, `GreetingResource`, `MyEntity`, `import.sql`, `easy-rag` dependency and `easy-rag-catalog/`) is removed.

### 3.1 Extension points

**`SourceConnector`** (CDI bean, discovered automatically)
```java
public interface SourceConnector {
    String type();                                   // "rss", "site:ansa", ...
    List<RawItem> fetch(SourceConfig config, Instant since);
    default boolean providesFullText() { return false; }
}
```
- `RssSourceConnector` (`rss`): config `{ "url": "..." }`; parses RSS/Atom (Rome library).
- Site plugins (`site:<name>`): one class per site; config is free-form JSONB interpreted by the plugin. A plugin may return full text directly or leave it to `ContentExtractor`.
- `RawItem`: url, title, author, publishedAt, summary, optional fullText.

**`ContentExtractor`** — `Optional<String> extract(String url, String html)`; default Jsoup implementation that picks the main content block (article/main tags, text-density heuristic), strips nav/ads/scripts. Plugins may supply their own.

**`TtsEngine`** — `AudioChunk synthesize(String text, VoiceConfig voice)`; v1 implementation `PiperHttpTtsEngine`.

**`AudioStorage`** — `store`, `open` (with range support), `delete`; v1 `LocalAudioStorage` rooted at `/data/audio`.

**`Notifier`** — `notifyFailure(Run, String message)`; v1 `TelegramNotifier` (Bot API `sendMessage`), no-op if not configured.

### 3.2 LLM configuration

Models are declared as quarkus-langchain4j named models. Extensions: `quarkus-langchain4j-openai`, `quarkus-langchain4j-anthropic`, `quarkus-langchain4j-ai-gemini`, `quarkus-langchain4j-ollama`.

```yaml
quarkus:
  langchain4j:
    writer:
      chat-model:
        provider: anthropic
    ranker:
      chat-model:
        provider: ollama
    anthropic:
      writer:
        api-key: ${ANTHROPIC_API_KEY}
        chat-model:
          model-name: claude-sonnet-5-5
          max-tokens: 4096
    ollama:
      ranker:
        base-url: http://ollama:11434
        chat-model:
          model-name: qwen3:14b
```

- `ChatModelRegistry` resolves `@ModelName(name) ChatModel` via CDI `Instance` and exposes `availableNames()` (read from config) for validation and UI dropdowns.
- Show validation rejects unknown model names.
- Adding/changing a model = config edit + restart. Switching a Show's model = DB edit.
- Verify during implementation: named-model beans are resolvable programmatically (not removed as unused beans); mark unremovable if needed.

## 4. Data model

All tables have `id` (bigserial/UUID), `created_at`, `updated_at`. Migrations via Flyway.

**show**
- `name`, `slug` (unique), `description`
- `language` (BCP-47, e.g. `it`), `voice_id` (Piper voice file name), `length_scale` (default 1.0)
- `writer_model`, `ranker_model` (nullable → writer), `focus_prompt` (nullable)
- `target_duration_minutes` (default 20), `min_items` (default 3)
- `cron`, `enabled`
- `retain_episodes` (default 30), `feed_token` (reserved; unused while LAN-only)

**source**
- `show_id`, `connector_type`, `config` (JSONB), `fetch_full_text` (default true), `enabled`
- `last_fetched_at`, `last_error`

**item**
- `show_id`, `source_id`, `url`, `content_hash`, `title`, `author`, `published_at`, `fetched_at`
- `summary`, `full_text`, `used_in_episode_id` (nullable)
- unique (`show_id`, `url`); index (`show_id`, `content_hash`)

**run**
- `show_id`, `trigger` (`SCHEDULED` | `MANUAL`)
- `stage` (`INGEST` | `SELECT` | `SCRIPT` | `TTS` | `PUBLISH`), `status` (`RUNNING` | `DONE` | `FAILED` | `SKIPPED`)
- `attempt`, `error`, `started_at`, `finished_at`

**episode**
- `run_id`, `show_id`, `title`, `description` (show notes incl. source links)
- `selection` (JSONB: clusters, ranking, chosen item ids), `outline` (JSONB: segments + word budgets), `script` (text)
- `audio_path`, `duration_seconds`, `size_bytes`, `published_at` (null until PUBLISH)

**voice_calibration**
- `voice_id`, `length_scale` (unique together), `words_per_minute` (initial 150), `samples`

## 5. Pipeline

A Run moves through stages; each stage persists its output before advancing. Retry resumes at the failed stage using persisted outputs.

1. **INGEST**
   - `since` = `published_at` of the Show's last published episode, else now − 24h.
   - For each enabled source: `fetch(config, since)`; dedupe by (`show_id`, `url`) and by `content_hash` of normalized title+text; extract full text when `fetch_full_text` and the connector doesn't provide it.
   - Per-source failures are recorded on `source.last_error` and included in a Telegram warning; the run fails only if every source fails.
2. **SELECT**
   - Candidates: items of the Show with `used_in_episode_id IS NULL` and `published_at >= since` (fallback `fetched_at` when no publish date).
   - If count < `min_items` → status `SKIPPED`, no episode, no alert.
   - One ranker-model call with titles + first ~500 chars per item + focus prompt → structured JSON: clusters (item ids, headline, importance score). Stored in `episode.selection`.
3. **SCRIPT** (writer model, Show language, spoken style, no markdown/URLs)
   - Word budget = `target_duration_minutes × words_per_minute(voice, length_scale)`.
   - **Outline** call: pick top clusters that fit the budget, allocate words per segment (min ~250 words/segment). With few clusters the total budget shrinks — no padding.
   - **Segment** calls: one per segment, given cluster source texts (truncated to a per-call context limit), word allocation, and the previous segment's closing lines for transitions.
   - **Intro/outro** call after segments, plus episode title and short description.
   - **TTS normalization**: strip markdown remnants and URLs, normalize symbols and abbreviations, collapse whitespace.
4. **TTS**
   - Split script into chunks ≤ ~500 chars on sentence boundaries; segment boundaries marked for longer pauses.
   - Synthesize via Piper HTTP with voice + `length_scale`, parallelism configurable (default 2). WAV chunks are kept in a per-run temp dir so a retry only resynthesizes missing chunks.
   - ffmpeg: concat with silence (≈0.4 s between chunks, ≈1.2 s between segments), encode MP3 mono 64 kbps with ID3 tags (show, title, date).
   - ffprobe measures duration → update `voice_calibration` (`wpm = words / minutes`, exponential moving average, α = 0.3).
5. **PUBLISH**
   - Store at `/data/audio/<show-slug>/<episode-id>.mp3`, set `audio_path`, `size_bytes`, `duration_seconds`, `published_at`.
   - Mark selected items `used_in_episode_id`.
   - Retention: delete audio of episodes beyond the newest `retain_episodes`; keep metadata (`audio_path` nulled).

**Concurrency**: at most one `RUNNING` run per Show (DB row lock / partial unique index); runs execute on a bounded managed executor (default 2 workers). A manual trigger while a run is active returns 409.

**Startup recovery**: runs found `RUNNING` at startup are marked `FAILED` ("interrupted by restart") and can be retried.

## 6. Interfaces

### 6.1 REST API (`/api`, `X-API-Key` header, OpenAPI + Swagger UI)

| Method | Path | Purpose |
|---|---|---|
| GET/POST | `/shows` | list / create |
| GET/PUT/DELETE | `/shows/{id}` | read / update / delete |
| GET/POST | `/shows/{id}/sources` | list / add source |
| PUT/DELETE | `/sources/{id}` | update / delete source |
| POST | `/sources/{id}/test` | dry-run fetch, returns sample items (nothing persisted) |
| POST | `/shows/{id}/runs` | manual trigger → 202 + run id (409 if one is active) |
| GET | `/runs?showId=&status=` | list runs |
| GET | `/runs/{id}` | run detail |
| POST | `/runs/{id}/retry` | resume failed run from its stage |
| GET | `/episodes?showId=` | list episodes |
| GET | `/episodes/{id}` | metadata, selection, outline, script |
| GET | `/episodes/{id}/audio` | MP3 download |
| GET | `/meta/models` · `/meta/connectors` · `/meta/voices` | configured model names, connector types, voices in `/voices` |

### 6.2 Admin UI

Qute templates + htmx (no JS build). Pages: Shows list/edit (dropdowns for model, voice, connector type), sources per Show with "test" button, run history with live status polling, episode detail with audio player and script. Access: enter API key once → session cookie.

### 6.3 Podcast feed

- `GET /feeds/{showSlug}.xml` — RSS 2.0 with `itunes:` tags (title, author, image optional, language, `enclosure` with length and `audio/mpeg`, `itunes:duration`, GUID = episode id).
- `GET /media/{showSlug}/{episodeId}.mp3` — supports HTTP Range requests.
- No auth on feed/media (LAN/VPN only); `feed_token` reserved for future exposure.

### 6.4 Scheduling

Quarkus Scheduler programmatic API: register one job per enabled Show at startup; re-register on Show create/update/delete. Missed runs during downtime are not caught up.

## 7. Error handling

- External calls have timeouts and retries with exponential backoff: LangChain4j built-in retries for LLMs; SmallRye Fault Tolerance (`@Retry`, `@Timeout`) for Piper and HTTP fetches.
- A stage failing after retries → run `FAILED` with error message; `Notifier` sends Telegram message (show, stage, error, run URL).
- Partial source failures → Telegram warning, run continues.
- Structured-output parse failure from the ranker → one repair retry with the parse error in the prompt, then fail.
- Telegram config (`TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`) optional; absent → notifications disabled with a startup log line.

## 8. Deployment

- `docker-compose.yml`: `podcaster`, `postgres`, `piper`, optional `ollama` (profile `ollama`).
- `podcaster` image: from `src/main/docker/Dockerfile.jvm`, adding ffmpeg. Volume `/data/audio`. Port 8080.
- `piper` image: `docker/piper/Dockerfile` — python-slim, `pip install piper-tts[http]`, runs the Piper HTTP server with `--data-dir /voices`. Internal network only. Exact request contract (text, voice, length_scale params) to be confirmed against the installed `piper-tts` version during implementation.
- Config via `.env` (template `.env.example`): DB credentials, LLM API keys, `PODCASTER_API_KEY`, Telegram settings, base URL used in feed links.
- Voices: Piper `.onnx` + `.onnx.json` files downloaded into the voices volume (documented in README).

## 9. Testing

- **Unit**: RSS parsing (fixture feeds), content extraction (saved HTML), word budget + calibration math, TTS normalization and chunking, outline allocation, feed XML rendering.
- **Generation**: fake `ChatModel` returning scripted responses → verifies prompts contain language/focus/budgets, structured parsing, persisted outputs.
- **Integration** (`@QuarkusTest`, Dev Services PostgreSQL): full run with fake ChatModel + WireMock Piper (returns short WAV) → stage transitions, resume after a TTS failure without new LLM calls, dedupe across two runs, `SKIPPED` path, feed XML, retention.
- **Connector contract**: each `site:*` plugin tested against fixture HTML.
- No real-LLM calls in automated tests; a `dev` profile for manual runs against real models.

## 10. Out of scope (v1)

Two-host dialogue format; script review/approval step; S3 storage; JavaScript-rendered scraping; public exposure/auth beyond API key; multi-user; catch-up of missed scheduled runs.
