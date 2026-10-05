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
| Scraping | One Java class per site implementing `SourceConnector`; site-specific `ContentExtractor`s for cleaner article text |
| v1 sources | Generic `rss` + ANSA extractor; covers ANSA and Il Post (section feeds). Reddit deferred |
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

**`ContentExtractor`** — `boolean supports(String url)` + `Optional<String> extract(String url, String html)`; extractors are selected by URL host (most specific first), falling back to the default Jsoup implementation that picks the main content block (article/main tags, text-density heuristic) and strips nav/ads/scripts.

**HTTP fetching** — all connector/extractor HTTP goes through one shared client with a configurable browser-like `User-Agent`, per-host politeness delay (default 1 s), timeouts and retries.

### 3.1.1 v1 sources and connectors

Built in v1:
- `rss` — generic RSS/Atom connector.
- `AnsaContentExtractor` — host `ansa.it`; site-specific selectors for article body, dropping related-links/boxes.

Example sources (verified 2026-10-06 from the dev machine):

| Site | Source | Notes |
|---|---|---|
| ANSA | `rss`, e.g. `https://www.ansa.it/sito/ansait_rss.xml` (plus per-section feeds) | Feed says "for personal use only"; items carry only title + description → full-text extraction via `AnsaContentExtractor` |
| Il Post | `rss`, per-section feeds `https://www.ilpost.it/<section>/feed/` (e.g. `italia`, `mondo`, `politica`, `economia`, `tecnologia`) | Main `/feed/` returns 403; section feeds work, 10 items each, no `content:encoded` → generic extractor (Next.js pages; verify extraction quality, add `IlPostContentExtractor` only if needed) |

Deferred (decided design, not built in v1):
- `reddit` connector via the official OAuth API ("script" app, client id/secret in `.env`). Config: subreddit, sort (`top`/`hot`), time window, `minScore`, `topComments` (N). Item = post title + self-text (+ linked article text for link posts) + top N comments, so episodes can mention community reactions. Anonymous `.json` returns 403; `.rss` works but lacks scores/comments.

**`TtsEngine`** — `AudioChunk synthesize(String text, VoiceConfig voice)`; v1 implementation `PiperHttpTtsEngine`.

**`AudioStorage`** — `store`, `open` (with range support), `delete`; v1 `LocalAudioStorage` rooted at `/data/audio`.

**`Notifier`** — `notifyFailure(Run, String message)`; v1 `TelegramNotifier` (Bot API `sendMessage`), no-op if not configured.

### 3.2 LLM configuration

Models are declared as quarkus-langchain4j named models. Extensions: `quarkus-langchain4j-openai`, `quarkus-langchain4j-anthropic`, `quarkus-langchain4j-ai-gemini`, `quarkus-langchain4j-ollama`.

```yaml
quarkus:
  langchain4j:
    # slot name → provider (build time)
    claude:
      chat-model:
        provider: anthropic
    local:
      chat-model:
        provider: ollama
    # provider settings per slot (runtime, env-driven)
    anthropic:
      claude:
        enable-integration: ${CLAUDE_ENABLED:false}
        api-key: ${ANTHROPIC_API_KEY:unset}
        chat-model:
          model-name: ${CLAUDE_MODEL:claude-sonnet-5-5}
          max-tokens: 4096
    ollama:
      local:
        enable-integration: ${LOCAL_ENABLED:false}
        base-url: ${OLLAMA_BASE_URL:http://ollama:11434}
        chat-model:
          model-name: ${LOCAL_MODEL:qwen3:14b}
```
Slot names deliberately differ from provider config prefixes (`openai`, `anthropic`, `ai.gemini`, `ollama`) to avoid key collisions. Shipped slots: `gpt` (openai-compatible, configurable base URL), `claude` (anthropic), `gemini` (ai-gemini), `local` (ollama).

- Model **names and their providers are build-time config** in quarkus-langchain4j (beans are generated at build). The shipped `application.yml` therefore defines four **model slots** — `gpt`, `claude`, `gemini`, `local` — whose runtime properties (model name, base URL, API key, temperature, timeout) come from env vars, and each slot is switched on/off at runtime with `enable-integration` (`*_ENABLED` env vars, default `false`). Swapping the model behind a slot = env change + restart; adding a new slot name = config edit + image rebuild.
- `ChatModelRegistry` resolves `@ModelName(name) ChatModel` via CDI `Instance` and exposes `availableNames()` (slots whose integration is enabled) for validation and UI dropdowns. `ChatModel` is marked unremovable (`quarkus.arc.unremovable-types`) so slot beans survive unused-bean removal.
- Show validation rejects unknown or disabled model names.
- Switching a Show's model = DB edit.

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
- `since` (ingestion window start, fixed at run creation so retries use the same window), `attempt`, `error`, `started_at`, `finished_at`

**episode**
- `run_id`, `show_id`, `title`, `description` (show notes incl. source links)
- `selection` (JSONB: clusters, ranking, chosen item ids), `outline` (JSONB: segments + word budgets), `script_parts` (JSONB array: intro, segments, outro — TTS-normalized), `script` (text: parts joined, for display)
- `audio_path`, `duration_seconds`, `size_bytes`, `published_at` (null until PUBLISH)

**voice_calibration**
- `voice_id`, `length_scale` (unique together), `words_per_minute` (initial 150), `samples`

## 5. Pipeline

A Run moves through stages; each stage persists its output before advancing. Retry resumes at the failed stage using persisted outputs.

