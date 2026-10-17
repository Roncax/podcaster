# Per-show Prompts & Test Tab — Design Spec

Date: 2026-10-17
Status: Approved design, pending implementation plan
Branch: `feature/per-show-prompts`
Parent specs: `2026-10-06-prompt-registry-design.md`, `2026-10-07-admin-ui-redesign-design.md`

## 1. Purpose

1. **Per-show prompts.** Every show owns its prompts, with its own immutable version history and `production`/`draft` labels. A new show forks its prompts from a set of global **default prompts** (today's prompts become the defaults). Later changes to a default do not affect existing shows, except through an explicit "Reset to default".
2. **Test tab.** Each show page gets a **Test** tab that dry-runs the pipeline in three chainable steps, persisting nothing: source retrieval → prompts (rank, outline, script) → voice generation of a short clip.

### Success criteria

- Every show has its own version 1 of each prompt after the migration. A show that had a pin is forked from the pinned body; any other show is forked from the current production body.
- Generated prompts for existing shows stay byte-for-byte the same right after the migration.
- Editing a default never changes an existing show's prompts.
- Every step in the Test tab leaves the database untouched: no items, episodes, runs or calibration changes, and no `Source.lastFetchedAt`/`lastError` updates. No Telegram alerts are sent.
- One flow can fetch from the sources, write a script with the show's draft prompts, and play part of it in the show's voice.

## 2. Data model (Flyway `V6__per_show_prompts.sql`)

```
prompt_versions  + show_id bigint null references shows(id) on delete cascade
                 drop unique (prompt_key, version)
                 add unique nulls not distinct (prompt_key, show_id, version)
prompt_labels    + show_id bigint not null references shows(id) on delete cascade
                 drop unique (prompt_key, label)
                 add unique (show_id, prompt_key, label)
drop table show_prompt_overrides
episodes         + prompt_versions_global boolean not null default false  -- true for pre-V6 rows
```

(`nulls not distinct` needs PostgreSQL 15+; we run 17.)

- **Default prompts:** `prompt_versions` rows with `show_id is null`. They are insert-only and have no labels. The source for forks and resets is the default's highest version ("latest default").
- **Show prompts:** `prompt_versions` rows with `show_id = X`. Versions are numbered per show starting at 1. The show's `production` and `draft` labels are `prompt_labels` rows with `show_id = X`.
- A label must point to a version of the same key and the same show. `PromptRegistry` enforces this; the API returns 400 otherwise.
- **Migration, in order:**
  1. Delete the existing global labels.
  2. For every show and every key, insert show v1. Its body is the pinned version's body if the show had an override, otherwise the current production body. Note: `"Forked from default v<n>"`. Both of the show's labels point to it.
  3. Drop `show_prompt_overrides`.

  The existing global version rows stay as the default history.
- **Lineage:** `episodes.prompt_versions` keeps its shape (`{"rank":3,…}`), and its numbers now refer to the show's versions. Episodes created before V6 keep global version numbers. V6 adds `episodes.prompt_versions_global boolean not null default false` and sets it to `true` on existing rows. The episode page shows "Prompt versions refer to the global registry (pre per-show prompts)" when it is true.
- **Show lifecycle:** `ShowService.create` forks all keys in the show's creation transaction. Deleting a show cascades to its versions and labels.

## 3. Prompt components

### 3.1 `PromptRegistry` (package `org.roncax.podcaster.prompts`)

Methods scoped to a show:
- `createVersion(showId, key, body, note)` validates the body (as today), inserts version N+1 for the show and points the show's `draft` at it.
- `setLabel(showId, key, label, version)`: promote or roll back.
- `resetToDefault(showId, key)` creates a new show draft with the latest default body. Note: `"Reset to default v<n>"`.
- `fork(showId)` creates v1 of every key from the latest defaults, with both labels on it.
- Reads: `versions(showId, key)`, `version(showId, key, n)`, `labels(showId, key)`, `labelsOf(showId, key, version)`.

Methods for defaults:
- `createDefaultVersion(key, body, note)` validates the body and appends a version.
- `defaultVersions(key)`, `defaultVersion(key, n)`, `latestDefault(key)`.

Removed: `pin`, `unpin`, `overrides`, and `ShowPromptOverride`.

Every write calls `PromptResolver.invalidate()`.

### 3.2 `PromptResolver`

`resolve(long showId, Mode mode)` reads the show's label for the mode (`PRODUCTION` or `DRAFT`). It no longer looks up overrides and no longer accepts a null show. Caching is unchanged. A missing label fails with `"No <label> version for prompt <key> in show <id>"`.

`PromptRenderer`, `PromptSet`, `PromptKey`, `LineDiff` and the fixed output contract are unchanged.

## 4. Test tab components (package `org.roncax.podcaster.testing`)

| Unit | Responsibility |
|---|---|
| `TestSession` | Per-show, in memory. Holds fetched items with per-source results, the last prompt dry-run result, the last voice clip (WAV bytes, duration, words/min), and a status (`IDLE`/`RUNNING`/`DONE`/`FAILED`) with an error for each step. A new source fetch clears the prompt and voice results. A new prompt dry-run clears nothing. |
| `TestSessionStore` | `ConcurrentHashMap<Long, TestSession>`. A scheduled sweep removes sessions idle for 30 minutes. Sessions are lost on restart. |
| `SourceDryRun` | For every enabled source of the show, it fetches items since now − `selection.first-run-window`, filters them and extracts full text, through the same code as ingestion (§4.1). It saves nothing and never calls `markSource`. It does not check URLs against the DB, so you see what the sources return. Items are de-duplicated within the batch by content hash and capped at `selection.max-candidates`. A failing source records its error and the other sources go on. |
| `PromptDryRun` | Moved from `prompts`. `run(showId, Mode, ItemsSource)`, where `ItemsSource` is `SESSION` (fetched items) or `DATABASE` (unused items of the show, as today). Runs rank → outline → script with the resolved `PromptSet`. Its result is as today (`promptVersions, selection, outline, title, description, scriptParts`). |
| `VoiceDryRun` | `run(showId, text, voiceId?, lengthScale?)`. Text is at most 1,000 chars (otherwise 400). It runs `TtsTextNormalizer.normalize` → `ScriptChunker.chunk` → `TtsEngine.synthesize` → `WavConcat.concat` (the PCM concatenation extracted from `AudioAssembler`, no ffmpeg), and stores the WAV in the session. Uses the show's voice and length scale unless overridden. The result includes the duration and measured words/min. |
| `TestRunner` | Starts steps on a managed executor and updates the session's step status. Allows one running step per show (`TestStepBusyException` → 409). Independent from `RunLauncher`, so test steps and real runs never block each other. |

### 4.1 Shared ingestion refactor

`IngestionService` exposes the fetch → filter (http-only links, URL length) → extract part as `collect(Source, Instant since, Predicate<String> seen) → List<CollectedItem>`, where `CollectedItem` holds the raw item, its full text and its content hash. `ingest` uses `collect` and keeps doing the saving and `markSource`. `SourceDryRun` calls `collect` with `seen = url -> false`.

### 4.2 Transient items

`SourceDryRun` turns collected items into unsaved `Item` objects with synthetic ids 1, 2, 3, … and `showId`/`sourceId` set. Positive ids are used because the ids appear in the rank prompt (`[id=N]`) and a model may drop a minus sign. They cannot collide with DB items because a dry-run uses either session items or DB items, never both. They are never persisted or attached to a persistence context, so `StoryRanker`, `OutlinePlanner` and `ScriptWriter` work on them unchanged.

## 5. Interfaces

### 5.1 REST (`/api`, API key)

Per-show prompts:

| Method | Path | Purpose |
|---|---|---|
| GET | `/shows/{id}/prompts` | Keys with the show's production/draft version numbers |
| GET | `/shows/{id}/prompts/{key}/versions` | Show history: version, note, createdAt, labels |
| GET | `/shows/{id}/prompts/{key}/versions/{n}` | One version incl. body |
| POST | `/shows/{id}/prompts/{key}/versions` | `{body, note}` → 201, new draft; 400 with validation errors |
| PUT | `/shows/{id}/prompts/{key}/labels/{label}` | `{version}` → promote / roll back |
| POST | `/shows/{id}/prompts/{key}/reset` | New draft from the latest default |

Default prompts:

| Method | Path | Purpose |
|---|---|---|
| GET | `/prompts` | Keys with their latest default version |
| GET | `/prompts/{key}/versions[/{n}]` | Default history / one version |
| POST | `/prompts/{key}/versions` | `{body, note}` → 201; 400 with validation errors |

Removed: `PUT /prompts/{key}/labels/{label}`, `PUT`/`DELETE /shows/{id}/prompts/{key}` (pins), and `POST /prompts/dry-run`.

Test (`/shows/{id}/test`):

| Method | Path | Purpose |
|---|---|---|
| GET | `/` | Session state: step statuses, errors, results (audio as a URL) |
| POST | `/sources` | Start the source step → 202 |
| POST | `/prompts` | `{mode: draft\|production, items: session\|database}` → 202 |
| POST | `/voice` | `{text}` or `{part: n}`, optional `voiceId`, `lengthScale` → 202 |
| GET | `/voice.wav` | The last clip (`audio/wav`); 404 if there is none |

### 5.2 Admin UI

- **Show page tabs:** Overview, Sources, Episodes, Settings, **Prompts** (replaces "Prompt overrides"), **Test**.
- **Show › Prompts:** a table of the keys with their production and draft versions, each linking to `/admin/shows/{id}/prompts/{key}`. The detail page is today's prompt detail page scoped to the show: version table, line diff against production, "New version" editor prefilled from the draft, Promote / Set as draft, plus **Reset to default** and **Test this draft** (opens the Test tab with mode Draft).
- **Default prompts** (`/admin/prompts`, nav label "Default prompts"): history, a diff against the previous version, and an editor. It has no labels and no dry-run. Banner: "Used for new shows. Existing shows change only via Reset to default."
- `PromptPages` templates are shared between the show and default scopes through a parameterized fragment, so they are not copied.
- **Show › Test:** three cards, polled with htmx like the live-run page while a step is running.
  1. **Sources:** "Fetch sources". Per source: item count or error, expandable to items (title, date, link, full text yes/no, text length).
  2. **Prompts:** Mode (Draft | Production), Items (Fetched | Unused in DB), "Run dry-run". Shows prompt versions, selected clusters, outline with word budgets, title, description and script parts. Each part has a **Voice this** button that fills step 3.
  3. **Voice:** a textarea with a 1,000-char counter, a voice select and length scale prefilled from the show, and "Synthesize". Shows an `<audio>` player, the duration and the words/min.

  A step whose input is missing is disabled and shows a hint (e.g. "Fetch sources first").

- The single-source Test button on the Sources tab stays.

## 6. Error handling

- **Source failure:** shown in that source's row. The other sources go on, and the step is `DONE` if at least one source was attempted.
- **No candidates:** a prompt step with no items returns 409, with "Fetch sources first" for `session` or "No unused items from the last Nh" for `database`.
- **Prompt render or LLM failure:** the step is `FAILED` with the same `"prompt <key> v<n>: <message>"` text as real runs. Earlier steps' results are kept.
- **Piper down or TTS error:** the voice step is `FAILED` with the root message.
- **Text over 1,000 chars, or an unknown part index:** 400.
- **Concurrent step on the same show:** 409.
- **Session expired or missing:** `GET` returns an empty session, and the UI shows "Session expired — fetch again" when you request session items.
- **Invalid template, or a label pointing to another show's or key's version:** 400, as today.

## 7. Testing

- **Migration:** with a pre-V6 fixture, a pinned show is forked from the pinned body and an unpinned show from production. Labels exist per show, the overrides table is gone, and the rendered prompts equal the pre-migration ones.
- **Registry and resolver:**
  - versions are numbered independently per show
  - a label pointing to another show's version returns 400
  - `resetToDefault` creates a draft with the latest default body and leaves production alone
  - a new show gets v1 of every key
  - a new default version does not change existing shows
  - show deletion cascades
- **API:** every per-show and default endpoint, including validation errors, and the removed endpoints return 404/405.
- **Test steps:**
  - `SourceDryRun` stores no `Item` rows, leaves `lastFetchedAt` and `lastError` unchanged, and reports a failing source while the others succeed
  - a prompt dry-run over session items with synthetic ids returns script parts, and draft and production mode use the right versions
  - `VoiceDryRun` rejects over 1,000 chars and uses the voice override (fake `TtsEngine`)
  - a concurrent step returns 409
  - sessions expire after the idle timeout
  - a test step persists no episode or run, changes no calibration and sends no notification
- **Ingestion refactor:** existing ingestion tests stay green; `collect` is covered via `SourceDryRun`.
- Existing dry-run tests move to `testing`. All other existing tests stay green.

## 8. Out of scope

- A global test page and tests with ad-hoc settings unrelated to a show.
- Full-episode audio in tests.
- Keeping test sessions across restarts or across instances.
- Pushing default changes to existing shows (only per-show, per-key Reset).
- Labels on default prompts.
- Moving pre-V6 episode lineage to show version numbers.
