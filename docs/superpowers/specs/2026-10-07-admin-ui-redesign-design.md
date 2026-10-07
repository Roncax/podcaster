# Admin UI Redesign — Design Spec

Date: 2026-10-07
Status: Approved design, pending implementation plan
Branch: `feature/ui-redesign`
Parent spec: `docs/superpowers/specs/2026-10-06-podcaster-design.md`
Mockups: https://claude.ai/artifact/BG46ts34BVw2RWpCUVKa1c (Dashboard, Show, Episode, Prompts)

## 1. Purpose

Replace the rough Pico.css admin pages with the designed "on-air newsroom console" UI from the mockups, while keeping the server-rendered Qute + htmx architecture (option A). Add the backend data the new pages need: episode chapters, per-story show notes, and in-stage run progress.

### Success criteria

- Every admin page uses the shared layout (sidebar navigation, page header) and component tags; no Pico.css, no CDN requests (works offline on the LAN).
- A dashboard shows every show's status, latest episode (playable), recent runs and items needing attention.
- While a run is active, the show page shows a live 5-stage stepper with a progress line, updated within ~3 s.
- An episode page plays the MP3 with clickable chapters; each chapter lists its sources, including the Reddit thread when there is one.
- New episodes' show notes (feed description) list sources grouped by story.
- The prompt page shows a side-by-side diff and an editor that highlights `{variables}`.
- Forms still work without JavaScript; pages are usable at phone width; text meets WCAG AA contrast.

## 2. Decisions

| Topic | Decision |
|---|---|
| Architecture | Keep Qute server rendering + htmx fragments (option A); no SPA |
| Assets | `io.quarkiverse.web-bundler:quarkus-web-bundler` + `quarkus-web-bundler-tailwindcss` 2.3.6 (Tailwind 4, no Node); npm packages via mvnpm |
| JS | htmx (mvnpm `htmx.org`), two islands: audio player (custom element), CodeMirror 6 editor + `@codemirror/merge` diff |
| Fonts | IBM Plex Sans + Mono, bundled (no Google Fonts request) |
| Theme | Light only; colors and fonts defined as Tailwind `@theme` tokens so dark mode can be added later |
| Chapters | Recorded at TTS assembly; stored on the episode |
| Show notes | Per-story grouping (closes backlog item "Per-story source links in show notes") |
| Progress | `runs.progress` text updated by stages |

## 3. Frontend foundation

### 3.1 Asset pipeline

- `src/main/resources/web/app/app.css`: `@import "tailwindcss";` plus a `@theme` block. Tokens from the mockups:
  - colors: `ink #16202B`, `ground #F4F5F7`, `panel #FFFFFF`, `line #E3E7EC`, `muted #5B6878`, `accent #E4572E`, `ok #1E5E37/#E3F2E8`, `warn #8A4B00/#FFF4E5`, `fail #8F1D14/#FDE8E7`, `info #1F5F8B/#EEF4FB`, `run #1D4ED8/#E3ECFD`
  - fonts: `--font-sans: "IBM Plex Sans"`, `--font-mono: "IBM Plex Mono"`
  - radii: 8, 10, 14 px
- `web/app/app.js`: imports htmx and a small toast helper (htmx `HX-Trigger` → toast).
- Fonts: woff2 files for Plex Sans 400/500/600/700 and Plex Mono 400/500 under `web/public/fonts/`, declared with `@font-face` in `app.css`. Open Font License; `OFL.txt` is kept next to them.
- Islands, as separate Web Bundler entry points included only where needed (`{#bundle key="player"/}`, `{#bundle key="editor"/}`):
  - `web/player/player.js`: custom element `<podcast-player src chapters>` wrapping native `<audio>`. It provides play/pause, a seek bar, time, chapter list jump buttons, highlighting of the current chapter, and keyboard support (space, arrows).
  - `web/editor/editor.js`: mounts CodeMirror 6 on `textarea[data-prompt-editor]` (highlights `{name}` and `{#if …}`/`{/if}`, syncs the value back to the textarea for normal form submit) and `@codemirror/merge` on `[data-prompt-diff]` (production vs shown version, read-only).
