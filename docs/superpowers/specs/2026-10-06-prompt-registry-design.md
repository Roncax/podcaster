# Prompt Registry — Design Spec

Date: 2026-10-06
Status: Approved design, pending implementation plan
Branch: `feature/db-prompts`
Parent spec: `docs/superpowers/specs/2026-10-06-podcaster-design.md`

## 1. Purpose

Move every LLM prompt out of the Java code into the database, with immutable versions, deployment labels and per-show overrides, so prompts can be tuned, tested and rolled back from the admin UI or REST API without rebuilding the image.

### Success criteria

- No prompt text that is sent to an LLM remains hard-coded, except the fixed output contract (§4.3).
- After the migration, generated prompts are byte-for-byte identical to today's (seeded v1 = current text).
- Every episode records which prompt versions produced it.
- A prompt change can be dry-run on a show, promoted, and rolled back in one step, without a restart.
- An edit can never break response parsing, and an invalid template can never be stored.

## 2. Best practices applied

Based on Langfuse and MLflow prompt registries:

| Practice | Applied as |
|---|---|
| Versions are immutable; every save creates version N+1 | `prompt_versions` rows are insert-only; no update/delete path exists |
| Code references a label, not a version number; rollback = move the label | Labels `production` and `draft` per prompt |
| Lineage: record the prompt version behind each output | `episodes.prompt_versions` JSONB |
| Test before promoting | Script-only dry-run with the `draft` versions |

Out of scope: automated evaluation datasets, quality gates and traffic splitting (Langfuse-style prompt CI/CD) — dry-run plus manual promotion is enough for a single-user service.

## 3. Prompt inventory

| Key | Replaces | Variables (required in **bold**) |
|---|---|---|
| `rank` | `Prompts.rank` text, incl. the editorial-focus line | **`items`**, `showName`, `language`, `focus` |
| `segment` | `Prompts.segment` text, incl. style rules, the first-story / previous-segment transition sentences and the focus line | **`sources`**, **`words`**, `headline`, `language`, `focus`, `previousTail` |
| `framing` | `Prompts.framing` text | **`stories`**, `showName`, `language`, `date` |
| `json_repair` | `JsonChat.REPAIR` | **`error`** |

Variable meanings:
- `language` — English display name of the show language (e.g. "Italian").
- `focus` — the show's editorial focus, or null.
- `items` — ranker candidate lines, one per item (`[id=N] title — snippet`), joined by newlines.
- `sources` — the segment's source blocks (title, URL, text), as built today by `ScriptWriter`.
- `previousTail` — closing text of the previous segment, or null for the first segment.
- `date` — episode date formatted long in the show's locale (e.g. "6 ottobre 2026").
- `stories` — numbered headline list ("1. …\n2. …").
- `error` — the JSON parse error message.

Stays in code:
- The `TASK: <NAME>` first line of every prompt (used for logging and tests).
- The output contract blocks (§4.3).
- The show-notes label "Fonti"/"Sources": listener-facing text that is not sent to the LLM.
- `TtsTextNormalizer` substitutions: post-processing rules, not prompts.

## 4. Design

### 4.1 Data model (Flyway `V2__prompt_registry.sql`, schema `public`)

One schema; all tables share the `prompt` prefix.

```
prompts               key varchar(50) PK, description text
prompt_versions       id bigserial PK, prompt_key → prompts(key), version int,
                      body text not null, note text, created_at timestamptz,
                      unique (prompt_key, version)
prompt_labels         prompt_key → prompts(key), label varchar(20) check in ('production','draft'),
                      version_id → prompt_versions(id), primary key (prompt_key, label)
show_prompt_overrides show_id → shows(id) on delete cascade, prompt_key → prompts(key),
                      version_id → prompt_versions(id), primary key (show_id, prompt_key)
episodes              + prompt_versions jsonb   -- e.g. {"rank":3,"segment":2,"framing":1,"json_repair":1}
```

- `prompt_versions` is insert-only; the application exposes no update or delete.
- A label or override must point to a version of the same prompt. This is enforced in `PromptRegistry`; the API returns 400 otherwise.
- Seed rows: the four `prompts`, v1 of each with exactly today's text converted to Qute syntax (note "Initial version"), and both labels pointing to v1.

### 4.2 Components (package `org.roncax.podcaster.prompts`)

| Unit | Responsibility |
|---|---|
| `PromptKey` (enum) | `RANK`, `SEGMENT`, `FRAMING`, `JSON_REPAIR`: db key, `TASK:` header, allowed and required variables, contract block |
| `PromptRenderer` | Renders a body with Qute and a variable map. `validate(key, body) → List<String>` checks parse errors, unknown variables and missing required variables |
| `PromptRegistry` | `createVersion(key, body, note)` validates, inserts N+1 and points `draft` at it. Also `setLabel(key, label, version)`, `pin(showId, key, version)` / `unpin`, `versions(key)`, `version(key, n)`, `labels()` |
| `PromptResolver` | `resolve(showId, Mode) → PromptSet` where `Mode` is `PRODUCTION` or `DRAFT`. Per key: show override if present, else the label for the mode. In-memory cache, invalidated by every `PromptRegistry` write |
| `PromptSet` | Resolved versions per key; `render(key, vars) → String` (header + rendered body + contract); `versions() → Map<String,Integer>` for lineage |

