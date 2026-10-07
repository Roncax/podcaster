# Admin UI Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Pico.css admin with the designed "on-air newsroom console" UI (Qute + htmx + Tailwind 4 via Web Bundler, offline), and add episode chapters, per-story show notes and in-stage run progress.

**Architecture:** Server-rendered Qute pages split into four resources (`AdminResource` for login/dashboard/settings, `ShowPages`, `EpisodePages`, `PromptPages`) sharing an `AdminSupport` bean and view records (`AdminViews`). Styling comes from a Tailwind 4 `@theme` plus a few `@layer components` classes in `web/app/app.css`, bundled by Quarkus Web Bundler. Status colours live in Qute tags so Tailwind can scan them. Two JS islands are separate bundles: `player` (chapter jumps on native `<audio>`) and `editor` (CodeMirror 6 + merge view). The backend gains `episodes.chapters`, `runs.progress` (Flyway V4), a `RunProgress` service and per-story show notes.

**Tech Stack:** Java 25, Quarkus 3.40.1, Qute, Quarkus REST, `io.quarkiverse.web-bundler:quarkus-web-bundler` + `quarkus-web-bundler-tailwindcss` 2.3.6, mvnpm `htmx.org` 2.0.11, `codemirror` 6.65.7, `@codemirror/merge` 6.12.2, `@fontsource/ibm-plex-sans` / `-mono` 5.3.0, cron-utils (transitive).