- `templates/base.html`: `{#bundle /}` replaces the Pico and htmx CDN links.

### 3.2 Component tags (`src/main/resources/templates/tags/`)

| Tag | Purpose |
|---|---|
| `layout` | Sidebar (logo, nav with `aria-current`, footer with model/voice info) + main slot; `title`, `active` params |
| `pageHeader` | Breadcrumb, title, meta chips, action slot |
| `card` | Panel with optional title and action |
| `badge` | Status → color (`DONE`, `RUNNING`, `FAILED`, `SKIPPED`, `OK`, `WARN`, `INFO`) |
| `button` | `primary` (ink), `accent` (orange), `secondary` (outline); renders `<button>` or `<a>` |
| `stepper` | 5 stages with done, running, failed, skipped and pending states, plus a progress line |
| `field` | Label + input/select/textarea + error text |
| `emptyState` | Dashed box with message and action |
| `player` | Wraps `<podcast-player>` with server-rendered fallback (`<audio controls>` + chapter links) |

Existing `showForm` and `runTable` tags are replaced by these.

### 3.3 Accessibility and responsiveness

- Real `<button>`/`<a href>`/`<label>`.
- Icon-only buttons get `aria-label`.
- 44 px minimum touch targets.
- AA contrast (muted text `#5B6878` on white passes 4.5:1).
- Sidebar + content in a wrapping flex row: the sidebar stacks above the content at phone width.
- Wide tables scroll inside their card.

## 4. Pages

All under `/admin`, behind the existing API-key cookie filter.

| Page | Route | Content |
|---|---|---|
| Dashboard | `GET /admin` (was a redirect) | Show cards: status badge of last run, latest episode mini player, next run (from scheduler), unused fresh items count, Run now / Open. Recent runs (last 8, stage strip + badge). "Needs attention": sources with `lastError`, runs FAILED in the last 7 days |
| Shows | `GET /admin/shows` | Show cards; "New show" form in a `<details>` slide-over (grouped fields) |
| Show | `GET /admin/shows/{id}` | Page header (chips: language, voice, model, target, schedule; actions Run now, Dry-run drafts). Live run card when a run is active (htmx polls `GET /admin/shows/{id}/live` every 3 s; it stops polling when no run is RUNNING). Tabs as links with a query param `?tab=` (no JS needed): overview, sources, episodes, settings, prompts |
| Episodes | `GET /admin/episodes` (new) | Episodes of all shows, newest first, optional `?show=<id>` filter, each with mini player |
| Episode | `GET /admin/episodes/{id}` | Player card (title, date, duration, size, run), chapters with per-chapter sources, "Made with" (promptVersions, writer model, voice, current wpm), script by part |
| Prompts | `GET /admin/prompts`, `GET /admin/prompts/{key}` | Two panes: prompt list (prod/draft versions), version timeline, CodeMirror merge diff vs production, editor with variable chips and server validation on submit, Promote / Set draft / Dry-run (htmx fragment) |
| Settings | `GET /admin/settings` (new, read-only) | Enabled model slots, installed Piper voices, connector types, Telegram enabled, base URL, app version |
| Login | `GET/POST /admin/login` | Restyled card |

Show tab content:
- **Overview:** latest 3 episodes and recent runs.
- **Sources:** table with type badge, last fetch, items stored today, status (`lastError` → warn), Test (htmx fragment). The add-source form has a connector selector: RSS shows a `url` field; Reddit shows subreddit, window and top-posts fields. Fields map to the source config map.
- **Episodes:** the show's episodes.
- **Settings:** the existing show form with grouped fields and a cron preview text computed server-side (cron-utils `CronDescriptor`, English).
- **Prompts:** the existing override selects.

Existing POST endpoints (create/update/delete show, add/delete/test source, run, retry, prompt version/label, show prompt overrides, dry-run) keep their routes; their responses redirect to or render the new pages.

## 5. Backend additions

### 5.1 Chapters