### 4.3 Fixed output contract (code)

Appended after the rendered body:

- `rank`: "Return ONLY a JSON object, no prose, with this shape:\n{"clusters":[{"headline":"...","itemIds":[1,2],"importance":7}]}"
- `framing`: "Return ONLY a JSON object, no prose, with these fields: title, description, intro, outro.\n{"title":"...","description":"...","intro":"...","outro":"..."}"
- `segment`: none. The reply is plain text and is already cleaned by `LlmText` and `TtsTextNormalizer`.
- `json_repair`: none.

When the seeded v1 is wrapped with header and contract, the result equals today's prompt text exactly. Where today's JSON instructions sit mid-prompt, v1 is arranged so the concatenation reproduces the original; the golden test verifies this.

### 4.4 Generation flow changes

- `Prompts` keeps no text. `StoryRanker.rank`, `ScriptWriter.write` and `JsonChat.ask` take a `PromptSet`. `JsonChat` renders `json_repair` for its retry message.
- `SelectStage` and `ScriptStage` resolve a `PromptSet` (mode `PRODUCTION`) once per stage. `SelectStage` writes `episode.promptVersions` with the `rank` and `json_repair` versions; `ScriptStage` merges in `segment` and `framing`.
- A retry resolves prompts again. If a label moved in between, the lineage shows the versions each stage actually used.

### 4.5 Dry-run

`POST /api/prompts/dry-run {showId}`:
- Uses the `DRAFT` prompt set; show overrides still apply.
- Takes the show's unused items newer than now − `selection.first-run-window`; there is no ingest.
- Runs rank, outline and script with the same code as the stages, but persists nothing: no episode, no items marked used, no calibration change.
- Returns `{promptVersions, selection, outline, title, description, scriptParts}`.
- Synchronous; the admin UI shows a progress indicator.
- Fails with 409 if the show has no candidate items.

## 5. Interfaces

### 5.1 REST (`/api`, API key)

| Method | Path | Purpose |
|---|---|---|
| GET | `/prompts` | Prompts with production/draft version numbers |
| GET | `/prompts/{key}/versions` | History: version, note, createdAt, labels |
| GET | `/prompts/{key}/versions/{n}` | One version incl. body |
| POST | `/prompts/{key}/versions` | `{body, note}` → 201 with new version (labelled draft); 400 with validation errors |
| PUT | `/prompts/{key}/labels/{label}` | `{version}` → promote / roll back |
| PUT | `/shows/{id}/prompts/{key}` | `{version}` → pin show to a version |
| DELETE | `/shows/{id}/prompts/{key}` | Unpin |
| POST | `/prompts/dry-run` | `{showId}` → dry-run result |

### 5.2 Admin UI

- **Prompts page:** the four prompts with their production and draft versions, and links to each prompt's history.
- **Prompt page:**
  - version table (version, note, date, labels)
  - a version view with a line diff against production
  - a "New version" editor prefilled from the current draft, plus a note field
  - Promote-to-production and Set-as-draft buttons, which also cover rollback
  - "Dry-run on show…" (select a show) rendering the resulting script inline
- **Show settings:** an optional "Prompt overrides" table to pin or unpin a version per key.

## 6. Error handling

- **Invalid template on save:** 400 with a list of errors (parse error with line, unknown variable, missing required variable). Nothing is stored.
- **Label or override pointing to a missing version, or to another prompt's version:** 400.
- **Render failure at run time** (e.g. a null variable used without `{#if}`): the stage fails with `"prompt <key> v<n>: <message>"`. The run becomes FAILED, a Telegram alert is sent, and the run can be retried after fixing the prompt or moving the label.
- **Missing label at run time** (should not happen after seeding): the stage fails with "No production version for prompt <key>".

## 7. Testing

- **Golden:** for each key, seeded v1 rendered by `PromptSet` equals the output of today's `Prompts` methods for the same inputs. Captured before the old code is removed.
- **Unit:**
  - `PromptRenderer.validate`: parse error, unknown variable, missing required variable, valid body.
  - `PromptResolver`: override beats label; draft mode; cache invalidation after a write.
- **API (`@QuarkusTest`):**
  - create a version (lands as draft)
  - an invalid body returns 400
  - promote; roll back
  - pin and unpin a show
  - a label pointing to another prompt's version returns 400
  - history is immutable: there is no update endpoint, and versions are only appended
- **Pipeline:**
  - an episode records `promptVersions`
  - after promoting a v2 `rank`, the ranker request contains the v2 text
  - a pinned show uses its pinned version while others use production
- **Dry-run:** returns script parts, uses draft text, and creates no episode, no used items and no calibration change.
- **Existing tests:** all 119 stay green.

## 8. Out of scope

- Automated prompt evaluation, quality gates, A/B or percentage rollouts.
- Free-form labels beyond `production`/`draft`.
- Deleting versions.
- Per-language prompt variants. A show's language is a template variable; if needed later, overrides can pin a language-specific version.
