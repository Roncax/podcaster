# Podcaster

Self-hosted service that turns news sources into a daily ~20-minute podcast episode.

**Pipeline per Show:** ingest (RSS + pluggable connectors, full-text extraction) → rank & cluster stories (LLM) → write a single-narrator script (LLM) → synthesize with Piper TTS → publish to a private podcast feed.

## Quick start

1. `cp .env.example .env` and set `PODCASTER_API_KEY`, `PODCASTER_BASE_URL`, the DB password, and enable at least one LLM slot.
2. `docker compose up -d --build` (add `--profile ollama` for a local Ollama).
3. Open `http://<server>:8080/admin`, log in with the API key, create a Show, add sources, press **Run now**.
4. Subscribe to `http://<server>:8080/feeds/<slug>.xml` in your podcast app (AntennaPod, Pocket Casts, …). Keep the service on your LAN/VPN: feeds and audio are not authenticated.

Example sources: ANSA `https://www.ansa.it/sito/ansait_rss.xml`, Il Post sections `https://www.ilpost.it/italia/feed/`, `https://www.ilpost.it/mondo/feed/`.

Reddit (no credentials needed): connector `reddit` with config `subreddit=italy` (optional `window=day|week`, `maxPosts`, `commentPosts`, `topComments`). Link posts use the linked article's text, substantial text posts their own text; top comments are added when Reddit allows (requests are spaced 6 s apart to respect rate limits).

## LLM slots

| Slot | Provider | Env |
|---|---|---|
| `gpt` | OpenAI-compatible | `GPT_ENABLED`, `GPT_API_KEY`, `GPT_BASE_URL`, `GPT_MODEL` |
| `claude` | Anthropic | `CLAUDE_ENABLED`, `ANTHROPIC_API_KEY`, `CLAUDE_MODEL` |
| `gemini` | Google AI Gemini | `GEMINI_ENABLED`, `GEMINI_API_KEY`, `GEMINI_MODEL` |
| `local` | Ollama | `LOCAL_ENABLED`, `OLLAMA_BASE_URL`, `LOCAL_MODEL` |

Cron schedules (5-field Unix syntax, e.g. `0 7 * * *`) and episode dates use the container timezone `TZ` (default `Europe/Rome`).

Each Show picks a writer model and optionally a cheaper ranker model by slot name. Changing the model behind a slot = edit `.env` + restart. Adding a new slot = add it to `application.yml` and rebuild.

## Prompts

Every prompt sent to the LLM (`rank`, `segment`, `framing`, `json_repair`) lives in the database as immutable versions, editable at `/admin/prompts` or via `/api/prompts`.

- Saving creates a new version labelled **draft**. Runs use **production**; promoting a version (or an older one, to roll back) takes effect on the next run, no restart.
- **Dry-run** renders a full script for a show with the draft prompts without publishing anything.
- A show can pin a specific version of any prompt (show page → Prompt overrides).
- Templates support plain variables (`{variable}`) and `{#if focus}…{#else}…{/if}` only: no property/method access, loops, includes or namespaces. Allowed variables are listed per prompt; `{contract}` (required in `rank` and `framing`) inserts the JSON format the app parses. Invalid templates are rejected on save.
- Each episode records the prompt versions that produced it (`promptVersions` in `/api/episodes/{id}`).

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

## Admin UI

`http://<server>:8080/admin` (log in with `PODCASTER_API_KEY`): dashboard of shows and runs, live run progress, sources per show (RSS, Reddit), episodes with a chapter player and per-story sources, prompt versions with diff, editor and dry-run, and read-only settings. Assets (Tailwind, fonts, htmx, CodeMirror) are bundled by the Quarkus Web Bundler at build time: no Node and no CDN, so it works offline on your LAN.

## Database UI

[Adminer](https://www.adminer.org/) runs alongside the stack at `http://<server>:8082` (`ADMINER_PORT`). Log in with System **PostgreSQL**, server `postgres`, username/password from `DB_USER`/`DB_PASSWORD` in `.env`, database `podcaster`. Edits made there bypass the app (e.g. prompt versions are meant to be immutable), so prefer it for reading.

## API

REST API under `/api` (header `X-API-Key`), documented at `/q/swagger-ui`.

## Development

Requires JDK 25 and ffmpeg on `PATH`.

- `./mvnw test` — full test suite. Tests start an embedded PostgreSQL (no Docker needed) and never call a real LLM, Piper or the internet.
- `./mvnw quarkus:dev` — dev mode (Dev UI at `/q/dev-ui`). Uses Dev Services PostgreSQL, which needs Docker; alternatively set `DB_URL`, `DB_USER`, `DB_PASSWORD` to an existing database, and point `PIPER_URL` at a running Piper.