1. **INGEST**
   - `since` = start time of the Show's last successful (`DONE`) run minus a 1 h overlap (so items published while that run was executing are not missed; dedupe prevents repeats), else now − 24h. Fixed at run creation (`run.since`).
   - For each enabled source: `fetch(config, since)`; dedupe by (`show_id`, `url`) and by `content_hash` of normalized title+text; extract full text when `fetch_full_text` and the connector doesn't provide it.
   - Per-source failures are recorded on `source.last_error` and included in a Telegram warning; the run fails only if every source fails.
2. **SELECT**
   - Candidates: items of the Show with `used_in_episode_id IS NULL` and `published_at >= since` (fallback `fetched_at` when no publish date).
   - If count < `min_items` → status `SKIPPED`, no episode, no alert.
   - One ranker-model call with titles + first ~500 chars per item + focus prompt → structured JSON: clusters (item ids, headline, importance score). Stored in `episode.selection`.
3. **SCRIPT** (writer model, Show language, spoken style, no markdown/URLs)
   - Word budget = `target_duration_minutes × words_per_minute(voice, length_scale)`.
   - **Outline** (deterministic, no LLM call): reserve ~200 words for intro/outro, take clusters by importance while each gets ≥ 250 words, allocate the rest proportionally to importance, capped at 900 words per segment. With few clusters the total shrinks — no padding. Stored in `episode.outline`.
   - **Segment** calls: one per segment, given cluster source texts (truncated to a per-call context limit), word allocation, and the previous segment's closing lines for transitions.
   - **Intro/outro** call after segments, plus episode title and short description.
   - **TTS normalization**: strip markdown remnants and URLs, normalize symbols and abbreviations, collapse whitespace.
4. **TTS**
   - Split script into chunks ≤ ~500 chars on sentence boundaries; segment boundaries marked for longer pauses.
   - Synthesize via Piper HTTP with voice + `length_scale`, parallelism configurable (default 2). WAV chunks are kept in a per-run temp dir so a retry only resynthesizes missing chunks.
   - Concatenate the WAV PCM in Java with silence (≈0.4 s between chunks, ≈1.2 s between segments); duration is computed exactly from the PCM length. ffmpeg encodes MP3 mono 64 kbps with ID3 tags (show, title, date).
   - Update `voice_calibration` (`wpm = words / minutes`; first sample replaces the default, then exponential moving average, α = 0.3).
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

Cron expressions use standard 5-field Unix syntax (`quarkus.scheduler.cron-type=unix`, `start-mode=forced` since there are no `@Scheduled` methods). Quarkus Scheduler programmatic API: register one job per enabled Show at startup; re-register on Show create/update/delete. Missed runs during downtime are not caught up.

## 7. Error handling

- External calls have timeouts and retries with exponential backoff: LangChain4j's client timeouts for LLMs; a small shared `Retries` helper (configurable attempts/delay) for Piper, HTTP fetches and the ranker's JSON repair round-trip.
- A stage failing after retries → run `FAILED` with error message; `Notifier` sends Telegram message (show, stage, error, run URL).
- Partial source failures → Telegram warning, run continues.
- Structured-output parse failure from the ranker → one repair retry with the parse error in the prompt, then fail.
- Telegram config (`TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`) optional; absent → notifications disabled with a startup log line.

## 8. Deployment

- `docker-compose.yml`: `podcaster`, `postgres`, `piper`, optional `ollama` (profile `ollama`).
- `podcaster` image: from `src/main/docker/Dockerfile.jvm`, adding ffmpeg. Volume `/data/audio`. Port 8080.
- `piper` image: `docker/piper/Dockerfile` — python-slim, `pip install piper-tts[http]==1.8.0`, runs `python -m piper.http_server --data-dir /voices -m $PIPER_DEFAULT_VOICE` (downloads the default voice on first start). Internal network only. API (verified against piper1-gpl source): `POST /synthesize` JSON `{text, voice, length_scale}` → WAV; `GET /voices` → object keyed by voice id.
- Config via `.env` (template `.env.example`): DB credentials, LLM API keys, `PODCASTER_API_KEY`, Telegram settings, base URL used in feed links.
- Voices: Piper `.onnx` + `.onnx.json` files downloaded into the voices volume (documented in README).

## 9. Testing

- **Unit**: RSS parsing (fixture feeds), content extraction (saved HTML), word budget + calibration math, TTS normalization and chunking, outline allocation, feed XML rendering.
- **Generation**: fake `ChatModel` returning scripted responses → verifies prompts contain language/focus/budgets, structured parsing, persisted outputs.
- **Integration** (`@QuarkusTest`, Dev Services PostgreSQL): full run with fake ChatModel + WireMock Piper (returns short WAV) → stage transitions, resume after a TTS failure without new LLM calls, dedupe across two runs, `SKIPPED` path, feed XML, retention.
- **Connector contract**: each `site:*` plugin and site-specific extractor tested against fixture HTML (v1: saved ANSA and Il Post article pages, ANSA and Il Post feed XML).
- No real-LLM calls in automated tests; a `dev` profile for manual runs against real models.

## 10. Out of scope (v1)

Reddit connector (designed in §3.1.1); site scraper plugins (`site:*` SPI exists, none built); two-host dialogue format; script review/approval step; S3 storage; JavaScript-rendered scraping; public exposure/auth beyond API key; multi-user; catch-up of missed scheduled runs.