**Spec:** `docs/superpowers/specs/2026-10-07-admin-ui-redesign-design.md` (mockups: https://claude.ai/artifact/BG46ts34BVw2RWpCUVKa1c)

## Global Constraints

- Branch `feature/ui-redesign`. Maven with JDK 25: `export JAVA_HOME=$HOME/.jdks/temurin-25 PATH=$HOME/.jdks/temurin-25/bin:$HOME/.local/bin:$PATH`. After switching branches or large template changes, prefer `./mvnw -q clean test` (stale incremental output has caused bogus failures before).
- Existing admin routes and POST endpoints keep their URLs. New routes: `GET /admin` (dashboard), `GET /admin/episodes`, `GET /admin/settings`, `GET /admin/shows/{id}/live`. `GET /admin/shows/{id}/runs` is removed (replaced by `/live`).
- No CDN or Google Fonts references in rendered pages; all CSS/JS/fonts come from `/static/bundle/…`.
- Colour tokens (Tailwind `@theme`): ink `#16202B`, ground `#F4F5F7`, panel `#FFFFFF`, line `#E3E7EC`, muted `#5B6878`, accent `#E4572E`. Status pairs (text/bg): ok `#1E5E37/#E3F2E8`, warn `#8A4B00/#FFF4E5`, fail `#8F1D14/#FDE8E7`, info `#1F5F8B/#EEF4FB`, run `#1D4ED8/#E3ECFD`; stage dots ok `#2E7D4F`, run `#2563EB`, fail `#B42318`.
- Tailwind only sees classes written in templates and CSS. Never build class names in Java: Java passes tone or state keywords (`ok|warn|fail|info|run|neutral`, `done|run|fail|skip|pending`) and the tags map them to classes.
- Forms work without JS. Islands only enhance. 44 px touch targets. Real buttons, links and labels.
- Chapters JSON: `[{"title","startSeconds","itemIds"}]`. Progress strings are exactly as in spec §5.3.
- All `@QuarkusTest` classes carry `@WithTestResource(WireMockResource.class)` and `@WithTestResource(org.roncax.podcaster.support.PostgresResource.class)`.
- Commit trailers: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01LbcoMupq1VXnfzdqroFmyp`.

## Review Focus

1. **Bundle build fails on the font imports** (`@import "@fontsource/…"` not resolvable by the Tailwind/esbuild pipeline) → every page loses its CSS. The asset test must fail loudly. Fallback is in Task 1 Step 6. Test: Task 1 `AssetsTest.layoutLinksServedBundle`.
2. **Live run card keeps polling forever**, or never stops, after a run finishes or is deleted → the fragment must drop its `hx-trigger` when the run is not RUNNING. Test: Task 5 `ShowPagesTest.liveFragmentStopsPollingWhenFinished`.
3. **Episodes created before this change have no chapters**, and items without `discussionUrl` exist → the episode page must render without chapters (fallback list of sources from the outline) and must never NPE. Test: Task 6 `EpisodePagesTest.oldEpisodeWithoutChaptersRenders`.
4. **Reddit add-source form submitted with RSS fields**, or a blank subreddit → connector validation errors are shown on the page (HTML), not as a JSON 400. Test: Task 5 `ShowPagesTest.invalidSourceFormShowsErrors`.
5. **Prompt editor island fails to load** (JS error or old browser) → the textarea must still submit the body. The island copies the editor value back to the textarea before submit, and the form posts the textarea. Test: Task 7 `PromptPagesTest.formWorksWithoutIsland` (posts the plain textarea field).

**Plan-level deviation from the spec:** the player island enhances the **native** `<audio controls>` (chapter jumps, current-chapter highlight) instead of drawing its own seek bar. Native controls are already accessible and keyboard-operable, and the no-JS fallback is identical. Toasts are limited to htmx request errors. Spec §3.2's `card`, `button` and `field` are CSS component classes (`.card`, `.btn-*`, `.field*` in `app.css`) rather than Qute tags: same reuse with less markup; `layout`, `badge`, `stages`, `stepper`, `player` and `emptyState` stay tags because they carry logic.

---

## File Structure

```
pom.xml                                                     + web-bundler deps, mvnpm packages
src/main/resources/application.yml                          + web-bundler bundles (player, editor)
src/main/resources/web/app/app.css                          Tailwind import, @theme tokens, fonts, components
src/main/resources/web/app/app.js                           htmx + error toast
src/main/resources/web/player/player.js                     <podcast-player> island
src/main/resources/web/editor/editor.js                     CodeMirror editor + merge diff island
src/main/resources/db/migration/V4__chapters_and_progress.sql
src/main/java/org/roncax/podcaster/
  domain/Chapter.java                                       record (title, startSeconds, itemIds)
  domain/Episode.java, domain/Run.java                      + chapters, + progress
  runs/RunProgress.java                                     update/clear progress text
  runs/{IngestStage,SelectStage,ScriptStage,TtsStage,PublishStage,RunOrchestrator}.java   progress + chapters
  runs/ShowScheduler.java                                   + nextRun(showId)
  ingestion/IngestionService.java                           + progress callback overload
  generation/{ScriptChunker,TtsChunk,ScriptWriter}.java     chunk part index, per-story show notes
  tts/{AudioAssembler,AssembledAudio}.java                  part start offsets
  admin/AdminSupport.java                                   shared helpers (voices, bean errors, formatting, view builders)
  admin/AdminViews.java                                     view records
  admin/AdminResource.java                                  login, logout, dashboard, settings (rewritten)
  admin/ShowPages.java                                      /admin/shows/** (moved from AdminResource)
  admin/EpisodePages.java                                   /admin/episodes/**
  admin/PromptPages.java                                    /admin/prompts/** (moved from AdminResource)
  admin/TemplateTypes.java                                  + new view records
src/main/resources/templates/
  tags/{layout,badge,stages,stepper,player,emptyState}.html
  AdminResource/{login,dashboard,settings}.html
  ShowPages/{list,detail,live,sourceTest}.html
  EpisodePages/{list,detail}.html
  PromptPages/{list,detail,dryRun}.html
  (deleted: base.html, tags/{showForm,runTable}.html, AdminResource/{shows,show,runs,sourceTest,episode,prompts,prompt,dryRun}.html)
Tests: AssetsTest, RunProgressTest (in RunPipelineTest), AudioAssemblerTest, ScriptWriterTest,
       AdminTest (rewritten), ShowPagesTest, EpisodePagesTest, PromptAdminTest (adjusted)
```

---

### Task 1: Asset pipeline (Tailwind, htmx, fonts, islands) and layout tag

**Files:**
- Modify: `pom.xml`, `src/main/resources/application.yml`
- Create: `src/main/resources/web/app/app.css`, `web/app/app.js`, `web/player/player.js`, `web/editor/editor.js`
- Create: `src/main/resources/templates/tags/{layout,badge,stages,stepper,player,emptyState}.html`
- Test: `src/test/java/org/roncax/podcaster/admin/AssetsTest.java`

**Interfaces:**
- Produces:
  - Bundles `app` (default, `{#bundle /}`), `player` (`{#bundle key="player"/}`), `editor` (`{#bundle key="editor"/}`) served under `/static/bundle/`.
  - CSS component classes: `.btn`, `.btn-primary`, `.btn-accent`, `.btn-secondary`, `.btn-sm`, `.card`, `.chip`, `.field`, `.field-label`, `.field-input`, `.link`, `.muted`, `.mono`, `.tab`, `.tab-active`, `.diff-add`, `.diff-del`, `.diff-same`.
  - Tags (used by Tasks 4–7):
    - `{#layout title="…" active="dashboard|shows|episodes|prompts|settings" islands="player,editor"}…{/layout}`: full HTML page with sidebar; `islands` optional, comma-separated.
    - `{#badge tone=x}label{/badge}`: tone `ok|warn|fail|info|run|neutral`.
    - `{#stages stages=list label="…"/}`: list items have `name`, `state` (`done|run|fail|skip|pending`).
    - `{#stepper steps=list progress=text/}`: steps have `name`, `detail`, `state`.
    - `{#player src=url chapters=list/}`: chapters have `title`, `time`, `start`, `sources`.
    - `{#emptyState title="…"}body{/emptyState}`.

- [ ] **Step 1: Add dependencies**

In `pom.xml` `<properties>` add `<web-bundler.version>2.3.6</web-bundler.version>`. In `<dependencies>` add:

```xml
        <dependency><groupId>io.quarkiverse.web-bundler</groupId><artifactId>quarkus-web-bundler</artifactId><version>${web-bundler.version}</version></dependency>
        <dependency><groupId>io.quarkiverse.web-bundler</groupId><artifactId>quarkus-web-bundler-tailwindcss</artifactId><version>${web-bundler.version}</version></dependency>
        <dependency><groupId>org.mvnpm</groupId><artifactId>htmx.org</artifactId><version>2.0.11</version><scope>provided</scope></dependency>
        <dependency><groupId>org.mvnpm</groupId><artifactId>codemirror</artifactId><version>6.65.7</version><scope>provided</scope></dependency>
        <dependency><groupId>org.mvnpm.at.codemirror</groupId><artifactId>merge</artifactId><version>6.12.2</version><scope>provided</scope></dependency>
        <dependency><groupId>org.mvnpm.at.codemirror</groupId><artifactId>view</artifactId><version>6.43.13</version><scope>provided</scope></dependency>
        <dependency><groupId>org.mvnpm.at.codemirror</groupId><artifactId>state</artifactId><version>6.7.6</version><scope>provided</scope></dependency>
        <dependency><groupId>org.mvnpm.at.fontsource</groupId><artifactId>ibm-plex-sans</artifactId><version>5.3.0</version><scope>provided</scope></dependency>
        <dependency><groupId>org.mvnpm.at.fontsource</groupId><artifactId>ibm-plex-mono</artifactId><version>5.3.0</version><scope>provided</scope></dependency>
```

In `application.yml` under `quarkus:` add:

```yaml
  web-bundler:
    bundle:
      player: true
      editor: true
```

- [ ] **Step 2: Write the failing test**

```java
package org.roncax.podcaster.admin;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.support.WireMockResource;

@QuarkusTest
@WithTestResource(WireMockResource.class)
@WithTestResource(org.roncax.podcaster.support.PostgresResource.class)
class AssetsTest {

    @Test
    void layoutLinksServedBundle() {
        String html = given().get("/admin/login").then().statusCode(200).extract().asString();
        Matcher css = Pattern.compile("href=\"(/static/bundle/app[^\"]*\\.css)\"").matcher(html);
        assertTrue(css.find(), "layout must link the app CSS bundle: " + html.substring(0, Math.min(600, html.length())));
        String body = given().get(css.group(1)).then().statusCode(200).extract().asString();
        assertTrue(body.contains("--color-accent"), "theme tokens compiled into the bundle");
        assertTrue(body.contains("IBM Plex Sans"), "fonts bundled");
        Matcher js = Pattern.compile("src=\"(/static/bundle/app[^\"]*\\.js)\"").matcher(html);
        assertTrue(js.find(), "layout must load the app JS bundle");
        given().get(js.group(1)).then().statusCode(200);
    }

    @Test
    void noExternalAssetReferences() {
        String html = given().get("/admin/login").then().statusCode(200).extract().asString();
        for (String host : new String[] {"unpkg.com", "jsdelivr", "fonts.googleapis", "cdn."}) {
            assertFalse(html.contains(host), "external asset reference: " + host);
        }
    }
}
```

- [ ] **Step 3: Run to verify failure**

Run: `./mvnw -q test -Dtest=AssetsTest`
Expected: FAIL (the current login page uses the Pico CDN and has no bundle link).

- [ ] **Step 4: Write the stylesheet and app script**

`src/main/resources/web/app/app.css`:

```css
@import "@fontsource/ibm-plex-sans/400.css";
@import "@fontsource/ibm-plex-sans/500.css";
@import "@fontsource/ibm-plex-sans/600.css";
@import "@fontsource/ibm-plex-sans/700.css";
@import "@fontsource/ibm-plex-mono/400.css";
@import "@fontsource/ibm-plex-mono/500.css";
@import "tailwindcss";

@theme {
  --font-sans: "IBM Plex Sans", ui-sans-serif, system-ui, sans-serif;
  --font-mono: "IBM Plex Mono", ui-monospace, monospace;
  --color-ink: #16202B;
  --color-ink-2: #26323F;
  --color-ground: #F4F5F7;
  --color-panel: #FFFFFF;
  --color-line: #E3E7EC;
  --color-muted: #5B6878;
  --color-accent: #E4572E;
  --color-accent-soft: #FDF1EC;
  --color-ok: #1E5E37;
  --color-ok-bg: #E3F2E8;
  --color-ok-dot: #2E7D4F;
  --color-warn: #8A4B00;
  --color-warn-bg: #FFF4E5;
  --color-fail: #8F1D14;
  --color-fail-bg: #FDE8E7;
  --color-fail-dot: #B42318;
  --color-info: #1F5F8B;
  --color-info-bg: #EEF4FB;
  --color-run: #1D4ED8;
  --color-run-bg: #E3ECFD;
  --color-run-dot: #2563EB;
  --radius-card: 14px;
}

@layer components {
  .btn { @apply inline-flex items-center justify-center gap-2 h-11 px-4 rounded-[10px] text-sm font-semibold no-underline cursor-pointer whitespace-nowrap; }
  .btn-sm { @apply h-9 px-3 text-[13px] rounded-lg; }
  .btn-primary { @apply bg-ink text-white hover:bg-ink-2; }
  .btn-accent { @apply bg-accent text-white hover:brightness-95; }
  .btn-secondary { @apply bg-panel text-ink ring-1 ring-[#C9D1DB] hover:bg-ground; }
  .card { @apply bg-panel rounded-(--radius-card) ring-1 ring-line p-5 flex flex-col gap-4; }
  .chip { @apply inline-flex items-center rounded-full px-2.5 py-1 text-xs font-medium bg-panel ring-1 ring-line text-[#3D4957]; }
  .field { @apply flex flex-col gap-1.5; }
  .field-label { @apply text-[13px] font-medium; }
  .field-input { @apply h-11 rounded-[10px] ring-1 ring-[#C9D1DB] px-3 bg-panel text-[15px] focus:outline-2 focus:outline-accent; }
  textarea.field-input { @apply h-auto py-2.5 font-mono text-[13px] leading-relaxed; }
  .link { @apply text-info underline-offset-2 hover:underline; }
  .muted { @apply text-muted; }
  .mono { @apply font-mono; }
  .tab { @apply inline-flex items-center h-11 px-4 text-sm font-medium text-muted no-underline; }
  .tab-active { @apply text-ink shadow-[inset_0_-2px_0_var(--color-accent)]; }
  .diff-add { @apply bg-[#E6F4EA] text-[#14532D]; }
  .diff-del { @apply bg-[#FDECEA] text-[#7F1D1D] line-through; }
  .diff-same { @apply text-[#3D4957]; }
}
```

`src/main/resources/web/app/app.js`:

```js
import htmx from "htmx.org";

window.htmx = htmx;

// Show a short toast when an htmx request fails (forms without htmx keep normal page errors).
document.addEventListener("htmx:responseError", (event) => {
  const toast = document.createElement("div");
  toast.setAttribute("role", "alert");
  // inline styles: Tailwind does not scan JS files, so no utility classes here
  toast.style.cssText = "position:fixed;right:16px;bottom:16px;z-index:50;padding:12px 16px;border-radius:12px;"
    + "background:#FDE8E7;color:#8F1D14;font:500 14px 'IBM Plex Sans',sans-serif;box-shadow:0 6px 24px rgba(22,32,43,.18)";
  toast.textContent = "Request failed (" + event.detail.xhr.status + "). Please retry.";
  document.body.appendChild(toast);
  setTimeout(() => toast.remove(), 6000);
});
```

- [ ] **Step 5: Write the islands**

`src/main/resources/web/player/player.js`:

```js
// Enhances the server-rendered <podcast-player>: chapter links seek the native <audio>,
// and the current chapter gets aria-current="true". Without JS the links point at #t= and the audio still plays.
class PodcastPlayer extends HTMLElement {
  connectedCallback() {
    const audio = this.querySelector("audio");
    const links = Array.from(this.querySelectorAll("[data-start]"));
    if (!audio || links.length === 0) return;
    links.forEach((link) => link.addEventListener("click", (e) => {
      e.preventDefault();
      audio.currentTime = Number(link.dataset.start);
      audio.play();
    }));
    audio.addEventListener("timeupdate", () => {
      let current = links[0];
      for (const link of links) if (Number(link.dataset.start) <= audio.currentTime + 0.25) current = link;
      links.forEach((link) => link.toggleAttribute("aria-current", link === current));
    });
  }
}
customElements.define("podcast-player", PodcastPlayer);
```

`src/main/resources/web/editor/editor.js`:

```js
import { EditorView, basicSetup } from "codemirror";
import { MergeView } from "@codemirror/merge";
import { Decoration, MatchDecorator, ViewPlugin } from "@codemirror/view";
import { EditorState } from "@codemirror/state";

// Highlight {variable}, {#if x}, {#else}, {/if} in prompt templates.
const variableMatcher = new MatchDecorator({
  regexp: /\{[#/]?[A-Za-z][^{}]*\}/g,
  decoration: Decoration.mark({ class: "cm-prompt-var" }),
});
const variables = ViewPlugin.define((view) => ({
  decorations: variableMatcher.createDeco(view),
  update(u) { this.decorations = variableMatcher.updateDeco(u, this.decorations); },
}), { decorations: (v) => v.decorations });
const theme = EditorView.theme({
  "&": { fontSize: "13px", border: "1px solid #C9D1DB", borderRadius: "10px", backgroundColor: "#fff" },
  ".cm-content": { fontFamily: "'IBM Plex Mono', monospace" },
  ".cm-prompt-var": { color: "#1F5F8B", backgroundColor: "#EEF4FB", borderRadius: "3px" },
});

// Editor: replaces the textarea visually but keeps it as the submitted field.
document.querySelectorAll("textarea[data-prompt-editor]").forEach((textarea) => {
  const view = new EditorView({
    doc: textarea.value,
    extensions: [basicSetup, variables, theme, EditorView.lineWrapping,
      EditorView.updateListener.of((u) => { if (u.docChanged) textarea.value = u.state.doc.toString(); })],
  });
  textarea.after(view.dom);
  textarea.hidden = true;
  textarea.form?.addEventListener("submit", () => { textarea.value = view.state.doc.toString(); });
  document.querySelectorAll("[data-insert-var]").forEach((button) => button.addEventListener("click", () => {
    const text = button.dataset.insertVar;
    view.dispatch(view.state.replaceSelection(text));
    view.focus();
  }));
});

// Diff: replaces the server-rendered unified diff with a side-by-side merge view.
document.querySelectorAll("[data-prompt-diff]").forEach((box) => {
  const a = box.querySelector("template[data-side='a']")?.content.textContent ?? "";
  const b = box.querySelector("template[data-side='b']")?.content.textContent ?? "";
  const readOnly = [basicSetup, variables, theme, EditorView.editable.of(false), EditorState.readOnly.of(true), EditorView.lineWrapping];
  box.replaceChildren();
  new MergeView({ a: { doc: a, extensions: readOnly }, b: { doc: b, extensions: readOnly }, parent: box });
});
```

- [ ] **Step 6: Font fallback (only if the build cannot resolve the `@fontsource` imports)**

If Step 9 fails with an unresolved `@fontsource/...` import, replace the six `@import "@fontsource/…"` lines with local fonts:

```bash
mkdir -p src/main/resources/web/public/fonts
for f in sans mono; do
  J=$(ls ~/.m2/repository/org/mvnpm/at/fontsource/ibm-plex-$f/5.3.0/*.jar)
  unzip -o -j -q "$J" "*/files/ibm-plex-$f-latin-[4-7]00-normal.woff2" -d src/main/resources/web/public/fonts
done
ls src/main/resources/web/public/fonts
```

and put at the top of `app.css` (after nothing, before `@import "tailwindcss";`):

```css
@font-face { font-family: "IBM Plex Sans"; font-weight: 400; font-display: swap; src: url("/fonts/ibm-plex-sans-latin-400-normal.woff2") format("woff2"); }
@font-face { font-family: "IBM Plex Sans"; font-weight: 500; font-display: swap; src: url("/fonts/ibm-plex-sans-latin-500-normal.woff2") format("woff2"); }
@font-face { font-family: "IBM Plex Sans"; font-weight: 600; font-display: swap; src: url("/fonts/ibm-plex-sans-latin-600-normal.woff2") format("woff2"); }
@font-face { font-family: "IBM Plex Sans"; font-weight: 700; font-display: swap; src: url("/fonts/ibm-plex-sans-latin-700-normal.woff2") format("woff2"); }
@font-face { font-family: "IBM Plex Mono"; font-weight: 400; font-display: swap; src: url("/fonts/ibm-plex-mono-latin-400-normal.woff2") format("woff2"); }
@font-face { font-family: "IBM Plex Mono"; font-weight: 500; font-display: swap; src: url("/fonts/ibm-plex-mono-latin-500-normal.woff2") format("woff2"); }
```

(`web/public/**` is served unmodified at `/`.)

- [ ] **Step 7: Write the tags**

`templates/tags/layout.html`:

```html
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title} · Podcaster</title>
{#bundle /}
{#if player??}{#bundle key="player" /}{/if}
{#if editor??}{#bundle key="editor" /}{/if}
</head>
<body class="bg-ground text-ink font-sans antialiased">
<div class="min-h-screen flex flex-wrap">
  <nav aria-label="Main" class="grow basis-60 max-w-full sm:max-w-64 bg-ink text-slate-300 px-4 py-6 flex flex-col gap-7">
    <a href="/admin" class="flex items-center gap-2.5 px-2 text-white no-underline">
      <span class="size-3 rounded-full bg-accent ring-4 ring-accent/25" aria-hidden="true"></span>
      <span class="text-lg font-bold">Podcaster</span>
      <span class="ml-auto font-mono text-[11px] text-slate-400">ON AIR</span>
    </a>
    <ul class="flex flex-col gap-1" role="list">
      <li><a href="/admin" {#if active is 'dashboard'}aria-current="page"{/if} class="flex h-10 items-center rounded-lg px-3 text-sm font-medium no-underline text-slate-300 hover:text-white aria-[current=page]:bg-ink-2 aria-[current=page]:text-white">Dashboard</a></li>
      <li><a href="/admin/shows" {#if active is 'shows'}aria-current="page"{/if} class="flex h-10 items-center rounded-lg px-3 text-sm font-medium no-underline text-slate-300 hover:text-white aria-[current=page]:bg-ink-2 aria-[current=page]:text-white">Shows</a></li>
      <li><a href="/admin/episodes" {#if active is 'episodes'}aria-current="page"{/if} class="flex h-10 items-center rounded-lg px-3 text-sm font-medium no-underline text-slate-300 hover:text-white aria-[current=page]:bg-ink-2 aria-[current=page]:text-white">Episodes</a></li>
      <li><a href="/admin/prompts" {#if active is 'prompts'}aria-current="page"{/if} class="flex h-10 items-center rounded-lg px-3 text-sm font-medium no-underline text-slate-300 hover:text-white aria-[current=page]:bg-ink-2 aria-[current=page]:text-white">Prompts</a></li>
      <li><a href="/admin/settings" {#if active is 'settings'}aria-current="page"{/if} class="flex h-10 items-center rounded-lg px-3 text-sm font-medium no-underline text-slate-300 hover:text-white aria-[current=page]:bg-ink-2 aria-[current=page]:text-white">Settings</a></li>
    </ul>
    <div class="mt-auto flex flex-col gap-2 border-t border-ink-2 px-2 pt-4 text-xs text-slate-400">
      <a href="/q/swagger-ui" class="text-slate-300 no-underline hover:text-white">API docs</a>
      <form method="post" action="/admin/logout"><button type="submit" class="cursor-pointer text-slate-300 hover:text-white">Log out</button></form>
    </div>
  </nav>
  <main class="grow-[999] basis-[560px] min-w-0 px-5 py-7 md:px-10 flex flex-col gap-6">
{nested-content}
  </main>
</div>
</body>
</html>
```

`templates/tags/badge.html`:

```html
<span class="inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-semibold {#when tone}{#is 'ok'}bg-ok-bg text-ok{#is 'warn'}bg-warn-bg text-warn{#is 'fail'}bg-fail-bg text-fail{#is 'info'}bg-info-bg text-info{#is 'run'}bg-run-bg text-run{#else}bg-slate-100 text-slate-700{/when}">{nested-content}</span>
```

`templates/tags/stages.html`:

```html
<span class="flex gap-1" role="img" aria-label="{label ?: 'Run stages'}">{#for s in stages}<span title="{s.name}" class="h-1.5 w-5 rounded-full {#when s.state}{#is 'done'}bg-ok-dot{#is 'run'}bg-run-dot{#is 'fail'}bg-fail-dot{#is 'skip'}bg-slate-400{#else}bg-slate-200{/when}"></span>{/for}</span>
```

`templates/tags/stepper.html`:

```html
<ol class="grid grid-cols-5 gap-2" role="list">
{#for s in steps}
  <li class="flex flex-col gap-2">
    <span class="h-1.5 rounded-full {#when s.state}{#is 'done'}bg-ok-dot{#is 'run'}bg-run-dot{#is 'fail'}bg-fail-dot{#is 'skip'}bg-slate-400{#else}bg-slate-200{/when}" aria-hidden="true"></span>
    <span class="flex items-center gap-2">
      <span class="flex size-6 flex-none items-center justify-center rounded-full text-xs font-bold {#when s.state}{#is 'done'}bg-ok-bg text-ok{#is 'run'}bg-run-bg text-run{#is 'fail'}bg-fail-bg text-fail{#else}bg-slate-100 text-muted{/when}" aria-hidden="true">{#when s.state}{#is 'done'}✓{#is 'run'}…{#is 'fail'}!{#else}{/when}</span>
      <span class="flex flex-col"><span class="text-sm font-semibold">{s.name}</span><span class="text-xs text-muted">{s.detail}</span></span>
    </span>
  </li>
{/for}
</ol>
{#if progress}<p class="m-0 rounded-lg bg-ground px-3 py-2.5 font-mono text-xs text-[#3D4957]">{progress}</p>{/if}
```

`templates/tags/player.html`:

```html
<podcast-player class="flex flex-col gap-3">
  <audio controls preload="none" src="{src}" class="w-full"></audio>
  {#if chapters}
  <ol class="flex flex-col gap-1" role="list">
    {#for c in chapters}{#if c.time}
    <li class="flex items-baseline gap-3">
      <a href="{src}#t={c.start}" data-start="{c.start}" class="mono rounded-md px-2 py-1 text-xs text-info no-underline ring-1 ring-line aria-[current]:bg-accent aria-[current]:text-white">{c.time}</a>
      <span class="text-sm">{c.title}</span>
    </li>
    {/if}{/for}
  </ol>
  {/if}
</podcast-player>
```

`templates/tags/emptyState.html`:

```html
<div class="flex flex-col items-start gap-2 rounded-xl border border-dashed border-[#C9D1DB] p-4">
  <span class="text-sm font-medium">{title}</span>
  <div class="text-[13px] text-muted">{nested-content}</div>
</div>
```

- [ ] **Step 8: Move the login page onto the layout**

Replace `templates/AdminResource/login.html`:

```html
{#layout title="Log in" active=""}
<div class="mx-auto mt-16 w-full max-w-sm card">
  <h1 class="m-0 text-2xl font-bold">Podcaster</h1>
  {#if error}<p role="alert" class="m-0 rounded-lg bg-fail-bg px-3 py-2 text-sm text-fail">{error}</p>{/if}
  <form method="post" action="/admin/login" class="flex flex-col gap-4">
    <label class="field"><span class="field-label">API key</span><input class="field-input" type="password" name="key" required autofocus></label>
    <button type="submit" class="btn btn-primary">Log in</button>
  </form>
</div>
{/layout}
```

- [ ] **Step 9: Run tests**

Run: `./mvnw -q clean test -Dtest='AssetsTest,AdminTest'`
Expected: `AssetsTest` PASS. `AdminTest.loginSetsCookie` and `redirectsToLoginWithoutCookie` PASS; the other old admin pages render unstyled but still pass (their markup is unchanged until Tasks 4–7). If the bundle build fails on the font imports, apply Step 6 and re-run. Record a `Ruling:` in the ledger if Step 6 was needed.

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "Add Web Bundler asset pipeline: Tailwind theme, bundled fonts, htmx, player and editor islands, layout tags

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01LbcoMupq1VXnfzdqroFmyp"
```

---

### Task 2: Run progress and schema V4

**Files:**
- Create: `src/main/resources/db/migration/V4__chapters_and_progress.sql`, `src/main/java/org/roncax/podcaster/runs/RunProgress.java`, `src/main/java/org/roncax/podcaster/domain/Chapter.java`
- Modify: `domain/Run.java` (+`progress`), `domain/Episode.java` (+`chapters`), `ingestion/IngestionService.java` (progress callback), `runs/IngestStage.java`, `runs/SelectStage.java`, `runs/ScriptStage.java`, `runs/TtsStage.java`, `runs/PublishStage.java`, `runs/RunOrchestrator.java`, `generation/ScriptWriter.java` (progress callback)
- Test: `src/test/java/org/roncax/podcaster/runs/RunPipelineTest.java` (add test)

**Interfaces:**
- Produces:
  - `RunProgress#update(long runId, String text)`, `RunProgress#clear(long runId)`.
  - `Run.progress` (`String`, max 300).
  - `record Chapter(String title, double startSeconds, List<Long> itemIds)` and `Episode.chapters` (`List<Chapter>`, JSONB). Filled in Task 3.
  - `IngestionService#ingest(long showId, Instant since, IngestionService.Progress progress)` where `interface Progress { void update(int done, int total, int newItems); }`; the old 2-arg `ingest` delegates with a no-op.
  - `ScriptWriter#write(ChatModel, PromptSet, Show, Outline, Map<Long,Item>, LocalDate, java.util.function.IntConsumer onSegment)`, called with the 0-based segment index before each segment; the old 6-arg `write` delegates with a no-op.

- [ ] **Step 1: Write the failing test** (add to `RunPipelineTest`)

```java
    @Test
    void runReportsProgressAndClearsItWhenDone() throws Exception {
        stubFeed("/p1", "/p2");
        wm.stubFor(post("/synthesize").willReturn(aResponse().withStatus(200).withFixedDelay(400)
                .withBody(TestAudio.sineWav(0.2))));

        long runId = launcher.launch(show.id, RunTrigger.MANUAL);
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        Instant deadline = Instant.now().plusSeconds(60);
        while (Instant.now().isBefore(deadline)) {
            Run r = QuarkusTransaction.requiringNew().call(() -> Run.<Run>findById(runId));
            if (r.progress != null) seen.add(r.progress);
            if (r.status != RunStatus.RUNNING) break;
            Thread.sleep(50);
        }
        Run done = TestData.awaitRun(runId);

        assertEquals(RunStatus.DONE, done.status, done.error);
        assertNull(done.progress, "progress is cleared when the run finishes");
        assertTrue(seen.stream().anyMatch(p -> p.startsWith("Synthesizing chunk ")), seen.toString());
        assertTrue(seen.stream().anyMatch(p -> p.matches("Fetched \\d+/\\d+ sources, \\d+ new items")) || seen.stream().anyMatch(p -> p.startsWith("Writing segment")), seen.toString());
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -q test -Dtest=RunPipelineTest#runReportsProgressAndClearsItWhenDone`
Expected: compilation FAIL (`Run.progress` missing).

- [ ] **Step 3: Migration, entities, `RunProgress`**

`V4__chapters_and_progress.sql`:

```sql
alter table episodes add column chapters jsonb;
alter table runs add column progress varchar(300);
```

`domain/Chapter.java`:

```java
package org.roncax.podcaster.domain;

import java.util.List;

/** A chapter of an episode: intro, one per story segment, outro. */
public record Chapter(String title, double startSeconds, List<Long> itemIds) {}
```

Add to `Episode.java` (next to the other JSON columns): `@JdbcTypeCode(SqlTypes.JSON) public List<Chapter> chapters;`
Add to `Run.java`: `public String progress;`

`runs/RunProgress.java`:

```java
package org.roncax.podcaster.runs;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import org.roncax.podcaster.domain.Run;

/** Short human-readable progress of the running stage, shown live in the admin UI. */
@ApplicationScoped
public class RunProgress {
    static final int MAX = 300;

    public void update(long runId, String text) {
        String value = text == null ? null : text.length() > MAX ? text.substring(0, MAX) : text;
        QuarkusTransaction.requiringNew().run(() -> Run.update("progress = ?1 where id = ?2", value, runId));
    }

    public void clear(long runId) {
        update(runId, null);
    }
}
```

- [ ] **Step 4: Progress callbacks in ingestion and script writing**

`IngestionService`: add the interface and overload, and report after each source:

```java
    public interface Progress { void update(int done, int total, int newItems); }

    public IngestionReport ingest(long showId, Instant since) {
        return ingest(showId, since, (d, t, n) -> {});
    }
```

Rename the existing `ingest(long showId, Instant since)` body to `public IngestionReport ingest(long showId, Instant since, Progress progress)`. At the end of each loop iteration over `sources` (after the try/catch), add `progress.update(++done, sources.size(), added);` with `int done = 0;` declared before the loop.

`ScriptWriter`: add the overload and call the callback:

```java
    public Script write(ChatModel model, PromptSet prompts, Show show, Outline outline, Map<Long, Item> items, LocalDate date) {
        return write(model, prompts, show, outline, items, date, i -> {});
    }
```

Rename the existing method to the 7-arg version with `java.util.function.IntConsumer onSegment` as the last parameter, and change the segment loop header to an indexed loop that calls `onSegment.accept(i)` first:

```java
        for (int i = 0; i < outline.segments().size(); i++) {
            onSegment.accept(i);
            OutlineSegment seg = outline.segments().get(i);
            // … unchanged body …
        }
```

- [ ] **Step 5: Stages and orchestrator report progress**

- `IngestStage`: inject `@Inject RunProgress progress;` and call `ingestion.ingest(run.showId, run.since, (done, total, n) -> progress.update(run.id, "Fetched " + done + "/" + total + " sources, " + n + " new items"))`.
- `SelectStage`: inject `RunProgress progress`; after the `minItems` check, `progress.update(run.id, "Ranking " + candidates.size() + " items");`.
- `ScriptStage`: inject `RunProgress progress`. In `ScriptWriter.write` (7-arg), call `onSegment.accept(outline.segments().size())` immediately before the framing `JsonChat.ask(...)` call, so index `== segments` means "intro and outro". Call the writer with:

```java
        int segments = outline.segments().size();
        Script script = writer.write(models.get(show.writerModel), promptSet, show, outline, items, LocalDate.now(),
                i -> progress.update(run.id, i < segments ? "Writing segment " + (i + 1) + " of " + segments : "Writing intro and outro"));
```

- `TtsStage`: inject `RunProgress progress`; before submitting chunks create `java.util.concurrent.atomic.AtomicInteger synthesized = new AtomicInteger();` and in the submitted lambda after `synthesize(...)` returns call `progress.update(run.id, "Synthesizing chunk " + synthesized.incrementAndGet() + " of " + chunks.size());`. Before `assembler.assemble(...)` call `progress.update(run.id, "Encoding MP3");`.
- `PublishStage`: inject `RunProgress progress`; first line of `execute`: `progress.update(run.id, "Publishing");`.
- `RunOrchestrator.finish(...)`: inside its transaction lambda also set `r.progress = null;`.

The TTS lambda becomes:

```java
            for (TtsChunk chunk : chunks) futures.add(pool.submit(() -> {
                Path file = synthesize(dir, chunk, voice);
                progress.update(run.id, "Synthesizing chunk " + synthesized.incrementAndGet() + " of " + chunks.size());
                return file;
            }));
```

- [ ] **Step 6: Run tests**

Run: `./mvnw -q test -Dtest='RunPipelineTest,SelectStageTest,IngestionServiceTest,ScriptWriterTest,RunRecoveryTest'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "Record in-stage run progress; add schema for chapters and progress

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01LbcoMupq1VXnfzdqroFmyp"
```

---

### Task 3: Chapters and per-story show notes

**Files:**
- Modify: `generation/TtsChunk.java` (+`part`), `generation/ScriptChunker.java`, `tts/AudioAssembler.java`, `tts/AssembledAudio.java` (+`partStarts`), `runs/TtsStage.java` (store chapters), `generation/ScriptWriter.java` (`showNotes`)
- Test: `src/test/java/org/roncax/podcaster/tts/AudioAssemblerTest.java`, `generation/ScriptChunkerTest.java`, `generation/ScriptWriterTest.java`, `runs/RunPipelineTest.java` (add assertions)

**Interfaces:**
- Consumes: `Chapter`, `Episode.chapters` (Task 2); `Item.discussionUrl` (Reddit work).
- Produces:
  - `record TtsChunk(int index, String text, Duration pauseAfter, int part)`.
  - `AudioAssembler#assemble(List<Path> wavChunks, List<Duration> pausesAfter, List<Integer> partOfChunk, Path outMp3, Mp3Tags tags) → AssembledAudio`.
  - `record AssembledAudio(Path file, double durationSeconds, long sizeBytes, List<Double> partStarts)`: one start per distinct part, in order.
  - `ScriptWriter.showNotes(String description, String language, Outline outline, Map<Long,Item> items) → String` in the per-story format of spec §5.2.

- [ ] **Step 1: Write the failing tests**

In `ScriptChunkerTest` add:

```java
    @Test
    void chunksKnowTheirPart() {
        List<TtsChunk> chunks = ScriptChunker.chunk(List.of("Intro.", "One. Two.\n\nThree.", "Outro."), 500, CHUNK, SEGMENT);
        assertEquals(List.of(0, 1, 1, 2), chunks.stream().map(TtsChunk::part).toList());
    }
```

In `AudioAssemblerTest` change both `assembler.assemble(...)` calls to pass parts, and add an assertion on starts. The first test becomes:

```java
    @Test
    void concatenatesWithPausesAndEncodesMp3(@TempDir Path dir) throws Exception {
        Path a = Files.write(dir.resolve("a.wav"), TestAudio.sineWav(1.0));
        Path b = Files.write(dir.resolve("b.wav"), TestAudio.sineWav(1.0));
        Path out = dir.resolve("episode.mp3");

        AssembledAudio audio = assembler.assemble(List.of(a, b),
                List.of(Duration.ofMillis(500), Duration.ofMillis(500)), List.of(0, 1), out,
                new Mp3Tags("Title", "Podcaster", "Daily", "2026-10-06"));

        assertEquals(3.0, audio.durationSeconds(), 0.01);
        assertEquals(2, audio.partStarts().size());
        assertEquals(0.0, audio.partStarts().get(0), 0.001);
        assertEquals(1.5, audio.partStarts().get(1), 0.001);
        assertTrue(Files.exists(out));
        assertEquals(Files.size(out), audio.sizeBytes());
        byte[] head = Files.readAllBytes(out);
        assertEquals("ID3", new String(head, 0, 3));
        assertFalse(Files.exists(dir.resolve("episode.mp3.wav")), "temporary WAV is removed");
    }
```

and in `rejectsMixedFormats` pass `List.of(0, 0)` as the third argument.

In `ScriptWriterTest` add (it reuses the class's `items`, `outline`, `show()`):

```java
    @Test
    void showNotesGroupLinksByStoryWithRedditThread() {
        items.get(3L).discussionUrl = "https://www.reddit.com/r/italy/comments/abc/x/";
        String notes = ScriptWriter.showNotes("Notes.", "it", outline, items);
        assertEquals("""
                Notes.

                Fonti:
                1. Story A
                - Title 1 — https://news.example/1
                - Title 2 — https://news.example/2
                2. Story B
                - Title 3 — https://news.example/3
                - Discussione su Reddit — https://www.reddit.com/r/italy/comments/abc/x/""", notes);
    }

    @Test
    void showNotesUseEnglishLabelsForOtherLanguages() {
        String notes = ScriptWriter.showNotes("Notes.", "en", outline, items);
        assertTrue(notes.contains("\n\nSources:\n1. Story A\n"), notes);
    }
```

Change the `items` field in `ScriptWriterTest` from `Map.of(...)` to a mutable `new HashMap<>(Map.of(...))` (add `import java.util.HashMap;`) so the test can set `discussionUrl`.

In `RunPipelineTest.producesAndPublishesAnEpisode`, after the `scriptParts` assertion add:

```java
        assertEquals(List.of("Intro", "Top story", "Outro"), episode.chapters.stream().map(Chapter::title).toList());
        assertEquals(0.0, episode.chapters.get(0).startSeconds(), 0.001);
        assertTrue(episode.chapters.get(1).startSeconds() > 0);
        assertEquals(3, episode.chapters.get(1).itemIds().size());
```

(`Chapter` is in `org.roncax.podcaster.domain`, already imported by `domain.*`.)

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -q test -Dtest='ScriptChunkerTest,AudioAssemblerTest,ScriptWriterTest'`
Expected: compilation FAIL (`part()`, the 5-arg `assemble`, `partStarts()` missing).

- [ ] **Step 3: Chunk parts**

`TtsChunk`:

```java
package org.roncax.podcaster.generation;

import java.time.Duration;

public record TtsChunk(int index, String text, Duration pauseAfter, int part) {}
```

`ScriptChunker.chunk`: iterate parts with an index and pass it:

```java
        for (int partIndex = 0; partIndex < parts.size(); partIndex++) {
            String part = parts.get(partIndex);
            // … unchanged paragraph/sentence packing into `texts` …
            for (int i = 0; i < texts.size(); i++) {
                Duration pause = i == texts.size() - 1 ? segmentPause : chunkPause;
                chunks.add(new TtsChunk(chunks.size(), texts.get(i), pause, partIndex));
            }
        }
```

- [ ] **Step 4: Part start offsets in the assembler**

`AssembledAudio`:

```java
package org.roncax.podcaster.tts;

import java.nio.file.Path;
import java.util.List;

/** {@code partStarts}: start time in seconds of each script part (intro, segments, outro), in order. */
public record AssembledAudio(Path file, double durationSeconds, long sizeBytes, List<Double> partStarts) {}
```

`AudioAssembler.assemble`: new signature `(List<Path> wavChunks, List<Duration> pausesAfter, List<Integer> partOfChunk, Path outMp3, Mp3Tags tags)`. Inside the chunk loop, before writing each chunk's PCM:

```java
            if (i == 0 || !partOfChunk.get(i).equals(partOfChunk.get(i - 1))) {
                partStarts.add(first == null ? 0.0 : (double) pcm.size() / first.bytesPerSecond());
            }
```

Declare `List<Double> partStarts = new ArrayList<>();` before the loop. `first` is assigned from the first chunk's `Wav` at the top of iteration 0, so for `i == 0` the start is `0.0`. Place the snippet **after** `first` is set, then use `pcm.size() / first.bytesPerSecond()` for all chunks. Return `new AssembledAudio(outMp3, duration, Files.size(outMp3), List.copyOf(partStarts))`.

- [ ] **Step 5: Store chapters in `TtsStage`**

Pass `chunks.stream().map(TtsChunk::part).toList()` to `assemble(...)` and, in the transaction that stores duration and size, add the chapters:

```java
        List<Chapter> chapters = chapters(episode, audio.partStarts());
        QuarkusTransaction.requiringNew().run(() -> {
            Episode e = Episode.findById(episode.id);
            e.durationSeconds = audio.durationSeconds();
            e.sizeBytes = audio.sizeBytes();
            e.chapters = chapters;
        });
```

and the helper (in `TtsStage`):

```java
    /** Intro, one chapter per outline segment, outro; starts come from the assembled audio. */
    static List<Chapter> chapters(Episode episode, List<Double> partStarts) {
        List<Chapter> chapters = new ArrayList<>();
        int parts = partStarts.size();
        for (int p = 0; p < parts; p++) {
            String title;
            List<Long> itemIds = List.of();
            if (p == 0) {
                title = "Intro";
            } else if (p == parts - 1) {
                title = "Outro";
            } else {
                int seg = p - 1;
                boolean known = episode.outline != null && seg < episode.outline.segments().size();
                title = known ? episode.outline.segments().get(seg).headline() : "Story " + p;
                itemIds = known ? episode.outline.segments().get(seg).itemIds() : List.of();
            }
            chapters.add(new Chapter(title, partStarts.get(p), itemIds));
        }
        return chapters;
    }
```

(imports: `org.roncax.podcaster.domain.Chapter`, `java.util.ArrayList`.)

- [ ] **Step 6: Per-story show notes**

Replace `ScriptWriter.showNotes`:

```java
    static String showNotes(String description, String language, Outline outline, Map<Long, Item> items) {
        boolean italian = language != null && language.startsWith("it");
        StringBuilder sb = new StringBuilder(description == null ? "" : description.trim());
        sb.append("\n\n").append(italian ? "Fonti" : "Sources").append(':');
        int n = 0;
        for (OutlineSegment seg : outline.segments()) {
            sb.append('\n').append(++n).append(". ").append(seg.headline());
            for (Long id : seg.itemIds()) {
                Item item = items.get(id);
                if (item == null) continue;
                sb.append("\n- ").append(item.title).append(" — ").append(item.url);
                if (item.discussionUrl != null && !item.discussionUrl.isBlank()) {
                    sb.append("\n- ").append(italian ? "Discussione su Reddit" : "Reddit discussion").append(" — ").append(item.discussionUrl);
                }
            }
        }
        return sb.toString().trim();
    }
```

(`ScriptWriterTest.writesSegmentsThenFraming` still passes: it checks `contains("Fonti:")` and the item URL.)

- [ ] **Step 7: Run tests**

Run: `./mvnw -q test -Dtest='ScriptChunkerTest,AudioAssemblerTest,ScriptWriterTest,RunPipelineTest,PromptPipelineTest'`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "Record episode chapters at TTS assembly and group show-note links per story

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01LbcoMupq1VXnfzdqroFmyp"
```

---
### Task 4: Admin support, dashboard and settings

**Files:**
- Create: `src/main/java/org/roncax/podcaster/admin/AdminViews.java`, `admin/AdminSupport.java`
- Modify: `admin/AdminResource.java` (login, logout, dashboard, settings; show/prompt/episode endpoints move out in Tasks 5–7), `admin/TemplateTypes.java`, `runs/ShowScheduler.java` (+`nextRun`)
- Create: `templates/AdminResource/dashboard.html`, `templates/AdminResource/settings.html`
- Test: `src/test/java/org/roncax/podcaster/admin/AdminTest.java` (rewritten: login, dashboard, settings; show-page tests move to `ShowPagesTest` in Task 5)

**Interfaces:**
- Consumes: tags from Task 1; `Run.progress`, `Episode.chapters` (Task 2); `ShowService`, `ChatModelRegistry`, `ConnectorRegistry`, `TtsEngine`, `VoiceCalibrationService`, `PodcasterConfig`.
- Produces:
  - `AdminViews` records:
    - `StageDot(String name, String state)`, `Step(String name, String detail, String state)`
    - `EpisodeLink(long id, String title, long showId, String showName, String date, String duration, String audioUrl)`
    - `ShowCard(long id, String name, String slug, String meta, String lastRunLabel, String lastRunTone, EpisodeLink latest, String nextRun, long freshItems, long episodes)`
    - `RunRow(long id, long showId, String showName, List<StageDot> stages, String status, String tone, String when, String error)`
    - `Attention(String title, String detail, String tone, String href)`
    - `SourceLink(String site, String title, String url)`, `ChapterView(String title, String time, double start, List<SourceLink> sources)`
    - `SourceRow(long id, String name, String detail, String type, String typeTone, String fetched, long newToday, String status, String tone)`
    - `LiveRun(long id, boolean running, String status, String tone, String trigger, String started, List<Step> steps, String progress, String error, Long episodeId)`
  - `AdminSupport` (`@ApplicationScoped`):
    - data helpers: `voices() → Set<String>`, `beanErrors(ShowRequest) → List<String>`, `showCard(Show) → ShowCard`, `runRow(Run, String showName) → RunRow`, `attention() → List<Attention>`, `liveRun(long showId) → LiveRun` (null when the show never ran), `episodeLink(Episode, String showName) → EpisodeLink`, `chapters(Episode) → List<ChapterView>`
    - static formatters: `when(Instant)`, `day(Instant)`, `duration(Double)`, `tone(RunStatus)`, `label(RunStatus)`, `stages(Run)`, `steps(Run)`, `site(String url)`, `cronText(String cron)`
  - `ShowScheduler#nextRun(long showId) → Optional<Instant>`.
  - Pages: `GET /admin` (dashboard), `GET /admin/settings`; login redirects to `/admin`.

- [ ] **Step 1: Write the failing test** (replace `AdminTest`)

```java
package org.roncax.podcaster.admin;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.domain.*;
import org.roncax.podcaster.support.*;

@QuarkusTest
@WithTestResource(WireMockResource.class)
@WithTestResource(org.roncax.podcaster.support.PostgresResource.class)
class AdminTest {
    @InjectWireMock WireMockServer wm;

    @BeforeEach
    void setup() {
        TestData.cleanDb();
        WireMockResource.installDefaults(wm);
        FakeChatModelRegistry.install(new FakeChatModel().responder(FakeResponses::pipeline));
    }

    private RequestSpecification admin() {
        return given().cookie("podcaster_key", "test-api-key-0123456789").redirects().follow(false);
    }

    @Test
    void redirectsToLoginWithoutCookie() {
        given().redirects().follow(false).get("/admin").then().statusCode(303).header("Location", endsWith("/admin/login"));
    }

    @Test
    void loginSetsCookieAndOpensDashboard() {
        given().redirects().follow(false).formParam("key", "test-api-key-0123456789").post("/admin/login")
                .then().statusCode(303).cookie("podcaster_key", "test-api-key-0123456789").header("Location", endsWith("/admin"));
        given().formParam("key", "nope").post("/admin/login").then().statusCode(200).body(containsString("Wrong key"));
    }

    @Test
    void dashboardShowsShowsLatestEpisodeRunsAndAttention() {
        Show show = TestData.show("dash");
        Source source = TestData.source(show.id, "http://feed/x");
        QuarkusTransaction.requiringNew().run(() -> {
            Source s = Source.findById(source.id);
            s.lastError = "HTTP 404 from http://feed/x";
            Run ok = new Run();
            ok.showId = show.id; ok.trigger = RunTrigger.MANUAL; ok.status = RunStatus.DONE; ok.stage = RunStage.PUBLISH; ok.since = Instant.now();
            ok.persist();
            Episode e = new Episode();
            e.runId = ok.id; e.showId = show.id; e.title = "Episodio di prova"; e.audioPath = "dash/1.mp3";
            e.durationSeconds = 245.0; e.publishedAt = Instant.now();
            e.persist();
            Run failed = new Run();
            failed.showId = show.id; failed.trigger = RunTrigger.SCHEDULED; failed.status = RunStatus.FAILED; failed.stage = RunStage.TTS;
            failed.error = "TTS: Piper returned HTTP 503"; failed.since = Instant.now();
            failed.persist();
        });

        admin().get("/admin").then().statusCode(200)
                .body(containsString("Show dash"))
                .body(containsString("Episodio di prova"))
                .body(containsString("/media/dash/1.mp3"))
                .body(containsString("4:05"))
                .body(containsString("Source failing"))
                .body(containsString("HTTP 404 from http://feed/x"))
                .body(containsString("TTS: Piper returned HTTP 503"))
                .body(containsString("aria-current=\"page\""));
    }

    @Test
    void settingsListsModelsVoicesAndConnectors() {
        admin().get("/admin/settings").then().statusCode(200)
                .body(containsString("fake"))
                .body(containsString("it_IT-paola-medium"))
                .body(containsString("reddit"))
                .body(containsString("rss"));
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -q test -Dtest=AdminTest`
Expected: FAIL (`GET /admin` redirects to `/admin/shows`; `/admin/settings` is 404).

- [ ] **Step 3: View records**

```java
package org.roncax.podcaster.admin;

import java.util.List;

/** View models for the admin pages. Tones and states are keywords; the Qute tags map them to classes. */
public final class AdminViews {
    private AdminViews() {}

    public record StageDot(String name, String state) {}
    public record Step(String name, String detail, String state) {}
    public record EpisodeLink(long id, String title, long showId, String showName, String date, String duration, String audioUrl) {}
    public record ShowCard(long id, String name, String slug, String meta, String lastRunLabel, String lastRunTone,
                           EpisodeLink latest, String nextRun, long freshItems, long episodes) {}
    public record RunRow(long id, long showId, String showName, List<StageDot> stages, String status, String tone, String when, String error) {}
    public record Attention(String title, String detail, String tone, String href) {}
    public record SourceLink(String site, String title, String url) {}
    public record ChapterView(String title, String time, double start, List<SourceLink> sources) {}
    public record SourceRow(long id, String name, String detail, String type, String typeTone, String fetched, long newToday, String status, String tone) {}
    public record LiveRun(long id, boolean running, String status, String tone, String trigger, String started,
                          List<Step> steps, String progress, String error, Long episodeId) {}
}
```

- [ ] **Step 4: `ShowScheduler.nextRun`**

```java
    public java.util.Optional<java.time.Instant> nextRun(long showId) {
        return scheduler.getScheduledJobs().stream()
                .filter(t -> t.getId().equals(PREFIX + showId))
                .map(Trigger::getNextFireTime)
                .filter(java.util.Objects::nonNull)
                .findFirst();
    }
```

- [ ] **Step 5: `AdminSupport`**

```java
package org.roncax.podcaster.admin;

import com.cronutils.descriptor.CronDescriptor;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.roncax.podcaster.admin.AdminViews.*;
import org.roncax.podcaster.api.ShowRequest;
import org.roncax.podcaster.domain.*;
import org.roncax.podcaster.runs.ShowScheduler;
import org.roncax.podcaster.tts.TtsEngine;

/** Shared helpers for the admin pages: lookups, formatting and view-model building. */
@ApplicationScoped
public class AdminSupport {
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH).withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).withZone(ZoneId.systemDefault());
    private static final CronParser CRON = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX));

    @Inject TtsEngine tts;
    @Inject Validator validator;
    @Inject ShowScheduler scheduler;

    public Set<String> voices() {
        try {
            return tts.voices();
        } catch (Exception e) {
            return Set.of();
        }
    }

    public List<String> beanErrors(ShowRequest request) {
        List<String> errors = new ArrayList<>();
        for (ConstraintViolation<ShowRequest> v : validator.validate(request)) errors.add(v.getPropertyPath() + " " + v.getMessage());
        Collections.sort(errors);
        return errors;
    }

    public ShowCard showCard(Show s) {
        Optional<Run> last = QuarkusTransaction.requiringNew().call(() ->
                Run.<Run>find("showId = ?1 order by startedAt desc", s.id).firstResultOptional());
        Optional<Episode> latest = latestEpisode(s.id);
        long fresh = QuarkusTransaction.requiringNew().call(() -> Item.count(
                "showId = ?1 and usedInEpisodeId is null and coalesce(publishedAt, fetchedAt) >= ?2", s.id, Instant.now().minus(Duration.ofHours(24))));
        long episodes = QuarkusTransaction.requiringNew().call(() -> Episode.count("showId = ?1 and publishedAt is not null", s.id));
        String meta = s.language + " · " + s.voiceId + " · " + s.writerModel + " · " + s.targetDurationMinutes + " min";
        return new ShowCard(s.id, s.name, s.slug, meta,
                last.map(r -> "Last run: " + label(r.status).toLowerCase()).orElse("No runs yet"),
                last.map(r -> tone(r.status)).orElse("neutral"),
                latest.map(e -> episodeLink(e, s.name)).orElse(null),
                scheduler.nextRun(s.id).map(AdminSupport::when).orElse(s.cron == null ? "Not scheduled" : cronText(s.cron)),
                fresh, episodes);
    }

    public Optional<Episode> latestEpisode(long showId) {
        return QuarkusTransaction.requiringNew().call(() -> Episode.<Episode>find(
                "showId = ?1 and publishedAt is not null and audioPath is not null order by publishedAt desc", showId).firstResultOptional());
    }

    public EpisodeLink episodeLink(Episode e, String showName) {
        return new EpisodeLink(e.id, e.title == null ? "Untitled episode" : e.title, e.showId, showName,
                day(e.publishedAt != null ? e.publishedAt : e.createdAt), duration(e.durationSeconds),
                e.audioPath == null ? null : "/media/" + e.audioPath);
    }

    public RunRow runRow(Run r, String showName) {
        return new RunRow(r.id, r.showId, showName, stages(r), label(r.status), tone(r.status), when(r.startedAt), r.error);
    }

    public List<Attention> attention() {
        List<Attention> out = new ArrayList<>();
        QuarkusTransaction.requiringNew().run(() -> {
            for (Source s : Source.<Source>list("lastError is not null order by id")) {
                out.add(new Attention("Source failing: " + s.label(), abbreviate(s.lastError, 200), "warn", "/admin/shows/" + s.showId + "?tab=sources"));
            }
            for (Run r : Run.<Run>list("status = ?1 and startedAt >= ?2 order by startedAt desc", RunStatus.FAILED, Instant.now().minus(Duration.ofDays(7)))) {
                out.add(new Attention("Run #" + r.id + " failed", abbreviate(r.error, 200), "fail", "/admin/shows/" + r.showId));
            }
        });
        return out;
    }

    public LiveRun liveRun(long showId) {
        Optional<Run> run = QuarkusTransaction.requiringNew().call(() ->
                Run.<Run>find("showId = ?1 order by startedAt desc", showId).firstResultOptional());
        if (run.isEmpty()) return null;
        Run r = run.get();
        Long episodeId = QuarkusTransaction.requiringNew().call(() -> Episode.findByRun(r.id).map(e -> e.publishedAt == null ? null : e.id).orElse(null));
        return new LiveRun(r.id, r.status == RunStatus.RUNNING, label(r.status), tone(r.status), r.trigger.name().toLowerCase(),
                when(r.startedAt), steps(r), r.progress, r.error, episodeId);
    }

    /** Chapters with their sources; episodes without recorded chapters fall back to the outline (no timestamps). */
    public List<ChapterView> chapters(Episode e) {
        List<Chapter> chapters = e.chapters;
        if (chapters == null || chapters.isEmpty()) {
            if (e.outline == null) return List.of();
            chapters = e.outline.segments().stream().map(s -> new Chapter(s.headline(), -1, s.itemIds())).toList();
        }
        Set<Long> ids = chapters.stream().flatMap(c -> c.itemIds().stream()).collect(Collectors.toSet());
        Map<Long, Item> items = ids.isEmpty() ? Map.of() : QuarkusTransaction.requiringNew().call(() ->
                Item.<Item>list("id in ?1", ids).stream().collect(Collectors.toMap(i -> i.id, Function.identity())));
        Show show = QuarkusTransaction.requiringNew().call(() -> Show.<Show>findById(e.showId));
        boolean italian = show != null && show.language != null && show.language.startsWith("it");
        List<ChapterView> out = new ArrayList<>();
        for (Chapter c : chapters) {
            List<SourceLink> sources = new ArrayList<>();
            for (Long id : c.itemIds()) {
                Item item = items.get(id);
                if (item == null) continue;
                sources.add(new SourceLink(site(item.url), item.title, item.url));
                if (item.discussionUrl != null && !item.discussionUrl.isBlank()) {
                    sources.add(new SourceLink("Reddit", italian ? "Discussione su Reddit" : "Reddit discussion", item.discussionUrl));
                }
            }
            out.add(new ChapterView(c.title(), c.startSeconds() < 0 ? null : duration(c.startSeconds()), c.startSeconds(), sources));
        }
        return out;
    }

    public static String when(Instant i) { return i == null ? "—" : WHEN.format(i); }

    public static String day(Instant i) { return i == null ? "—" : DAY.format(i); }

    public static String duration(Double seconds) {
        if (seconds == null) return "—";
        long t = Math.round(seconds);
        return (t / 60) + ":" + String.format("%02d", t % 60);
    }

    public static String tone(RunStatus s) {
        return switch (s) { case RUNNING -> "run"; case DONE -> "ok"; case FAILED -> "fail"; case SKIPPED -> "neutral"; };
    }

    public static String label(RunStatus s) {
        return switch (s) { case RUNNING -> "Running"; case DONE -> "Done"; case FAILED -> "Failed"; case SKIPPED -> "Skipped"; };
    }

    static String state(Run r, RunStage stage) {
        if (r.status == RunStatus.DONE) return "done";
        int cmp = Integer.compare(stage.ordinal(), r.stage.ordinal());
        if (cmp < 0) return "done";
        if (cmp > 0) return "pending";
        return switch (r.status) { case RUNNING -> "run"; case FAILED -> "fail"; case SKIPPED -> "skip"; default -> "done"; };
    }

    public static List<StageDot> stages(Run r) {
        return Arrays.stream(RunStage.values()).map(st -> new StageDot(stageName(st), state(r, st))).toList();
    }

    public static List<Step> steps(Run r) {
        return Arrays.stream(RunStage.values()).map(st -> {
            String state = state(r, st);
            String detail = switch (state) { case "done" -> "done"; case "run" -> "in progress"; case "fail" -> "failed"; case "skip" -> "skipped"; default -> "waiting"; };
            return new Step(stageName(st), detail, state);
        }).toList();
    }

    static String stageName(RunStage st) {
        String n = st.name().toLowerCase();
        return st == RunStage.TTS ? "TTS" : Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    public static String site(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? url : host.replaceFirst("^www\\.", "");
        } catch (IllegalArgumentException e) {
            return url;
        }
    }

    public static String cronText(String cron) {
        if (cron == null || cron.isBlank()) return "Not scheduled";
        try {
            return CronDescriptor.instance(Locale.ENGLISH).describe(CRON.parse(cron.trim()));
        } catch (RuntimeException e) {
            return cron;
        }
    }

    static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
```

- [ ] **Step 6: Rewrite `AdminResource` (login, logout, dashboard, settings)**

Keep every show, source, run, episode and prompt endpoint in `AdminResource` for now. Tasks 5–7 move them out and delete them from this class. Change only:

```java
    // Templates: add
        static native TemplateInstance dashboard(List<ShowCard> shows, List<RunRow> runs, List<Attention> attention);
        static native TemplateInstance settings(Set<String> models, Set<String> voices, Set<String> connectors,
                                                boolean telegram, String baseUrl, String version);

    @Inject AdminSupport support;

    @GET
    public TemplateInstance dashboard() {
        List<Show> all = QuarkusTransaction.requiringNew().call(() -> Show.<Show>listAll(io.quarkus.panache.common.Sort.by("name")));
        Map<Long, String> names = new HashMap<>();
        all.forEach(s -> names.put(s.id, s.name));
        List<RunRow> runs = QuarkusTransaction.requiringNew().call(() ->
                Run.<Run>find("order by startedAt desc").page(0, 8).list()).stream()
                .map(r -> support.runRow(r, names.getOrDefault(r.showId, "Deleted show"))).toList();
        return Templates.dashboard(all.stream().map(support::showCard).toList(), runs, support.attention());
    }

    @GET
    @Path("/settings")
    public TemplateInstance settings() {
        boolean telegram = config.telegram().botToken().isPresent() && config.telegram().chatId().isPresent();
        String version = org.eclipse.microprofile.config.ConfigProvider.getConfig()
                .getOptionalValue("quarkus.application.version", String.class).orElse("dev");
        return Templates.settings(models.availableNames(), support.voices(), connectors.types(), telegram, config.baseUrl(), version);
    }
```

Delete the old `index()` method (the `GET /admin` redirect). In `login(...)` change the redirect target to `/admin`. Add `import org.roncax.podcaster.admin.AdminViews.*;`.

Add to `TemplateTypes`: `@TemplateData(target = AdminViews.StageDot.class)`, `@TemplateData(target = AdminViews.Step.class)`, `@TemplateData(target = AdminViews.ChapterView.class)`, `@TemplateData(target = AdminViews.SourceLink.class)`. These are the records used inside untyped tags.

- [ ] **Step 7: Templates**

`templates/AdminResource/dashboard.html`:

```html
{#layout title="Dashboard" active="dashboard"}
<header class="flex flex-wrap items-end gap-4">
  <div class="flex flex-col gap-1">
    <span class="mono text-xs tracking-wide text-muted">PODCASTER</span>
    <h1 class="m-0 text-3xl font-bold">Dashboard</h1>
  </div>
  <div class="ml-auto flex gap-2.5">
    <a href="/admin/shows#new-show" class="btn btn-primary">New show</a>
  </div>
</header>

{#if shows.isEmpty}
  {#emptyState title="No shows yet"}Create a show, add sources and press Run now.{/emptyState}
{#else}
<section aria-label="Shows" class="grid gap-5 md:grid-cols-2">
  {#for s in shows}
  <article class="card">
    <div class="flex items-start gap-3">
      <div class="flex flex-col gap-1">
        <h2 class="m-0 text-xl font-semibold"><a href="/admin/shows/{s.id}" class="text-ink no-underline hover:underline">{s.name}</a></h2>
        <span class="text-[13px] text-muted">{s.meta}</span>
      </div>
      <span class="ml-auto">{#badge tone=s.lastRunTone}{s.lastRunLabel}{/badge}</span>
    </div>
    {#if s.latest}
    <div class="flex flex-col gap-2 rounded-xl bg-ground p-3">
      <a href="/admin/episodes/{s.latest.id}" class="text-sm font-medium text-ink no-underline hover:underline">{s.latest.title}</a>
      {#if s.latest.audioUrl}<audio controls preload="none" src="{s.latest.audioUrl}" class="w-full"></audio>{/if}
      <span class="mono text-xs text-muted">{s.latest.date} · {s.latest.duration}</span>
    </div>
    {#else}
      {#emptyState title="No episodes yet"}Press Run now to produce the first episode.{/emptyState}
    {/if}
    <dl class="m-0 grid grid-cols-3 gap-3 text-sm">
      <div class="flex flex-col gap-0.5"><dt class="text-xs text-muted">Next run</dt><dd class="m-0 font-medium">{s.nextRun}</dd></div>
      <div class="flex flex-col gap-0.5"><dt class="text-xs text-muted">Episodes</dt><dd class="m-0 font-medium">{s.episodes}</dd></div>
      <div class="flex flex-col gap-0.5"><dt class="text-xs text-muted">Fresh items (24 h)</dt><dd class="m-0 font-medium">{s.freshItems}</dd></div>
    </dl>
    <div class="flex flex-wrap gap-2.5">
      <form method="post" action="/admin/shows/{s.id}/run"><button type="submit" class="btn btn-primary">Run now</button></form>
      <a href="/admin/shows/{s.id}" class="btn btn-secondary">Open show</a>
      <a href="/feeds/{s.slug}.xml" class="btn btn-secondary">Feed</a>
    </div>
  </article>
  {/for}
</section>
{/if}

<section class="flex flex-wrap items-start gap-5">
  <div class="card grow-[999] basis-[520px] min-w-0">
    <h2 class="m-0 text-base font-semibold">Recent runs</h2>
    {#if runs.isEmpty}<p class="m-0 text-sm text-muted">No runs yet.</p>{/if}
    <ul class="m-0 flex flex-col p-0" role="list">
      {#for r in runs}
      <li class="flex flex-wrap items-center gap-3.5 border-t border-line py-3 first:border-t-0">
        <span class="mono w-12 text-xs text-muted">#{r.id}</span>
        <a href="/admin/shows/{r.showId}" class="min-w-36 text-sm font-medium text-ink no-underline hover:underline">{r.showName}</a>
        {#stages stages=r.stages label=r.status /}
        {#badge tone=r.tone}{r.status}{/badge}
        <span class="ml-auto text-[13px] text-muted">{r.when}</span>
      </li>
      {/for}
    </ul>
  </div>
  <aside class="card grow basis-72" aria-label="Needs attention">
    <h2 class="m-0 text-base font-semibold">Needs attention</h2>
    {#if attention.isEmpty}<p class="m-0 text-sm text-muted">All good.</p>{/if}
    {#for a in attention}
    <a href="{a.href}" class="flex flex-col gap-0.5 rounded-xl p-3 no-underline {#when a.tone}{#is 'fail'}bg-fail-bg text-fail{#else}bg-warn-bg text-warn{/when}">
      <span class="text-sm font-medium">{a.title}</span>
      <span class="text-[13px] opacity-90">{a.detail}</span>
    </a>
    {/for}
  </aside>
</section>
{/layout}
```

`templates/AdminResource/settings.html`:

```html
{#layout title="Settings" active="settings"}
<h1 class="m-0 text-3xl font-bold">Settings</h1>
<p class="m-0 text-sm text-muted">Read-only. Change these in <code class="mono">.env</code> and restart the stack.</p>
<div class="grid gap-5 md:grid-cols-2">
  <section class="card"><h2 class="m-0 text-base font-semibold">LLM model slots (enabled)</h2>
    <ul class="m-0 flex flex-wrap gap-2 p-0" role="list">{#for m in models}<li class="chip mono">{m}</li>{#else}<li class="text-sm text-muted">None enabled</li>{/for}</ul></section>
  <section class="card"><h2 class="m-0 text-base font-semibold">Piper voices</h2>
    <ul class="m-0 flex flex-wrap gap-2 p-0" role="list">{#for v in voices}<li class="chip mono">{v}</li>{#else}<li class="text-sm text-muted">Piper unreachable</li>{/for}</ul></section>
  <section class="card"><h2 class="m-0 text-base font-semibold">Source connectors</h2>
    <ul class="m-0 flex flex-wrap gap-2 p-0" role="list">{#for c in connectors}<li class="chip mono">{c}</li>{/for}</ul></section>
  <section class="card"><h2 class="m-0 text-base font-semibold">System</h2>
    <dl class="m-0 grid grid-cols-[auto_1fr] gap-x-6 gap-y-2 text-sm">
      <dt class="text-muted">Telegram alerts</dt><dd class="m-0">{#if telegram}Enabled{#else}Disabled{/if}</dd>
      <dt class="text-muted">Base URL</dt><dd class="m-0 mono">{baseUrl}</dd>
      <dt class="text-muted">Version</dt><dd class="m-0 mono">{version}</dd>
    </dl></section>
</div>
{/layout}
```

- [ ] **Step 8: Run tests**

Run: `./mvnw -q clean test -Dtest='AdminTest,AssetsTest,PromptAdminTest'`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "Add admin dashboard and settings pages on the new layout

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01LbcoMupq1VXnfzdqroFmyp"
```

---

### Task 5: Show pages — list, detail with tabs, live run card, sources

**Files:**
- Create: `src/main/java/org/roncax/podcaster/admin/ShowPages.java`
- Create: `templates/ShowPages/{list,detail,live,sourceTest}.html`
- Modify: `admin/AdminResource.java` (remove show, source and run endpoints and their templates), `templates/AdminResource/` (delete `shows.html`, `show.html`, `runs.html`, `sourceTest.html`), `templates/tags/` (delete `showForm.html`, `runTable.html`)
- Test: `src/test/java/org/roncax/podcaster/admin/ShowPagesTest.java`

**Interfaces:**
- Consumes: `AdminSupport`, `AdminViews` (Task 4); `ShowService`, `RunLauncher`, `PromptRegistry`, `ShowForm`, `PromptAdminViews.OverrideRow`.
- Produces routes (same URLs as before):
  - `GET /admin/shows`, `POST /admin/shows`, `GET /admin/shows/{id}?tab=overview|sources|episodes|settings|prompts`
  - `POST /admin/shows/{id}` (update), `POST /admin/shows/{id}/delete`
  - `POST /admin/shows/{id}/sources` (form fields `connectorType`, `url`, `subreddit`, `window`, `maxPosts`, `config`, `fetchFullText`), `POST /admin/sources/{id}/delete`, `POST /admin/sources/{id}/test` (fragment)
  - `POST /admin/shows/{id}/run` and `POST /admin/runs/{id}/retry`: live fragment for htmx (`HX-Request` header), 303 to the show page otherwise
  - `GET /admin/shows/{id}/live` (fragment), `POST /admin/shows/{id}/prompts` (overrides)

- [ ] **Step 1: Write the failing test**

```java
package org.roncax.podcaster.admin;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.domain.*;
import org.roncax.podcaster.support.*;

@QuarkusTest
@WithTestResource(WireMockResource.class)
@WithTestResource(org.roncax.podcaster.support.PostgresResource.class)
class ShowPagesTest {
    @InjectWireMock WireMockServer wm;

    @BeforeEach
    void setup() {
        TestData.cleanDb();
        WireMockResource.installDefaults(wm);
        FakeChatModelRegistry.install(new FakeChatModel().responder(FakeResponses::pipeline));
    }

    private RequestSpecification admin() {
        return given().cookie("podcaster_key", "test-api-key-0123456789").redirects().follow(false);
    }

    private Run run(Show show, RunStatus status, RunStage stage, String progress) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Run r = new Run();
            r.showId = show.id; r.trigger = RunTrigger.MANUAL; r.status = status; r.stage = stage; r.since = Instant.now(); r.progress = progress;
            r.persist();
            return r;
        });
    }

    @Test
    void listsShowsWithFeedLinks() {
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
        admin().get(location + "?tab=settings").then().statusCode(200).body(containsStringIgnoringCase("at 07:00"));
    }

    @Test
    void invalidShowFormIsRerenderedWithErrors() {
        admin().formParam("name", "X").formParam("slug", "x").formParam("language", "it")
                .formParam("voiceId", "it_IT-paola-medium").formParam("writerModel", "gpt")
                .formParam("targetDurationMinutes", "abc")
                .post("/admin/shows").then().statusCode(200)
                .body(containsString("writerModel &#39;gpt&#39;"))
                .body(containsString("targetDurationMinutes must be a number"));
    }

    @Test
    void addsRssAndRedditSourcesFromForm() {
        Show show = TestData.show("src");
        admin().formParam("connectorType", "rss").formParam("url", wm.baseUrl() + "/feed").formParam("fetchFullText", "on")
                .post("/admin/shows/" + show.id + "/sources").then().statusCode(303);
        admin().formParam("connectorType", "reddit").formParam("subreddit", "italy").formParam("window", "day").formParam("maxPosts", "8")
                .post("/admin/shows/" + show.id + "/sources").then().statusCode(303);
        Map<String, String> reddit = QuarkusTransaction.requiringNew().call(() ->
                Source.<Source>find("showId = ?1 and connectorType = 'reddit'", show.id).firstResult().config);
        assertEquals(Map.of("subreddit", "italy", "window", "day", "maxPosts", "8"), reddit);
        admin().get("/admin/shows/" + show.id + "?tab=sources").then().statusCode(200)
                .body(containsString(wm.baseUrl() + "/feed")).body(containsString("r/italy"));
    }

    @Test
    void invalidSourceFormShowsErrors() {
        Show show = TestData.show("srcerr");
        admin().formParam("connectorType", "reddit").formParam("subreddit", "")
                .post("/admin/shows/" + show.id + "/sources").then().statusCode(200)
                .contentType(containsString("text/html"))
                .body(containsString("reddit sources need config.subreddit"));
    }

    @Test
    void runNowReturnsLiveCardForHtmxAndRedirectsOtherwise() {
        Show show = TestData.show("live");
        wm.stubFor(get("/livefeed").willReturn(okXml("<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>t</title><link>http://x</link><description>d</description></channel></rss>")));
        TestData.source(show.id, wm.baseUrl() + "/livefeed");
        admin().header("HX-Request", "true").post("/admin/shows/" + show.id + "/run").then().statusCode(200)
                .body(containsString("id=\"live-run\""));
        TestData.awaitRun(QuarkusTransaction.requiringNew().call(() -> Run.<Run>find("showId", show.id).firstResult().id));
        admin().post("/admin/shows/" + show.id + "/run").then().statusCode(303).header("Location", endsWith("/admin/shows/" + show.id));
    }

    @Test
    void liveFragmentPollsWhileRunningWithProgress() {
        Show show = TestData.show("poll");
        run(show, RunStatus.RUNNING, RunStage.SCRIPT, "Writing segment 3 of 9");
        admin().get("/admin/shows/" + show.id + "/live").then().statusCode(200)
                .body(containsString("hx-trigger=\"every 3s\""))
                .body(containsString("Writing segment 3 of 9"))
                .body(containsString("in progress"));
    }

    @Test
    void liveFragmentStopsPollingWhenFinished() {
        Show show = TestData.show("finished");
        Run r = run(show, RunStatus.FAILED, RunStage.TTS, null);
        QuarkusTransaction.requiringNew().run(() -> Run.<Run>findById(r.id).error = "TTS: Piper returned HTTP 503");
        String html = admin().get("/admin/shows/" + show.id + "/live").then().statusCode(200).extract().asString();
        assertFalse(html.contains("hx-trigger"), html);
        assertTrue(html.contains("TTS: Piper returned HTTP 503"));
        assertTrue(html.contains("/admin/runs/" + r.id + "/retry"));
    }

    @Test
    void tabsRender() {
        Show show = TestData.show("tabs");
        for (String tab : new String[] {"overview", "sources", "episodes", "settings", "prompts"}) {
            admin().get("/admin/shows/" + show.id + "?tab=" + tab).then().statusCode(200).body(containsString("aria-selected=\"true\""));
        }
        admin().get("/admin/shows/" + show.id + "?tab=prompts").then().statusCode(200).body(containsString("Prompt overrides"));
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -q test -Dtest=ShowPagesTest`
Expected: FAIL (old markup: no tabs, no `/live` route, no `id="live-run"`).

- [ ] **Step 3: Implement `ShowPages`** (move the show/source/run/override endpoints here from `AdminResource`; delete them there)

```java
package org.roncax.podcaster.admin;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.panache.common.Sort;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.RestHeader;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestQuery;
import org.roncax.podcaster.admin.AdminViews.*;
import org.roncax.podcaster.admin.PromptAdminViews.OverrideRow;
import org.roncax.podcaster.api.*;
import org.roncax.podcaster.domain.*;
import org.roncax.podcaster.generation.Prompts;
import org.roncax.podcaster.ingestion.ConnectorRegistry;
import org.roncax.podcaster.ingestion.reddit.RedditSourceConnector;
import org.roncax.podcaster.ingestion.RssSourceConnector;
import org.roncax.podcaster.llm.ChatModelRegistry;
import org.roncax.podcaster.prompts.PromptKey;
import org.roncax.podcaster.prompts.PromptRegistry;
import org.roncax.podcaster.runs.RunAlreadyActiveException;
import org.roncax.podcaster.runs.RunLauncher;

@Path("/admin")
@Produces(MediaType.TEXT_HTML)
public class ShowPages {
    static final Set<String> TABS = Set.of("overview", "sources", "episodes", "settings", "prompts");

    @CheckedTemplate
    static class Templates {
        static native TemplateInstance list(List<ShowCard> shows, ShowForm form, List<String> errors, Set<String> models, Set<String> voices);
        static native TemplateInstance detail(Show show, String tab, List<String> chips, LiveRun live,
                                              List<EpisodeLink> episodes, List<RunRow> runs, List<SourceRow> sources,
                                              List<String> sourceErrors, ShowForm form, String formAction, List<String> errors,
                                              Set<String> models, Set<String> voices, Set<String> connectors,
                                              List<OverrideRow> overrides, String cronText);
        static native TemplateInstance live(long showId, LiveRun live);
        static native TemplateInstance sourceTest(SourceTestResult result);
    }

    @Inject ShowService shows;
    @Inject RunLauncher launcher;
    @Inject ChatModelRegistry models;
    @Inject ConnectorRegistry connectors;
    @Inject PromptRegistry prompts;
    @Inject AdminSupport support;

    @GET
    @Path("/shows")
    public TemplateInstance list() {
        return listTemplate(ShowForm.defaults(), List.of());
    }

    @POST
    @Path("/shows")
    public Response create(@BeanParam ShowForm form) {
        List<String> errors = ShowForm.newErrors();
        ShowRequest request = form.toRequest(errors);
        errors.addAll(support.beanErrors(request));
        errors.addAll(shows.validationErrors(request, null));
        if (errors.isEmpty()) {
            try {
                Show show = shows.create(request);
                return Response.seeOther(URI.create("/admin/shows/" + show.id)).build();
            } catch (InvalidRequestException e) {
                errors.addAll(e.errors());
            }
        }
        return Response.ok(listTemplate(form, errors)).build();
    }

    @GET
    @Path("/shows/{id}")
    public TemplateInstance detail(@RestPath long id, @RestQuery String tab) {
        Show show = find(id);
        return detailTemplate(show, tab, ShowForm.from(show), List.of(), List.of());
    }

    @POST
    @Path("/shows/{id}")
    public Response update(@RestPath long id, @BeanParam ShowForm form) {
        Show existing = find(id);
        List<String> errors = ShowForm.newErrors();
        ShowRequest request = form.toRequest(errors);
        errors.addAll(support.beanErrors(request));
        errors.addAll(shows.validationErrors(request, id));
        if (errors.isEmpty()) {
            try {
                shows.update(id, request);
                return Response.seeOther(URI.create("/admin/shows/" + id + "?tab=settings")).build();
            } catch (InvalidRequestException e) {
                errors.addAll(e.errors());
            }
        }
        return Response.ok(detailTemplate(existing, "settings", form, errors, List.of())).build();
    }

    @POST
    @Path("/shows/{id}/delete")
    public Response delete(@RestPath long id) {
        shows.delete(id);
        return Response.seeOther(URI.create("/admin/shows")).build();
    }

    @POST
    @Path("/shows/{id}/sources")
    public Response addSource(@RestPath long id, @RestForm String connectorType, @RestForm String url,
                              @RestForm String subreddit, @RestForm String window, @RestForm String maxPosts,
                              @RestForm String config, @RestForm String fetchFullText) {
        Map<String, String> cfg = new LinkedHashMap<>(parseConfig(config));
        if (RssSourceConnector.TYPE.equals(connectorType) && notBlank(url)) cfg.put("url", url.trim());
        if (RedditSourceConnector.TYPE.equals(connectorType)) {
            if (notBlank(subreddit)) cfg.put("subreddit", subreddit.trim().replaceFirst("^r/", ""));
            if (notBlank(window)) cfg.put("window", window.trim());
            if (notBlank(maxPosts)) cfg.put("maxPosts", maxPosts.trim());
        }
        try {
            shows.addSource(id, new SourceRequest(connectorType, cfg, fetchFullText != null, true));
            return Response.seeOther(URI.create("/admin/shows/" + id + "?tab=sources")).build();
        } catch (InvalidRequestException e) {
            Show show = find(id);
            return Response.ok(detailTemplate(show, "sources", ShowForm.from(show), List.of(), e.errors())).build();
        }
    }

    @POST
    @Path("/sources/{id}/delete")
    public Response deleteSource(@RestPath long id) {
        Source source = QuarkusTransaction.requiringNew().call(() -> Source.<Source>findByIdOptional(id).orElseThrow(NotFoundException::new));
        shows.deleteSource(id);
        return Response.seeOther(URI.create("/admin/shows/" + source.showId + "?tab=sources")).build();
    }

    @POST
    @Path("/sources/{id}/test")
    public TemplateInstance testSource(@RestPath long id) {
        return Templates.sourceTest(shows.testSource(id));
    }

    @POST
    @Path("/shows/{id}/run")
    public Response runNow(@RestPath long id, @RestHeader("HX-Request") String htmx) {
        try {
            launcher.launch(id, RunTrigger.MANUAL);
        } catch (RunAlreadyActiveException ignored) {
            // the live card already shows the active run
        }
        return htmx != null ? Response.ok(Templates.live(id, support.liveRun(id))).build()
                : Response.seeOther(URI.create("/admin/shows/" + id)).build();
    }

    @POST
    @Path("/runs/{id}/retry")
    public Response retry(@RestPath long id, @RestHeader("HX-Request") String htmx) {
        Run run = QuarkusTransaction.requiringNew().call(() -> Run.<Run>findByIdOptional(id).orElseThrow(NotFoundException::new));
        try {
            launcher.retry(id);
        } catch (IllegalStateException | RunAlreadyActiveException ignored) {
            // status is visible in the refreshed card
        }
        return htmx != null ? Response.ok(Templates.live(run.showId, support.liveRun(run.showId))).build()
                : Response.seeOther(URI.create("/admin/shows/" + run.showId)).build();
    }

    @GET
    @Path("/shows/{id}/live")
    public TemplateInstance live(@RestPath long id) {
        return Templates.live(id, support.liveRun(id));
    }

    @POST
    @Path("/shows/{id}/prompts")
    public Response saveOverrides(@RestPath long id, @RestForm String rank, @RestForm String segment,
                                  @RestForm String framing, @RestForm("json_repair") String jsonRepair) {
        Map<PromptKey, String> form = Map.of(PromptKey.RANK, nz(rank), PromptKey.SEGMENT, nz(segment),
                PromptKey.FRAMING, nz(framing), PromptKey.JSON_REPAIR, nz(jsonRepair));
        form.forEach((key, value) -> {
            if (value.isBlank()) prompts.unpin(id, key);
            else prompts.pin(id, key, Integer.parseInt(value.trim()));
        });
        return Response.seeOther(URI.create("/admin/shows/" + id + "?tab=prompts")).build();
    }

    private TemplateInstance listTemplate(ShowForm form, List<String> errors) {
        List<Show> all = QuarkusTransaction.requiringNew().call(() -> Show.<Show>listAll(Sort.by("name")));
        return Templates.list(all.stream().map(support::showCard).toList(), form, errors, models.availableNames(), support.voices());
    }

    private TemplateInstance detailTemplate(Show show, String tab, ShowForm form, List<String> errors, List<String> sourceErrors) {
        String t = tab != null && TABS.contains(tab) ? tab : (sourceErrors.isEmpty() ? "overview" : "sources");
        List<String> chips = List.of(Prompts.languageName(show.language), "Voice: " + show.voiceId, "Model: " + show.writerModel,
                "Target " + show.targetDurationMinutes + " min", AdminSupport.cronText(show.cron));
        List<Episode> eps = QuarkusTransaction.requiringNew().call(() ->
                Episode.<Episode>find("showId = ?1 and publishedAt is not null order by publishedAt desc", show.id).list());
        List<EpisodeLink> episodes = eps.stream().map(e -> support.episodeLink(e, show.name)).toList();
        List<RunRow> runs = QuarkusTransaction.requiringNew().call(() ->
                Run.<Run>find("showId = ?1 order by startedAt desc", show.id).page(0, 10).list()).stream()
                .map(r -> support.runRow(r, show.name)).toList();
        Instant dayAgo = Instant.now().minus(Duration.ofHours(24));
        List<SourceRow> sources = QuarkusTransaction.requiringNew().call(() -> Source.<Source>list("showId = ?1 order by id", show.id)).stream()
                .map(s -> {
                    long today = QuarkusTransaction.requiringNew().call(() -> Item.count("sourceId = ?1 and fetchedAt >= ?2", s.id, dayAgo));
                    boolean reddit = RedditSourceConnector.TYPE.equals(s.connectorType);
                    String name = reddit ? "r/" + s.config.getOrDefault("subreddit", "?") : AdminSupport.site(s.config.getOrDefault("url", s.connectorType));
                    String detail = reddit ? "top of " + s.config.getOrDefault("window", "day") : s.config.getOrDefault("url", "");
                    return new SourceRow(s.id, name, detail, reddit ? "Reddit" : s.connectorType.toUpperCase(), reddit ? "warn" : "info",
                            AdminSupport.when(s.lastFetchedAt), today,
                            s.lastError == null ? "OK" : AdminSupport.abbreviate(s.lastError, 80), s.lastError == null ? "ok" : "warn");
                }).toList();
        return Templates.detail(show, t, chips, support.liveRun(show.id), episodes, runs, sources, sourceErrors, form,
                "/admin/shows/" + show.id, errors, models.availableNames(), support.voices(), connectors.types(),
                overrideRows(show.id), AdminSupport.cronText(show.cron));
    }

    private List<OverrideRow> overrideRows(long showId) {
        Map<PromptKey, Integer> pinned = prompts.overrides(showId);
        List<OverrideRow> rows = new ArrayList<>();
        for (PromptKey key : PromptKey.values()) {
            rows.add(new OverrideRow(key.dbKey(), pinned.get(key), prompts.versions(key).stream().map(v -> v.version).toList()));
        }
        return rows;
    }

    private static Show find(long id) {
        return QuarkusTransaction.requiringNew().call(() -> Show.<Show>findByIdOptional(id).orElseThrow(NotFoundException::new));
    }

    static Map<String, String> parseConfig(String text) {
        Map<String, String> config = new LinkedHashMap<>();
        if (text == null) return config;
        for (String line : text.split("\\R")) {
            int eq = line.indexOf('=');
            if (eq > 0) config.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        return config;
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    private static String nz(String s) { return s == null ? "" : s; }
}
```

Remove from `AdminResource`: `listShows`, `createShow`, `showPage`, `updateShow`, `deleteShow`, `addSource`, `deleteSource`, `testSource`, `runNow`, `runsFragment`, `retry`, `saveShowPrompts`, `showTemplate`, `recentRuns`, `overrideRows`, `parseConfig`, `nz`, `beanErrors`, `voices`, and the template declarations `shows`, `show`, `runs`, `sourceTest`. Keep the prompt and episode endpoints until Tasks 6–7.

- [ ] **Step 4: Templates**

`templates/ShowPages/list.html`:

```html
{#layout title="Shows" active="shows"}
<header class="flex flex-wrap items-end gap-4">
  <h1 class="m-0 text-3xl font-bold">Shows</h1>
</header>
<section class="grid gap-5 md:grid-cols-2" aria-label="Shows">
  {#for s in shows}
  <article class="card">
    <div class="flex items-start gap-3">
      <div class="flex flex-col gap-1">
        <h2 class="m-0 text-xl font-semibold"><a href="/admin/shows/{s.id}" class="text-ink no-underline hover:underline">{s.name}</a></h2>
        <span class="text-[13px] text-muted">{s.meta}</span>
      </div>
      <span class="ml-auto">{#badge tone=s.lastRunTone}{s.lastRunLabel}{/badge}</span>
    </div>
    <div class="flex flex-wrap gap-2.5">
      <a href="/admin/shows/{s.id}" class="btn btn-secondary btn-sm">Open</a>
      <a href="/feeds/{s.slug}.xml" class="btn btn-secondary btn-sm">/feeds/{s.slug}.xml</a>
    </div>
  </article>
  {#else}
  {#emptyState title="No shows yet"}Create your first show below.{/emptyState}
  {/for}
</section>
<details id="new-show" class="card" {#if errors}open{/if}>
  <summary class="cursor-pointer text-base font-semibold">New show</summary>
  {#include ShowPages/showFields form=form errors=errors models=models voices=voices action="/admin/shows" submit="Create show" /}
</details>
{/layout}
```

`templates/ShowPages/showFields.html` (included by `list` and `detail`):

```html
{#if errors}<div role="alert" class="rounded-xl bg-fail-bg p-3 text-sm text-fail"><ul class="m-0 pl-5">{#for e in errors}<li>{e}</li>{/for}</ul></div>{/if}
<form method="post" action="{action}" class="flex flex-col gap-5">
  <fieldset class="m-0 grid gap-4 border-0 p-0 md:grid-cols-2">
    <legend class="mb-2 text-sm font-semibold">General</legend>
    <label class="field"><span class="field-label">Name</span><input class="field-input" name="name" value="{form.name ?: ''}" required></label>
    <label class="field"><span class="field-label">Slug (feed URL)</span><input class="field-input mono" name="slug" value="{form.slug ?: ''}" required></label>
    <label class="field md:col-span-2"><span class="field-label">Description</span><input class="field-input" name="description" value="{form.description ?: ''}"></label>
    <label class="field"><span class="field-label">Language</span><input class="field-input" name="language" value="{form.language ?: ''}" placeholder="it" required></label>
    <label class="field"><span class="field-label">Editorial focus</span><input class="field-input" name="focusPrompt" value="{form.focusPrompt ?: ''}" placeholder="e.g. Skip sports and gossip"></label>
  </fieldset>
  <fieldset class="m-0 grid gap-4 border-0 p-0 md:grid-cols-3">
    <legend class="mb-2 text-sm font-semibold">Voice &amp; model</legend>
    <label class="field"><span class="field-label">Voice</span>
      <select class="field-input" name="voiceId" required>
        {#for v in voices}<option value="{v}" {#if v == form.voiceId}selected{/if}>{v}</option>{/for}
        {#if voices.isEmpty}<option value="{form.voiceId ?: ''}" selected>{form.voiceId ?: 'Piper unreachable'}</option>{/if}
      </select></label>
    <label class="field"><span class="field-label">Speed (length scale)</span><input class="field-input mono" name="lengthScale" type="number" step="0.05" min="0.5" max="2" value="{form.lengthScale ?: '1.0'}"></label>
    <label class="field"><span class="field-label">Writer model</span>
      <select class="field-input" name="writerModel" required>{#for m in models}<option value="{m}" {#if m == form.writerModel}selected{/if}>{m}</option>{/for}</select></label>
    <label class="field"><span class="field-label">Ranker model</span>
      <select class="field-input" name="rankerModel"><option value="">(same as writer)</option>{#for m in models}<option value="{m}" {#if m == form.rankerModel}selected{/if}>{m}</option>{/for}</select></label>
  </fieldset>
  <fieldset class="m-0 grid gap-4 border-0 p-0 md:grid-cols-3">
    <legend class="mb-2 text-sm font-semibold">Episodes &amp; schedule</legend>
    <label class="field"><span class="field-label">Target minutes</span><input class="field-input mono" name="targetDurationMinutes" value="{form.targetDurationMinutes ?: '20'}"></label>
    <label class="field"><span class="field-label">Min new items</span><input class="field-input mono" name="minItems" value="{form.minItems ?: '3'}"></label>
    <label class="field"><span class="field-label">Keep episodes</span><input class="field-input mono" name="retainEpisodes" value="{form.retainEpisodes ?: '30'}"></label>
    <label class="field md:col-span-2"><span class="field-label">Schedule (cron, e.g. <code class="mono">0 6 * * *</code>)</span><input class="field-input mono" name="cron" value="{form.cron ?: ''}"></label>
    <label class="flex items-center gap-2 self-end text-sm"><input type="checkbox" name="enabled" {#if form.enabled}checked{/if}> Enabled</label>
  </fieldset>
  <div><button type="submit" class="btn btn-primary">{submit}</button></div>
</form>
```

Note: `{#include}` with parameters passes them as data to the included template. `ShowPages/showFields.html` is not a checked template (no method in `Templates`), so its expressions are not type-checked. `ShowForm` is already in `TemplateTypes`.

`templates/ShowPages/live.html`:

```html
{#if live}
<section id="live-run" class="card" aria-live="polite" {#if live.running}hx-get="/admin/shows/{showId}/live" hx-trigger="every 3s" hx-swap="outerHTML"{/if}>
  <div class="flex flex-wrap items-center gap-3">
    {#badge tone=live.tone}{live.status}{/badge}
    <h2 class="m-0 text-base font-semibold">Run #{live.id}</h2>
    <span class="mono text-xs text-muted">{live.trigger} · started {live.started}</span>
    <span class="ml-auto flex gap-2">
      {#if live.episodeId}<a href="/admin/episodes/{live.episodeId}" class="btn btn-secondary btn-sm">Open episode</a>{/if}
      {#if live.status == 'Failed'}<form method="post" action="/admin/runs/{live.id}/retry" hx-post="/admin/runs/{live.id}/retry" hx-target="#live-run" hx-swap="outerHTML"><button type="submit" class="btn btn-accent btn-sm">Retry</button></form>{/if}
    </span>
  </div>
  {#stepper steps=live.steps progress=live.progress /}
  {#if live.error}<p class="m-0 rounded-lg bg-fail-bg px-3 py-2 text-sm text-fail">{live.error}</p>{/if}
</section>
{#else}
<section id="live-run" class="card"><p class="m-0 text-sm text-muted">No runs yet. Press Run now.</p></section>
{/if}
```

`templates/ShowPages/sourceTest.html`:

```html
<div class="flex flex-col gap-2 rounded-xl bg-ground p-3 text-sm">
  {#if result.error}<p role="alert" class="m-0 font-medium text-fail">Error: {result.error}</p>
  {#else}<span class="text-xs font-semibold text-ok">Test OK · {result.items.size} items (last 7 days)</span>{/if}
  <ul class="m-0 flex flex-col gap-1 pl-5">{#for i in result.items}<li><a href="{i.url}" class="link">{i.title}</a>{#if i.discussionUrl} · <a href="{i.discussionUrl}" class="link">discussion</a>{/if}</li>{/for}</ul>
  {#if result.sampleText}<details><summary class="cursor-pointer">Extracted text of the first item</summary><p class="m-0 mt-2 whitespace-pre-wrap">{result.sampleText}</p></details>{/if}
</div>
```

`templates/ShowPages/detail.html`:

```html
{#layout title=show.name active="shows"}
<nav aria-label="Breadcrumb" class="text-[13px] text-muted"><a href="/admin/shows" class="link">Shows</a> / {show.name}</nav>
<header class="flex flex-wrap items-center gap-4">
  <div class="flex flex-col gap-2">
    <h1 class="m-0 text-3xl font-bold">{show.name}</h1>
    <ul class="m-0 flex flex-wrap gap-2 p-0" role="list">{#for c in chips}<li class="chip">{c}</li>{/for}<li><a href="/feeds/{show.slug}.xml" class="chip mono no-underline">/feeds/{show.slug}.xml</a></li></ul>
  </div>
  <div class="ml-auto flex gap-2.5">
    <a href="/admin/prompts" class="btn btn-secondary">Dry-run drafts</a>
    <form method="post" action="/admin/shows/{show.id}/run" hx-post="/admin/shows/{show.id}/run" hx-target="#live-run" hx-swap="outerHTML"><button type="submit" class="btn btn-accent">Run now</button></form>
  </div>
</header>

{#include ShowPages/live showId=show.id live=live /}

<div role="tablist" aria-label="Show sections" class="flex flex-wrap gap-1 border-b border-line">
  <a role="tab" href="?tab=overview" aria-selected="{#if tab == 'overview'}true{#else}false{/if}" class="tab {#if tab == 'overview'}tab-active{/if}">Overview</a>
  <a role="tab" href="?tab=sources" aria-selected="{#if tab == 'sources'}true{#else}false{/if}" class="tab {#if tab == 'sources'}tab-active{/if}">Sources</a>
  <a role="tab" href="?tab=episodes" aria-selected="{#if tab == 'episodes'}true{#else}false{/if}" class="tab {#if tab == 'episodes'}tab-active{/if}">Episodes</a>
  <a role="tab" href="?tab=settings" aria-selected="{#if tab == 'settings'}true{#else}false{/if}" class="tab {#if tab == 'settings'}tab-active{/if}">Settings</a>
  <a role="tab" href="?tab=prompts" aria-selected="{#if tab == 'prompts'}true{#else}false{/if}" class="tab {#if tab == 'prompts'}tab-active{/if}">Prompt overrides</a>
</div>

{#if tab == 'overview'}
<section class="flex flex-wrap items-start gap-5">
  <div class="card grow-[999] basis-[480px] min-w-0">
    <h2 class="m-0 text-base font-semibold">Latest episodes</h2>
    {#for e in episodes}{#if e_index < 3}
    <div class="flex flex-col gap-2 border-t border-line pt-3 first:border-t-0 first:pt-0">
      <a href="/admin/episodes/{e.id}" class="text-sm font-medium text-ink no-underline hover:underline">{e.title}</a>
      {#if e.audioUrl}<audio controls preload="none" src="{e.audioUrl}" class="w-full"></audio>{/if}
      <span class="mono text-xs text-muted">{e.date} · {e.duration}</span>
    </div>
    {/if}{#else}<p class="m-0 text-sm text-muted">No episodes yet.</p>{/for}
  </div>
  <div class="card grow basis-80">
    <h2 class="m-0 text-base font-semibold">Recent runs</h2>
    <ul class="m-0 flex flex-col p-0" role="list">
      {#for r in runs}
      <li class="flex flex-wrap items-center gap-3 border-t border-line py-2.5 first:border-t-0">
        <span class="mono text-xs text-muted">#{r.id}</span>{#stages stages=r.stages label=r.status /}{#badge tone=r.tone}{r.status}{/badge}
        <span class="ml-auto text-xs text-muted">{r.when}</span>
      </li>
      {#else}<li class="text-sm text-muted">No runs yet.</li>{/for}
    </ul>
  </div>
</section>
{/if}

{#if tab == 'sources'}
<section class="flex flex-wrap items-start gap-5">
  <div class="card grow-[999] basis-[560px] min-w-0 overflow-x-auto p-0">
    <table class="w-full min-w-[640px] border-collapse text-sm">
      <thead><tr class="text-left text-xs text-muted"><th class="px-5 py-3.5 font-medium">Source</th><th class="px-2 py-3.5 font-medium">Type</th><th class="px-2 py-3.5 font-medium">Last fetch</th><th class="px-2 py-3.5 font-medium">New (24 h)</th><th class="px-2 py-3.5 font-medium">Status</th><th class="px-5 py-3.5"><span class="sr-only">Actions</span></th></tr></thead>
      <tbody>
        {#for s in sources}
        <tr class="border-t border-line align-top">
          <td class="px-5 py-3"><span class="flex flex-col"><span class="font-medium">{s.name}</span><span class="mono break-all text-[11px] text-muted">{s.detail}</span></span></td>
          <td class="px-2 py-3">{#badge tone=s.typeTone}{s.type}{/badge}</td>
          <td class="px-2 py-3 text-[#3D4957]">{s.fetched}</td>
          <td class="mono px-2 py-3">{s.newToday}</td>
          <td class="px-2 py-3">{#badge tone=s.tone}{s.status}{/badge}</td>
          <td class="px-5 py-3 text-right">
            <span class="inline-flex gap-2">
              <button type="button" class="btn btn-secondary btn-sm" hx-post="/admin/sources/{s.id}/test" hx-target="#source-test" hx-indicator="#source-test-busy">Test</button>
              <form method="post" action="/admin/sources/{s.id}/delete"><button type="submit" class="btn btn-secondary btn-sm">Delete</button></form>
            </span>
          </td>
        </tr>
        {#else}<tr><td colspan="6" class="px-5 py-4 text-muted">No sources yet. Add one on the right.</td></tr>{/for}
      </tbody>
    </table>
    <div class="px-5 pb-5"><span id="source-test-busy" class="htmx-indicator text-xs text-muted">Testing…</span><div id="source-test"></div></div>
  </div>
  <form method="post" action="/admin/shows/{show.id}/sources" class="card grow basis-80" aria-label="Add source">
    <h2 class="m-0 text-base font-semibold">Add source</h2>
    {#if sourceErrors}<div role="alert" class="rounded-xl bg-fail-bg p-3 text-sm text-fail"><ul class="m-0 pl-5">{#for e in sourceErrors}<li>{e}</li>{/for}</ul></div>{/if}
    <label class="field"><span class="field-label">Connector</span>
      <select class="field-input" name="connectorType">{#for c in connectors}<option value="{c}">{c}</option>{/for}</select></label>
    <fieldset class="m-0 flex flex-col gap-3 border-0 p-0"><legend class="mb-1 text-[13px] font-semibold">RSS</legend>
      <label class="field"><span class="field-label">Feed URL</span><input class="field-input mono" name="url" placeholder="https://www.ilpost.it/italia/feed/"></label>
      <label class="flex items-center gap-2 text-sm"><input type="checkbox" name="fetchFullText" checked> Fetch full article text</label>
    </fieldset>
    <fieldset class="m-0 grid grid-cols-2 gap-3 border-0 p-0"><legend class="mb-1 text-[13px] font-semibold">Reddit</legend>
      <label class="field col-span-2"><span class="field-label">Subreddit</span><input class="field-input" name="subreddit" placeholder="italy"></label>
      <label class="field"><span class="field-label">Window</span><select class="field-input" name="window"><option value="day">Last day</option><option value="week">Last week</option><option value="hour">Last hour</option><option value="month">Last month</option></select></label>
      <label class="field"><span class="field-label">Top posts</span><input class="field-input mono" name="maxPosts" value="10"></label>
    </fieldset>
    <details><summary class="cursor-pointer text-[13px] font-medium">Advanced: extra config (key=value per line)</summary>
      <textarea class="field-input mt-2 w-full" name="config" rows="3"></textarea></details>
    <button type="submit" class="btn btn-primary">Add to show</button>
  </form>
</section>
{/if}

{#if tab == 'episodes'}
<section class="card">
  <h2 class="m-0 text-base font-semibold">Episodes</h2>
  <ul class="m-0 flex flex-col p-0" role="list">
    {#for e in episodes}
    <li class="flex flex-wrap items-center gap-3 border-t border-line py-3 first:border-t-0">
      <a href="/admin/episodes/{e.id}" class="grow text-sm font-medium text-ink no-underline hover:underline">{e.title}</a>
      <span class="mono text-xs text-muted">{e.date} · {e.duration}</span>
    </li>
    {#else}<li class="text-sm text-muted">No episodes yet.</li>{/for}
  </ul>
</section>
{/if}

{#if tab == 'settings'}
<section class="card">
  <h2 class="m-0 text-base font-semibold">Settings</h2>
  <p class="m-0 text-sm text-muted">Schedule: {cronText}</p>
  {#include ShowPages/showFields form=form errors=errors models=models voices=voices action=formAction submit="Save" /}
  <form method="post" action="/admin/shows/{show.id}/delete" onsubmit="return confirm('Delete this show, its sources and episodes?')" class="border-t border-line pt-4">
    <button type="submit" class="btn btn-secondary">Delete show</button>
  </form>
</section>
{/if}

{#if tab == 'prompts'}
<form method="post" action="/admin/shows/{show.id}/prompts" class="card">
  <h2 class="m-0 text-base font-semibold">Prompt overrides</h2>
  <p class="m-0 text-sm text-muted">Pin a prompt version for this show only; unpinned prompts use the production label.</p>
  <table class="w-full border-collapse text-sm"><tbody>
    {#for o in overrides}
    <tr class="border-t border-line"><td class="py-2.5"><a href="/admin/prompts/{o.key}" class="link mono">{o.key}</a></td>
      <td class="py-2.5"><select class="field-input" name="{o.key}"><option value="">(production label)</option>{#for v in o.versions}<option value="{v}" {#if v == o.pinned}selected{/if}>v{v}</option>{/for}</select></td></tr>
    {/for}
  </tbody></table>
  <div><button type="submit" class="btn btn-primary">Save overrides</button></div>
</form>
{/if}
{/layout}
```

`{#include ShowPages/live …}` reuses the fragment template, and its `id="live-run"` is the htmx swap target. Add `@TemplateData(target = AdminViews.LiveRun.class)`, `@TemplateData(target = AdminViews.Step.class)` (already added) and `@TemplateData(target = PromptAdminViews.OverrideRow.class)` to `TemplateTypes`, because these are used inside included templates.

- [ ] **Step 5: Delete the old templates and tags**

```bash
git rm -q src/main/resources/templates/AdminResource/shows.html src/main/resources/templates/AdminResource/show.html \
  src/main/resources/templates/AdminResource/runs.html src/main/resources/templates/AdminResource/sourceTest.html \
  src/main/resources/templates/tags/showForm.html src/main/resources/templates/tags/runTable.html
```

Move the `addsSourceAndTriggersRun`-style coverage: the old `AdminTest` show tests are now in `ShowPagesTest` (Task 4 already rewrote `AdminTest`).

- [ ] **Step 6: Run tests**

Run: `./mvnw -q clean test -Dtest='ShowPagesTest,AdminTest,PromptAdminTest,AssetsTest'`
Expected: `ShowPagesTest`, `AdminTest`, `AssetsTest` PASS. In `PromptAdminTest.showOverridesFromForm`, change `admin().get("/admin/shows/" + show.id)` to `admin().get("/admin/shows/" + show.id + "?tab=prompts")` (the overrides moved into a tab). Record a `Ruling:` for this test change.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "Redesign show pages: show cards, tabs, live run card with progress, connector-aware source form

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01LbcoMupq1VXnfzdqroFmyp"
```

---
### Task 6: Episodes list and episode page with chapter player

**Files:**
- Create: `src/main/java/org/roncax/podcaster/admin/EpisodePages.java`, `templates/EpisodePages/{list,detail}.html`
- Modify: `admin/AdminResource.java` (remove `episodePage` and the `episode` template), delete `templates/AdminResource/episode.html`
- Test: `src/test/java/org/roncax/podcaster/admin/EpisodePagesTest.java`

**Interfaces:**
- Consumes: `AdminSupport.chapters/episodeLink/duration/day`, `AdminViews.ChapterView/EpisodeLink`, `VoiceCalibrationService.wordsPerMinute`, tags `layout` (`islands="player"`) and `player`.
- Produces: `GET /admin/episodes[?show=<id>]`, `GET /admin/episodes/{id}`; `record EpisodePages.EpisodeView(long id, String title, long showId, String showName, String date, String duration, String size, long runId, String audioUrl, List<ChapterView> chapters, List<String> madeWith, List<String> scriptParts, String description)`.

- [ ] **Step 1: Write the failing test**

```java
package org.roncax.podcaster.admin;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.domain.*;
import org.roncax.podcaster.support.*;

@QuarkusTest
@WithTestResource(WireMockResource.class)
@WithTestResource(org.roncax.podcaster.support.PostgresResource.class)
class EpisodePagesTest {
    @InjectWireMock WireMockServer wm;

    @BeforeEach
    void setup() {
        TestData.cleanDb();
        WireMockResource.installDefaults(wm);
        FakeChatModelRegistry.install(new FakeChatModel().responder(FakeResponses::pipeline));
    }

    private RequestSpecification admin() {
        return given().cookie("podcaster_key", "test-api-key-0123456789").redirects().follow(false);
    }

    private Episode episode(Show show, String title, List<Chapter> chapters, Outline outline) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Run r = new Run();
            r.showId = show.id; r.trigger = RunTrigger.MANUAL; r.status = RunStatus.DONE; r.stage = RunStage.PUBLISH; r.since = Instant.now();
            r.persist();
            Episode e = new Episode();
            e.runId = r.id; e.showId = show.id; e.title = title; e.audioPath = show.slug + "/" + r.id + ".mp3";
            e.durationSeconds = 90.0; e.sizeBytes = 1_500_000L; e.publishedAt = Instant.now();
            e.chapters = chapters; e.outline = outline; e.scriptParts = List.of("Benvenuti.", "Prima storia.", "Ciao.");
            e.promptVersions = Map.of("rank", 2, "segment", 1);
            e.persist();
            return e;
        });
    }

    @Test
    void listsEpisodesAcrossShowsWithFilter() {
        Show a = TestData.show("ep-a");
        Show b = TestData.show("ep-b");
        episode(a, "Episodio A", null, null);
        episode(b, "Episodio B", null, null);
        admin().get("/admin/episodes").then().statusCode(200).body(containsString("Episodio A")).body(containsString("Episodio B"));
        admin().get("/admin/episodes?show=" + a.id).then().statusCode(200).body(containsString("Episodio A")).body(not(containsString("Episodio B")));
    }

    @Test
    void episodePageShowsChaptersWithSourcesAndRedditThread() {
        Show show = TestData.show("ep-c");
        Source src = TestData.source(show.id, "http://feed");
        Item item = TestData.item(show.id, src.id, "https://news.example/a", Instant.now());
        QuarkusTransaction.requiringNew().run(() -> Item.<Item>findById(item.id).discussionUrl = "https://www.reddit.com/r/italy/comments/x1/y/");
        Episode e = episode(show, "Episodio con capitoli", List.of(
                new Chapter("Intro", 0.0, List.of()),
                new Chapter("Storia A", 12.5, List.of(item.id)),
                new Chapter("Outro", 80.0, List.of())), null);

        admin().get("/admin/episodes/" + e.id).then().statusCode(200)
                .body(containsString("Episodio con capitoli"))
                .body(containsString("Storia A"))
                .body(containsString("data-start=\"12.5\""))
                .body(containsString("0:13"))
                .body(containsString("https://news.example/a"))
                .body(containsString("Discussione su Reddit"))
                .body(containsString("https://www.reddit.com/r/italy/comments/x1/y/"))
                .body(containsString("rank v2"))
                .body(containsString("/static/bundle/player"));
    }

    @Test
    void oldEpisodeWithoutChaptersRenders() {
        Show show = TestData.show("ep-old");
        Episode e = episode(show, "Episodio vecchio", null,
                new Outline(500, List.of(new OutlineSegment("Storia senza tempi", List.of(), 300))));
        String html = admin().get("/admin/episodes/" + e.id).then().statusCode(200).extract().asString();
        assertTrue(html.contains("Storia senza tempi"));
        assertFalse(html.contains("data-start="), "no timestamps for episodes without recorded chapters");
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -q test -Dtest=EpisodePagesTest`
Expected: FAIL (`/admin/episodes` is 404; the old episode page has no chapters).

- [ ] **Step 3: Implement `EpisodePages`**

```java
package org.roncax.podcaster.admin;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.panache.common.Sort;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.*;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestQuery;
import org.roncax.podcaster.admin.AdminViews.*;
import org.roncax.podcaster.domain.*;
import org.roncax.podcaster.tts.VoiceCalibrationService;

@Path("/admin/episodes")
@Produces(MediaType.TEXT_HTML)
public class EpisodePages {

    public record EpisodeView(long id, String title, long showId, String showName, String date, String duration, String size,
                              long runId, String audioUrl, List<ChapterView> chapters, List<String> madeWith,
                              List<String> scriptParts, String description) {}

    @CheckedTemplate
    static class Templates {
        static native TemplateInstance list(List<EpisodeLink> episodes, List<Show> shows, Long selected);
        static native TemplateInstance detail(EpisodeView episode);
    }

    @Inject AdminSupport support;
    @Inject VoiceCalibrationService calibration;

    @GET
    public TemplateInstance list(@RestQuery Long show) {
        List<Show> shows = QuarkusTransaction.requiringNew().call(() -> Show.<Show>listAll(Sort.by("name")));
        Map<Long, String> names = new HashMap<>();
        shows.forEach(s -> names.put(s.id, s.name));
        List<Episode> episodes = QuarkusTransaction.requiringNew().call(() -> show == null
                ? Episode.<Episode>find("publishedAt is not null order by publishedAt desc").page(0, 100).list()
                : Episode.<Episode>find("showId = ?1 and publishedAt is not null order by publishedAt desc", show).page(0, 100).list());
        return Templates.list(episodes.stream().map(e -> support.episodeLink(e, names.getOrDefault(e.showId, "?"))).toList(), shows, show);
    }

    @GET
    @Path("/{id}")
    public TemplateInstance detail(@RestPath long id) {
        Episode e = QuarkusTransaction.requiringNew().call(() -> Episode.<Episode>findByIdOptional(id).orElseThrow(NotFoundException::new));
        Show show = QuarkusTransaction.requiringNew().call(() -> Show.<Show>findById(e.showId));
        List<String> madeWith = new ArrayList<>();
        if (e.promptVersions != null) new TreeMap<>(e.promptVersions).forEach((k, v) -> madeWith.add(k + " v" + v));
        if (show != null) {
            madeWith.add("model " + show.writerModel);
            madeWith.add(show.voiceId);
            madeWith.add(String.format(Locale.ROOT, "%.0f wpm", calibration.wordsPerMinute(show.voiceId, show.lengthScale)));
        }
        String size = e.sizeBytes == null ? "—" : String.format(Locale.ROOT, "%.1f MB", e.sizeBytes / 1_000_000.0);
        EpisodeView view = new EpisodeView(e.id, e.title == null ? "Untitled episode" : e.title, e.showId,
                show == null ? "Deleted show" : show.name, AdminSupport.day(e.publishedAt), AdminSupport.duration(e.durationSeconds), size,
                e.runId, e.audioPath == null ? null : "/media/" + e.audioPath, support.chapters(e), madeWith,
                e.scriptParts == null ? List.of() : e.scriptParts, e.description);
        return Templates.detail(view);
    }
}
```

Remove `episodePage` and the `episode(...)` template declaration from `AdminResource`; `git rm -q src/main/resources/templates/AdminResource/episode.html`.

- [ ] **Step 4: Templates**

`templates/EpisodePages/list.html`:

```html
{#layout title="Episodes" active="episodes"}
<header class="flex flex-wrap items-end gap-4">
  <h1 class="m-0 text-3xl font-bold">Episodes</h1>
  <form method="get" action="/admin/episodes" class="ml-auto flex items-end gap-2">
    <label class="field"><span class="field-label">Show</span>
      <select class="field-input" name="show"><option value="">All shows</option>{#for s in shows}<option value="{s.id}" {#if selected == s.id}selected{/if}>{s.name}</option>{/for}</select></label>
    <button type="submit" class="btn btn-secondary">Filter</button>
  </form>
</header>
<section class="grid gap-4 md:grid-cols-2" aria-label="Episodes">
  {#for e in episodes}
  <article class="card">
    <div class="flex flex-col gap-1">
      <a href="/admin/episodes/{e.id}" class="text-base font-semibold text-ink no-underline hover:underline">{e.title}</a>
      <span class="mono text-xs text-muted">{e.showName} · {e.date} · {e.duration}</span>
    </div>
    {#if e.audioUrl}<audio controls preload="none" src="{e.audioUrl}" class="w-full"></audio>{/if}
  </article>
  {#else}
  {#emptyState title="No episodes yet"}Run a show to produce its first episode.{/emptyState}
  {/for}
</section>
{/layout}
```

`templates/EpisodePages/detail.html`:

```html
{#layout title=episode.title active="episodes" player=true}
<nav aria-label="Breadcrumb" class="text-[13px] text-muted"><a href="/admin/shows/{episode.showId}" class="link">{episode.showName}</a> / <a href="/admin/episodes?show={episode.showId}" class="link">Episodes</a> / {episode.date}</nav>

<section aria-label="Player" class="flex flex-col gap-5 rounded-2xl bg-ink p-6 text-white md:p-7">
  <span class="mono text-xs tracking-wide text-[#F2A98F]">EPISODE · {episode.date} · {episode.duration} · {episode.size} · run #{episode.runId}</span>
  <h1 class="m-0 max-w-4xl text-2xl font-semibold md:text-3xl">{episode.title}</h1>
  {#if episode.audioUrl}
  <div class="rounded-xl bg-white p-3 text-ink">
    {#player src=episode.audioUrl chapters=episode.chapters /}
  </div>
  <a href="{episode.audioUrl}" download class="btn btn-secondary self-start">Download MP3</a>
  {#else}
  <p class="m-0 text-sm text-slate-300">Audio no longer available (removed by retention).</p>
  {/if}
</section>

<div class="flex flex-wrap items-start gap-5">
  <section aria-label="Chapters and sources" class="grow-[999] basis-[520px] min-w-0 flex flex-col gap-3">
    <h2 class="m-0 text-base font-semibold">Chapters &amp; sources</h2>
    {#for c in episode.chapters}
    <article class="card flex-row gap-4">
      {#if c.time}<a href="{episode.audioUrl}#t={c.start}" class="mono h-8 flex-none rounded-lg px-2.5 py-1.5 text-xs text-info no-underline ring-1 ring-line">{c.time}</a>{/if}
      <div class="flex min-w-0 flex-col gap-1.5">
        <span class="font-semibold">{c.title}</span>
        {#for s in c.sources}<a href="{s.url}" class="flex gap-2 text-[13px] no-underline"><span class="text-muted">{s.site}</span><span class="link">{s.title}</span></a>{/for}
      </div>
    </article>
    {#else}
    <p class="m-0 text-sm text-muted">No chapter information for this episode.</p>
    {/for}
  </section>
  <aside class="grow basis-80 flex flex-col gap-4">
    <section class="card"><h2 class="m-0 text-[15px] font-semibold">Made with</h2>
      <ul class="m-0 flex flex-wrap gap-1.5 p-0" role="list">{#for m in episode.madeWith}<li class="mono rounded-md bg-ground px-2 py-1 text-xs text-[#3D4957]">{m}</li>{/for}</ul></section>
    {#if episode.description}<section class="card"><h2 class="m-0 text-[15px] font-semibold">Show notes</h2><p class="m-0 whitespace-pre-wrap text-sm">{episode.description}</p></section>{/if}
    <section class="card"><h2 class="m-0 text-[15px] font-semibold">Script</h2>
      {#for p in episode.scriptParts}<p class="m-0 text-[15px] leading-relaxed text-[#2B3746]">{p}</p>{/for}</section>
  </aside>
</div>
{/layout}
```

Add `@TemplateData(target = EpisodePages.EpisodeView.class)` to `TemplateTypes`.

- [ ] **Step 5: Run tests**

Run: `./mvnw -q clean test -Dtest='EpisodePagesTest,AdminTest,ShowPagesTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Add episodes list and episode page with chapter player and per-story sources

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01LbcoMupq1VXnfzdqroFmyp"
```

---

### Task 7: Prompt pages with CodeMirror editor and side-by-side diff

**Files:**
- Create: `src/main/java/org/roncax/podcaster/admin/PromptPages.java`, `templates/PromptPages/{list,detail,dryRun}.html`
- Modify: `admin/AdminResource.java` (remove the prompt endpoints, templates and helpers; it keeps only login, logout, dashboard, settings), delete `templates/AdminResource/{prompts,prompt,dryRun}.html`
- Test: `src/test/java/org/roncax/podcaster/admin/PromptAdminTest.java` (add `formWorksWithoutIsland`)

**Interfaces:**
- Consumes: `PromptRegistry`, `PromptDryRun`, `LineDiff`, `PromptAdminViews.{PromptRow, VersionRow, DiffRow}`, tags `layout` (`islands="editor"`).
- Produces (same URLs as before): `GET /admin/prompts`, `GET /admin/prompts/{key}?v=n`, `POST /admin/prompts/{key}/versions`, `POST /admin/prompts/{key}/labels/{label}`, `POST /admin/prompts/dry-run` (fragment).

- [ ] **Step 1: Write the failing test** (add to `PromptAdminTest`)

```java
    @Test
    void formWorksWithoutIsland() {
        String html = admin().get("/admin/prompts/json_repair").then().statusCode(200).extract().asString();
        assertTrue(html.contains("data-prompt-editor"), "editor island hook");
        assertTrue(html.contains("name=\"body\""), "plain textarea is the submitted field");
        assertTrue(html.contains("data-prompt-diff"), "diff island hook");
        assertTrue(html.contains("/static/bundle/editor"), "editor bundle loaded");
        assertTrue(html.contains("data-insert-var=\"{error}\""), "variable chips");
        admin().formParam("body", REPAIR_V2).formParam("note", "plain form").post("/admin/prompts/json_repair/versions")
                .then().statusCode(303);
        assertEquals(2, registry.labels(PromptKey.JSON_REPAIR).get(PromptLabel.DRAFT));
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -q test -Dtest=PromptAdminTest#formWorksWithoutIsland`
Expected: FAIL (no `data-prompt-editor` in the current markup).

- [ ] **Step 3: Implement `PromptPages`** (move from `AdminResource`)

```java
package org.roncax.podcaster.admin;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.panache.common.Sort;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.*;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestQuery;
import org.roncax.podcaster.admin.PromptAdminViews.*;
import org.roncax.podcaster.domain.Show;
import org.roncax.podcaster.prompts.*;

@Path("/admin/prompts")
@Produces(MediaType.TEXT_HTML)
public class PromptPages {

    @CheckedTemplate
    static class Templates {
        static native TemplateInstance list(List<PromptRow> rows);
        static native TemplateInstance detail(String key, String description, Integer production, Integer draft,
                                              List<VersionRow> versions, int shownVersion, String shownBody, String productionBody,
                                              List<DiffRow> diff, String editorBody, String note, List<String> errors,
                                              List<Show> shows, List<String> variables);
        static native TemplateInstance dryRun(PromptDryRun.Result result, String error);
    }

    @Inject PromptRegistry prompts;
    @Inject PromptDryRun dryRun;

    @GET
    public TemplateInstance list() {
        List<PromptRow> rows = new ArrayList<>();
        for (PromptKey key : PromptKey.values()) {
            Map<PromptLabel, Integer> labels = prompts.labels(key);
            rows.add(new PromptRow(key.dbKey(), key.description(), labels.get(PromptLabel.PRODUCTION), labels.get(PromptLabel.DRAFT)));
        }
        return Templates.list(rows);
    }

    @GET
    @Path("/{key}")
    public TemplateInstance detail(@RestPath String key, @RestQuery Integer v) {
        PromptKey k = key(key);
        Map<PromptLabel, Integer> labels = prompts.labels(k);
        int shown = v != null ? v : labels.get(PromptLabel.DRAFT);
        String draftBody = prompts.version(k, labels.get(PromptLabel.DRAFT)).orElseThrow().body;
        return template(k, shown, draftBody, null, List.of());
    }

    @POST
    @Path("/{key}/versions")
    public Response create(@RestPath String key, @RestForm String body, @RestForm String note) {
        PromptKey k = key(key);
        try {
            PromptVersion created = prompts.createVersion(k, body, note);
            return Response.seeOther(URI.create("/admin/prompts/" + k.dbKey() + "?v=" + created.version)).build();
        } catch (InvalidPromptException e) {
            int shown = prompts.labels(k).get(PromptLabel.DRAFT);
            return Response.ok(template(k, shown, body, note, e.errors())).build();
        }
    }

    @POST
    @Path("/{key}/labels/{label}")
    public Response setLabel(@RestPath String key, @RestPath String label, @RestForm int version) {
        PromptKey k = key(key);
        PromptLabel l = PromptLabel.fromDb(label).orElseThrow(NotFoundException::new);
        prompts.setLabel(k, l, version);
        return Response.seeOther(URI.create("/admin/prompts/" + k.dbKey() + "?v=" + version)).build();
    }

    @POST
    @Path("/dry-run")
    public TemplateInstance dryRun(@RestForm long showId) {
        try {
            return Templates.dryRun(dryRun.run(showId), null);
        } catch (RuntimeException e) {
            return Templates.dryRun(null, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    private TemplateInstance template(PromptKey k, int shown, String editorBody, String note, List<String> errors) {
        Map<PromptLabel, Integer> labels = prompts.labels(k);
        Integer production = labels.get(PromptLabel.PRODUCTION);
        String shownBody = prompts.version(k, shown).orElseThrow(NotFoundException::new).body;
        String productionBody = prompts.version(k, production).orElseThrow().body;
        List<DiffRow> diff = LineDiff.diff(productionBody, shownBody).stream()
                .map(l -> new DiffRow(l.op() == '+' ? "diff-add" : l.op() == '-' ? "diff-del" : "diff-same", String.valueOf(l.op()), l.text()))
                .toList();
        List<VersionRow> versions = prompts.versions(k).stream()
                .map(ver -> new VersionRow(ver.version, ver.note == null ? "" : ver.note, AdminSupport.when(ver.createdAt),
                        String.join(", ", prompts.labelsOf(k, ver.version))))
                .toList();
        List<String> variables = new TreeSet<>(k.variables()).stream().filter(n -> !n.equals("contract") || k.contract() != null)
                .map(n -> "{" + n + "}").toList();
        List<Show> shows = QuarkusTransaction.requiringNew().call(() -> Show.<Show>listAll(Sort.by("name")));
        return Templates.detail(k.dbKey(), k.description(), production, labels.get(PromptLabel.DRAFT), versions, shown,
                shownBody, productionBody, diff, editorBody, note, errors, shows, variables);
    }

    private static PromptKey key(String key) {
        return PromptKey.fromDb(key).orElseThrow(NotFoundException::new);
    }
}
```

Remove from `AdminResource` everything prompt-related (`promptsPage`, `promptPage`, `createPromptVersion`, `setPromptLabel`, `dryRunFragment`, `promptTemplate`, `promptKey`, the `prompts`, `prompt`, `dryRun` template declarations and unused injections). `git rm -q src/main/resources/templates/AdminResource/prompts.html src/main/resources/templates/AdminResource/prompt.html src/main/resources/templates/AdminResource/dryRun.html`.

- [ ] **Step 4: Templates**

`templates/PromptPages/list.html`:

```html
{#layout title="Prompts" active="prompts"}
<h1 class="m-0 text-3xl font-bold">Prompts</h1>
<p class="m-0 text-sm text-muted">Every prompt sent to the LLM. Versions are immutable: <strong>production</strong> is used by runs, <strong>draft</strong> by dry-runs.</p>
<section class="grid gap-4 md:grid-cols-2" aria-label="Prompts">
  {#for r in rows}
  <a href="/admin/prompts/{r.key}" class="card no-underline text-ink hover:ring-accent">
    <span class="mono text-base font-semibold">{r.key}</span>
    <span class="text-sm text-muted">{r.description}</span>
    <span class="flex gap-2">{#badge tone='ok'}production v{r.production}{/badge}{#badge tone='info'}draft v{r.draft}{/badge}</span>
  </a>
  {/for}
</section>
{/layout}
```

`templates/PromptPages/detail.html`:

```html
{#layout title=key active="prompts" editor=true}
<nav aria-label="Breadcrumb" class="text-[13px] text-muted"><a href="/admin/prompts" class="link">Prompts</a> / {key}</nav>
<header class="flex flex-wrap items-center gap-3">
  <div class="flex flex-col gap-1">
    <h1 class="mono m-0 text-2xl font-semibold">{key}</h1>
    <span class="text-sm text-muted">{description} · production v{production} · draft v{draft}</span>
  </div>
</header>

<section aria-label="Versions" class="flex flex-col gap-3">
  <h2 class="m-0 text-base font-semibold">Versions</h2>
  <ol class="m-0 flex flex-wrap gap-2.5 p-0" role="list">
    {#for v in versions}
    <li class="flex min-w-44 flex-col gap-1 rounded-xl bg-panel p-3 ring-1 {#if v.version == shownVersion}ring-2 ring-run-dot{#else}ring-line{/if}">
      <a href="/admin/prompts/{key}?v={v.version}" class="mono font-semibold text-ink no-underline hover:underline">v{v.version}</a>
      <span class="text-xs text-muted">{v.note}</span>
      <span class="text-[11px] font-semibold uppercase text-ok">{v.labels}</span>
      <span class="flex flex-wrap gap-1.5">
        <form method="post" action="/admin/prompts/{key}/labels/production"><input type="hidden" name="version" value="{v.version}"><button type="submit" class="btn btn-secondary btn-sm">Promote</button></form>
        <form method="post" action="/admin/prompts/{key}/labels/draft"><input type="hidden" name="version" value="{v.version}"><button type="submit" class="btn btn-secondary btn-sm">Set draft</button></form>
      </span>
    </li>
    {/for}
  </ol>
</section>

<section class="card p-0 overflow-hidden" aria-label="Changes">
  <h2 class="m-0 px-5 pt-4 text-base font-semibold">v{shownVersion} compared with production (v{production})</h2>
  <div data-prompt-diff class="min-h-24">
    <template data-side="a">{productionBody}</template>
    <template data-side="b">{shownBody}</template>
    <div class="mono overflow-x-auto py-3 text-[12.5px] leading-relaxed">{#for d in diff}<div class="{d.css} whitespace-pre px-5">{d.prefix} {d.text}</div>{/for}</div>
  </div>
</section>

<section class="card" aria-label="Dry-run">
  <h2 class="m-0 text-base font-semibold">Dry-run the draft</h2>
  <form method="post" action="/admin/prompts/dry-run" hx-post="/admin/prompts/dry-run" hx-target="#dry-run" hx-indicator="#dry-run-busy" class="flex flex-wrap items-end gap-2">
    <label class="field"><span class="field-label">Show</span><select class="field-input" name="showId">{#for s in shows}<option value="{s.id}">{s.name}</option>{/for}</select></label>
    <button type="submit" class="btn btn-secondary">Dry-run</button>
    <span id="dry-run-busy" class="htmx-indicator text-xs text-muted">Running… (about a minute)</span>
  </form>
  <div id="dry-run"></div>
</section>

<form method="post" action="/admin/prompts/{key}/versions" class="card" aria-label="New version">
  <div class="flex flex-wrap items-center gap-2">
    <h2 class="m-0 text-base font-semibold">New version</h2>
    <span class="ml-2 text-xs text-muted">Insert variable:</span>
    {#for v in variables}<button type="button" data-insert-var="{v}" class="mono h-7 rounded-md bg-ground px-2 text-xs text-info ring-1 ring-line">{v}</button>{/for}
  </div>
  {#if errors}<div role="alert" class="rounded-xl bg-fail-bg p-3 text-sm text-fail"><ul class="m-0 pl-5">{#for e in errors}<li>{e}</li>{/for}</ul></div>{/if}
  <label class="field"><span class="field-label">Template</span><textarea class="field-input" name="body" rows="18" data-prompt-editor>{editorBody}</textarea></label>
  <div class="flex flex-wrap items-end gap-2.5">
    <label class="field grow basis-64"><span class="field-label">Change note</span><input class="field-input" name="note" value="{note ?: ''}"></label>
    <button type="submit" class="btn btn-primary">Save as new draft version</button>
  </div>
</form>
{/layout}
```

`templates/PromptPages/dryRun.html`:

```html
<div class="mt-2 flex flex-col gap-2 rounded-xl bg-ground p-4 text-sm">
{#if error}
  <p role="alert" class="m-0 font-medium text-fail">{error}</p>
{#else}
  <p class="m-0"><strong>{result.title}</strong> <span class="mono text-xs text-muted">· prompt versions {result.promptVersions}</span></p>
  <p class="m-0 whitespace-pre-wrap">{result.description}</p>
  {#for part in result.scriptParts}<p class="m-0 leading-relaxed">{part}</p>{/for}
{/if}
</div>
```

- [ ] **Step 5: Run tests**

Run: `./mvnw -q clean test -Dtest='PromptAdminTest,AdminTest,AssetsTest'`
Expected: PASS (existing prompt admin tests keep their routes and assertions: `diff-add`, `shorter`, `Missing required variable {error}`, `No unused items`).

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Redesign prompt pages with CodeMirror editor, variable chips and side-by-side diff

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01LbcoMupq1VXnfzdqroFmyp"
```

---

### Task 8: Cleanup, docs and visual check

**Files:**
- Delete: `src/main/resources/templates/base.html` (no longer included anywhere)
- Modify: `admin/AdminResource.java` (remove now-unused injections/imports), `admin/TemplateTypes.java` (drop entries for removed types if any), `README.md` (admin UI section), `docs/BACKLOG.md` (mark per-story links done)

**Interfaces:** none new.

- [ ] **Step 1: Remove leftovers and verify nothing references them**

```bash
git rm -q src/main/resources/templates/base.html
grep -rn "include base\|pico\|unpkg\|jsdelivr\|fonts.googleapis" src/main/resources/templates src/main/java || echo "clean"
```

Expected: `clean`.

- [ ] **Step 2: README and backlog**

Replace the README's "API" lead-in area with an "Admin UI" section placed before "## Database UI":

```markdown
## Admin UI

`http://<server>:8080/admin` (log in with `PODCASTER_API_KEY`): dashboard of shows and runs, live run progress, sources per show (RSS, Reddit), episodes with a chapter player and per-story sources, prompt versions with diff, editor and dry-run, and read-only settings. Assets (Tailwind, fonts, htmx, CodeMirror) are bundled by the Quarkus Web Bundler at build time: no Node and no CDN, so it works offline on your LAN.
```

In `docs/BACKLOG.md`, remove the "Per-story source links in show notes" section and add to the "Done:" line: `per-story source links in show notes (admin UI redesign, 2026-10-07)`.

- [ ] **Step 3: Full suite**

Run: `./mvnw -q clean test`
Expected: PASS (all tests).

- [ ] **Step 4: Rebuild the container and check every page renders**

```bash
docker compose up -d --build podcaster
for i in $(seq 1 60); do curl -fs localhost:8080/q/health >/dev/null && break; sleep 3; done
KEY=$(grep ^PODCASTER_API_KEY .env | cut -d= -f2)
for p in /admin /admin/shows /admin/shows/1 "/admin/shows/1?tab=sources" /admin/episodes /admin/episodes/1 /admin/prompts /admin/prompts/segment /admin/settings; do
  printf "%-32s %s\n" "$p" "$(curl -s -o /dev/null -w '%{http_code}' -b "podcaster_key=$KEY" "localhost:8080$p")"
done
```

Expected: every page `200`. Then take screenshots of the dashboard, a show (sources tab), an episode and the segment prompt page with the browser tools available in the session (login first), save them under `target/ui-screens/`, and show them to the user. If a page looks broken (missing styles, overlapping layout), fix it before committing.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Remove legacy admin templates; document the admin UI

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01LbcoMupq1VXnfzdqroFmyp"
```