- `AudioAssembler.assemble` additionally receives `List<Integer> partOfChunk` (the script part index of each chunk). `AssembledAudio` gains `List<Double> partStarts` (seconds; the start of each part's first chunk, computed from the PCM byte offsets).
- `ScriptChunker` already knows the part of each chunk; `TtsChunk` gains `int part`.
- `TtsStage` builds `List<Chapter>`, with `record Chapter(String title, double startSeconds, List<Long> itemIds)`:
  - the first and last parts are "Intro" and "Outro" with no items
  - the middle parts take their headline and item ids from `outline.segments[i]`
- These are stored in `episodes.chapters` (JSONB).
- Flyway `V4__chapters_and_progress.sql`: `alter table episodes add column chapters jsonb; alter table runs add column progress varchar(300);`.

### 5.2 Per-story show notes

`ScriptWriter.showNotes` output:

```
<description>

Fonti:            (Sources: for non-Italian shows)
1. <segment headline>
- <article title> — <url>
- Discussione su Reddit — <discussion url>   ("Reddit discussion" for non-Italian)
2. <segment headline>
- …
```

Items without a `discussionUrl` get only the article line. This applies to newly generated episodes only.

### 5.3 Run progress

- `RunProgress` service: `update(long runId, String text)` (own `requiringNew` transaction, truncated to 300 chars) and `clear(long runId)`.
- Stages call it at the following points:

| Stage | Progress text |
|---|---|
| Ingest | after each source: `Fetched i/n sources, N new items` |
| Select | `Ranking N items` |
| Script | before each segment: `Writing segment i of n`; then `Writing intro and outro` |
| TTS | every chunk completion: `Synthesizing chunk i of n` (thread-safe counter); then `Encoding MP3` |
| Publish | `Publishing` |

- `RunOrchestrator` clears progress when the run finishes (DONE, SKIPPED or FAILED).
- `IngestionService.ingest` gains an optional progress callback `(int done, int total, int newItems) → void`, with an overload keeping the current signature.

## 6. Error handling

- A missing bundle (Web Bundler build error) fails the build in dev and CI.
- The pages keep a plain CSS fallback: content is readable even if `app.css` fails to load, because the HTML is semantic.
- The player island degrades to the native `<audio controls>` and chapter links when JS is unavailable (the server renders both; the custom element enhances).
- The editor island degrades to the plain `<textarea>`.
- The live run fragment returns the final state (badge + link to the episode) once the run is no longer RUNNING, and stops polling (`hx-trigger` only while running).

## 7. Testing

- **Admin page tests** (`AdminTest`, `PromptAdminTest`) updated to the new markup, plus new tests:
  - dashboard lists shows, latest episode and needs-attention items (a source with `lastError`, a failed run)
  - `/admin/episodes` lists episodes across shows, with the show filter
  - episode page renders chapters with timestamps and sources, including the Reddit thread
  - settings page lists models, voices and connectors
  - show live fragment: polling markup while RUNNING, final state when finished
  - add-source form for Reddit maps fields to config
- **Assets:** the layout links a `/static/bundle/app-*.css` that is served with 200, and there are no `cdn.`/`unpkg`/`jsdelivr`/`fonts.googleapis` references in rendered pages.
- **Chapters:**
  - `AudioAssemblerTest`: part starts match PCM offsets (e.g. two parts of 1.0 s + 0.5 s pause → starts 0.0 and 1.5)
  - `RunPipelineTest`: the published episode has chapters Intro/segment/Outro with the outline's item ids
- **Show notes:** `ScriptWriterTest` checks the per-story grouping, the Reddit line and the language label.
- **Progress:** `RunPipelineTest` records progress values during a run (a test hook collects updates) and finds progress cleared after DONE.
- **Manual check:** rebuild the container and visit each page.

## 8. Out of scope

- Dark mode (tokens are ready).
- Rendered audio waveform.
- Drag-and-drop.
- Multi-user and roles.
- Podcast-app chapter metadata (Podcasting 2.0 `<podcast:chapters>`). Possible later now that chapters exist.
