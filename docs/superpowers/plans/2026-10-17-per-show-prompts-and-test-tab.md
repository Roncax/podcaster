# Per-show Prompts & Test Tab Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Each show owns its prompts, forked from global default prompts. A new **Test** tab on each show dry-runs source retrieval → prompts → voice without writing to the database.

**Architecture:**
- The prompt registry gains a nullable `show_id` scope: `null` means a default prompt, a set value means that show's copy, with its own `production`/`draft` labels. Pins are removed.
- A new `org.roncax.podcaster.testing` package keeps an in-memory session per show and runs each step asynchronously on its own executor.
- Each step reuses the production code paths: `IngestionService.collect`, `StoryRanker`/`OutlinePlanner`/`ScriptWriter`, and `ScriptChunker`/`TtsEngine`/`WavConcat`. The admin tab polls with htmx.

**Tech Stack:** Java 25, Quarkus (RESTEasy Reactive, Hibernate Panache, Qute, Flyway, Scheduler), PostgreSQL 17 (embedded zonky in tests), htmx, RestAssured, WireMock.

**Spec:** `docs/superpowers/specs/2026-10-17-per-show-prompts-and-test-tab-design.md`

## Global Constraints

- Branch `feature/per-show-prompts`. Every commit message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Prompt versions are insert-only. Never add an update or delete path for `prompt_versions`.
- Labels exist only for show prompts (`show_id` not null). Defaults have no labels.
- Migration notes: `"Forked from default v<n>"` for the fork; `"Reset to default v<n>"` for a reset.
- Test steps never write `Item`, `Episode`, `Run` or `VoiceCalibration` rows, never update `Source.lastFetchedAt`/`lastError`, and never call `Notifier`.
- The voice test text limit is exactly 1,000 chars. The length scale bounds are 0.5–2.0 (same as `ShowRequest`).
- Test sessions expire after 30 minutes idle. Only one test step runs per show at a time (409 otherwise).
- Synthetic ids for fetched test items are 1, 2, 3, … (positive, because they appear in the rank prompt).
- Run the full suite with `./mvnw -q test` before each commit. All tests must pass.
- Match the surrounding code style: Panache public fields, `QuarkusTransaction.requiringNew()`, records for views, and comments only where non-obvious.

## Review Focus

1. **A show with no prompt rows** (created by a path other than `ShowService.create`, or a future new `PromptKey`): it must get forked at startup instead of failing runs with "No production version". Pinned by `PromptSeederTest.backfillsShowsMissingPrompts` (Task 1).
2. **Default v1 page** (no previous version to diff against): it must render, showing a diff against itself, not crash. Pinned by `PromptAdminTest.firstDefaultVersionRenders` (Task 1).
3. **Starting a step while another runs** (e.g. a prompt dry-run during a source fetch): this must return 409, not run on half-built session data. Pinned by `TestApiTest.concurrentStepIs409` (Task 4).
4. **Synthetic ids in the rank prompt:** the LLM must see `[id=1]`-style positive ids, and the selection must map back to the fetched items. Pinned by `TestApiTest.promptDryRunOverFetchedItems` (Task 5).
5. **Voice text that is blank or only whitespace:** it must return 400 without calling Piper. Pinned by `VoiceTestApiTest.blankTextIs400` (Task 6).

---

## File Structure

| File | Responsibility |
|---|---|
| `src/main/resources/db/migration/V6__per_show_prompts.sql` | Scope columns, fork of existing shows, drop pins, lineage flag |
| `prompts/PromptVersion.java`, `PromptLabelAssignment.java` | `showId` field and scoped query helpers |
| `prompts/PromptRegistry.java` | Show-scoped versioning, labels, reset, fork; defaults |
| `prompts/PromptResolver.java` | Resolves a show's labels |
| `prompts/PromptSeeder.java` | Seeds defaults and backfills shows missing prompts |
| `prompts/ShowPromptOverride.java` | **Deleted** |
| `api/PromptResource.java` | Default prompts REST |
| `api/ShowPromptResource.java` | Per-show prompts REST |
| `admin/PromptPages.java` + `templates/PromptPages/detail.html` | Shared prompt detail page (view record), default routes |
| `admin/PromptAdminViews.java` | `PromptPage`, `Crumb`, `ShowPromptRow` view records |
| `admin/ShowPromptPages.java` | Show prompt detail routes |
| `ingestion/IngestionService.java`, `ingestion/CollectedItem.java` | `collect()` without persistence |
| `testing/TestSession.java` | Per-show session state |
| `testing/TestSessionStore.java` | Session map plus expiry sweep |
| `testing/TestRunner.java` | Async step execution, one at a time per show |
| `testing/TestStepBusyException.java` | 409 |
| `testing/SourceDryRun.java` | Fetches sources in memory |
| `testing/PromptDryRun.java` | Moved from `prompts`, with mode and item source |
| `testing/VoiceDryRun.java` | Short TTS clip |
| `tts/WavConcat.java` | PCM concatenation extracted from `AudioAssembler` |
| `api/TestResource.java`, `api/TestViews.java` | Test REST |
| `admin/TestPages.java`, `templates/TestPages/panel.html` | Test tab UI |

(Paths are under `src/main/java/org/roncax/podcaster/` unless they start with `src/`.)

---

### Task 1: Per-show prompt model (migration, registry, REST, default prompt pages)

This task is large because removing pins and global labels changes every caller at once, and each commit must compile with a green suite.

**Files:**
- Create: `src/main/resources/db/migration/V6__per_show_prompts.sql`
- Create: `src/test/java/org/roncax/podcaster/prompts/PerShowPromptsMigrationTest.java`
- Modify: `prompts/PromptVersion.java`, `prompts/PromptLabelAssignment.java`, `prompts/PromptRegistry.java`, `prompts/PromptResolver.java`, `prompts/PromptSeeder.java`, `prompts/PromptDryRun.java` (no signature change; still compiles)
- Delete: `prompts/ShowPromptOverride.java`
- Modify: `domain/Episode.java` (add `promptVersionsGlobal`)
- Modify: `api/ShowService.java` (fork on create), `api/PromptResource.java`, `api/ShowPromptResource.java`, `api/PromptViews.java`
- Modify: `admin/PromptPages.java`, `admin/PromptAdminViews.java`, `admin/ShowPages.java`, `templates/PromptPages/list.html`, `templates/PromptPages/detail.html`, `templates/ShowPages/detail.html`, `templates/tags/layout.html` (nav label)
- Modify tests: `support/TestData.java`, `prompts/PromptRegistryTest.java`, `prompts/PromptSeederTest.java`, `prompts/PromptPipelineTest.java`, `api/PromptApiTest.java`, `admin/PromptAdminTest.java`, `admin/ShowPagesTest.java`

**Interfaces:**
- Produces (used by Tasks 2, 5, 7):
  - `PromptRegistry.createVersion(long showId, PromptKey key, String body, String note) → PromptVersion`
  - `setLabel(long showId, PromptKey, PromptLabel, int version)`
  - `resetToDefault(long showId, PromptKey) → PromptVersion`
  - `fork(long showId)`: `@Transactional(MANDATORY)`
  - `versions(long showId, PromptKey) → List<PromptVersion>` (newest first)
  - `version(long showId, PromptKey, int) → Optional<PromptVersion>`
  - `labels(long showId, PromptKey) → Map<PromptLabel,Integer>`
  - `labelsOf(long showId, PromptKey, int) → List<String>`
  - `createDefaultVersion(PromptKey, String body, String note) → PromptVersion`
  - `defaultVersions(PromptKey)`, `defaultVersion(PromptKey, int)`, `latestDefault(PromptKey) → PromptVersion`
  - `PromptResolver.resolve(long showId, Mode)`
  - `PromptAdminViews.PromptPage` and `Crumb` records; the `PromptPages.Templates.detail(PromptPage page)` template
  - static helpers `PromptPages.diffRows(String base, String shown)` and `PromptPages.variables(PromptKey)`

- [ ] **Step 1: Write the failing migration test**

`src/test/java/org/roncax/podcaster/prompts/PerShowPromptsMigrationTest.java`. This is plain JUnit, not `@QuarkusTest`. It runs Flyway on its own embedded Postgres so it can insert pre-V6 data.

```java
package org.roncax.podcaster.prompts;

import static org.junit.jupiter.api.Assertions.*;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class PerShowPromptsMigrationTest {

    @Test
    void forksShowsFromPinOrProductionAndDropsPins() throws Exception {
        try (EmbeddedPostgres pg = EmbeddedPostgres.builder().start()) {
            DataSource ds = pg.getPostgresDatabase();
            Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("5").load().migrate();
            try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
                st.execute("insert into prompts(key, description) values ('rank', 'r')");
                st.execute("insert into prompt_versions(prompt_key, version, body) values ('rank',1,'B1'),('rank',2,'B2'),('rank',3,'B3')");
                st.execute("insert into prompt_labels(prompt_key, label, version_id) select 'rank','production',id from prompt_versions where version = 2");
                st.execute("insert into prompt_labels(prompt_key, label, version_id) select 'rank','draft',id from prompt_versions where version = 3");
                st.execute("insert into shows(name, slug, language, voice_id, writer_model, feed_token) values"
                        + " ('A','a','it','v','m','tok-a'), ('B','b','it','v','m','tok-b')");
                st.execute("insert into show_prompt_overrides(show_id, prompt_key, version_id)"
                        + " select s.id, 'rank', v.id from shows s, prompt_versions v where s.slug = 'b' and v.version = 3");
                st.execute("insert into runs(show_id, trigger, stage, status, since) select id,'MANUAL','DONE','DONE',now() from shows where slug='a'");
                st.execute("insert into episodes(run_id, show_id, prompt_versions) select r.id, r.show_id, '{\"rank\":2}' from runs r");
            }

            Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();

            try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
                assertEquals("B2|Forked from default v2", one(st, "select v.body || '|' || v.note from prompt_versions v join shows s on s.id = v.show_id where s.slug = 'a' and v.version = 1"));
                assertEquals("B3|Forked from default v3", one(st, "select v.body || '|' || v.note from prompt_versions v join shows s on s.id = v.show_id where s.slug = 'b' and v.version = 1"));
                assertEquals("3", one(st, "select count(*) from prompt_versions where show_id is null"));
                assertEquals("4", one(st, "select count(*) from prompt_labels l join prompt_versions v on v.id = l.version_id where v.show_id = l.show_id and v.version = 1"));
                assertEquals("0", one(st, "select count(*) from prompt_labels where show_id is null"));
                assertNull(one(st, "select to_regclass('public.show_prompt_overrides')::text"));
                assertEquals("true", one(st, "select prompt_versions_global::text from episodes"));
            }
        }
    }

    private static String one(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next(), sql);
            return rs.getString(1);
        }
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw -q test -Dtest=PerShowPromptsMigrationTest`
Expected: FAIL. The `prompt_versions.show_id` column does not exist yet, so the first assertion query errors.

- [ ] **Step 3: Write the migration**

`src/main/resources/db/migration/V6__per_show_prompts.sql`:

```sql
-- Per-show prompts: show_id null = default prompt, otherwise that show's own copy.
alter table prompt_versions add column show_id bigint references shows(id) on delete cascade;
alter table prompt_versions drop constraint prompt_versions_prompt_key_version_key;
alter table prompt_versions add constraint prompt_versions_key_show_version_key
    unique nulls not distinct (prompt_key, show_id, version);

-- Fork every existing show from its pinned version, else from the production label.
insert into prompt_versions (prompt_key, show_id, version, body, note, created_at)
select p.key, s.id, 1, src.body, 'Forked from default v' || src.version, now()
from shows s
cross join prompts p
cross join lateral (
    select v.body, v.version from prompt_versions v
    where v.id = coalesce(
        (select o.version_id from show_prompt_overrides o where o.show_id = s.id and o.prompt_key = p.key),
        (select l.version_id from prompt_labels l where l.prompt_key = p.key and l.label = 'production'))
) src;

-- Labels now belong to shows only.
delete from prompt_labels;
alter table prompt_labels add column show_id bigint not null references shows(id) on delete cascade;
alter table prompt_labels drop constraint prompt_labels_prompt_key_label_key;
alter table prompt_labels add constraint prompt_labels_show_key_label_key unique (show_id, prompt_key, label);

insert into prompt_labels (prompt_key, label, version_id, show_id)
select v.prompt_key, l.label, v.id, v.show_id
from prompt_versions v
cross join (values ('production'), ('draft')) as l(label)
where v.show_id is not null;

drop table show_prompt_overrides;

-- Lineage of older episodes refers to the global registry.
alter table episodes add column prompt_versions_global boolean not null default false;
update episodes set prompt_versions_global = true;
```

If a `drop constraint` fails because Postgres named it differently, look up the real name with `\d prompt_versions` on a V5 database and use that name. Postgres names inline `unique (a, b)` constraints `<table>_<a>_<b>_key`.

- [ ] **Step 4: Run the migration test to verify it passes**

Run: `./mvnw -q test -Dtest=PerShowPromptsMigrationTest`
Expected: PASS

- [ ] **Step 5: Update entities**

In `PromptVersion.java`, add the field and replace `find(PromptKey, int)` with scoped helpers:

```java
    public Long showId;
    ...
    /** {@code showId} null = default prompts. */
    public static Optional<PromptVersion> find(Long showId, PromptKey key, int version) {
        return showId == null
                ? find("showId is null and promptKey = ?1 and version = ?2", key.dbKey(), version).firstResultOptional()
                : find("showId = ?1 and promptKey = ?2 and version = ?3", showId, key.dbKey(), version).firstResultOptional();
    }

    /** Newest first. */
    public static List<PromptVersion> history(Long showId, PromptKey key) {
        return showId == null
                ? list("showId is null and promptKey = ?1 order by version desc", key.dbKey())
                : list("showId = ?1 and promptKey = ?2 order by version desc", showId, key.dbKey());
    }
```

In `PromptLabelAssignment.java`, add `public Long showId;`.

In `Episode.java`, add `public boolean promptVersionsGlobal;` next to `promptVersions`.

Delete `prompts/ShowPromptOverride.java`.

- [ ] **Step 6: Rewrite the registry tests (failing)**

Replace the body of `PromptRegistryTest` (keep its annotations, `REPAIR_V2` and `repair(...)`). `TestData.show` will fork prompts (Step 9).

```java
    @Inject PromptRegistry registry;
    @Inject PromptResolver resolver;

    @BeforeEach
    void clean() { TestData.cleanDb(); }

    @Test
    void newShowGetsVersionOneOfEveryKey() {
        Show show = TestData.show("fresh");
        for (PromptKey key : PromptKey.values()) {
            assertEquals(Map.of(PromptLabel.PRODUCTION, 1, PromptLabel.DRAFT, 1), registry.labels(show.id, key), key.dbKey());
            assertEquals(key.seedBody(), registry.version(show.id, key, 1).orElseThrow().body);
            assertEquals("Forked from default v1", registry.version(show.id, key, 1).orElseThrow().note);
        }
    }

    @Test
    void createVersionLandsAsDraftOnlyForThatShow() {
        Show a = TestData.show("a");
        Show b = TestData.show("b");
        PromptVersion v2 = registry.createVersion(a.id, PromptKey.JSON_REPAIR, REPAIR_V2, "shorter");
        assertEquals(2, v2.version);
        assertEquals(Map.of(PromptLabel.PRODUCTION, 1, PromptLabel.DRAFT, 2), registry.labels(a.id, PromptKey.JSON_REPAIR));
        assertEquals("JSON broken (E). Send only the object.", repair(resolver.resolve(a.id, PromptResolver.Mode.DRAFT)));
        assertTrue(repair(resolver.resolve(a.id, PromptResolver.Mode.PRODUCTION)).startsWith("Your previous reply"));
        assertTrue(repair(resolver.resolve(b.id, PromptResolver.Mode.DRAFT)).startsWith("Your previous reply"));
        assertEquals(1, registry.versions(b.id, PromptKey.JSON_REPAIR).size());
    }

    @Test
    void versionsAreNumberedPerShow() {
        Show a = TestData.show("a");
        Show b = TestData.show("b");
        registry.createVersion(a.id, PromptKey.JSON_REPAIR, REPAIR_V2, null);
        assertEquals(2, registry.createVersion(b.id, PromptKey.JSON_REPAIR, REPAIR_V2, null).version);
    }

    @Test
    void invalidBodyIsRejectedAndNothingStored() {
        Show show = TestData.show("bad");
        InvalidPromptException ex = assertThrows(InvalidPromptException.class,
                () -> registry.createVersion(show.id, PromptKey.RANK, "no variables here", null));
        assertTrue(ex.errors().contains("Missing required variable {items}"));
        assertEquals(1, registry.versions(show.id, PromptKey.RANK).size());
    }

    @Test
    void promoteAndRollBack() {
        Show show = TestData.show("promo");
        registry.createVersion(show.id, PromptKey.JSON_REPAIR, REPAIR_V2, null);
        registry.setLabel(show.id, PromptKey.JSON_REPAIR, PromptLabel.PRODUCTION, 2);
        assertTrue(repair(resolver.resolve(show.id, PromptResolver.Mode.PRODUCTION)).startsWith("JSON broken"));
        registry.setLabel(show.id, PromptKey.JSON_REPAIR, PromptLabel.PRODUCTION, 1);
        assertTrue(repair(resolver.resolve(show.id, PromptResolver.Mode.PRODUCTION)).startsWith("Your previous reply"));
        assertEquals(java.util.List.of("draft"), registry.labelsOf(show.id, PromptKey.JSON_REPAIR, 2));
    }

    @Test
    void labelCannotPointAtAnotherShowsVersion() {
        Show a = TestData.show("a");
        Show b = TestData.show("b");
        registry.createVersion(a.id, PromptKey.JSON_REPAIR, REPAIR_V2, null);
        assertThrows(InvalidPromptException.class, () -> registry.setLabel(b.id, PromptKey.JSON_REPAIR, PromptLabel.PRODUCTION, 2));
    }

    @Test
    void unknownShowIs404() {
        assertThrows(jakarta.ws.rs.NotFoundException.class,
                () -> registry.createVersion(999_999L, PromptKey.JSON_REPAIR, REPAIR_V2, null));
    }

    @Test
    void resetToDefaultCreatesDraftFromLatestDefault() {
        Show show = TestData.show("reset");
        registry.createVersion(show.id, PromptKey.JSON_REPAIR, REPAIR_V2, null);
        registry.setLabel(show.id, PromptKey.JSON_REPAIR, PromptLabel.PRODUCTION, 2);
        registry.createDefaultVersion(PromptKey.JSON_REPAIR, REPAIR_V2 + " Default two.", "d2");

        PromptVersion reset = registry.resetToDefault(show.id, PromptKey.JSON_REPAIR);

        assertEquals(3, reset.version);
        assertEquals(REPAIR_V2 + " Default two.", reset.body);
        assertEquals("Reset to default v2", reset.note);
        assertEquals(Map.of(PromptLabel.PRODUCTION, 2, PromptLabel.DRAFT, 3), registry.labels(show.id, PromptKey.JSON_REPAIR));
    }

    @Test
    void newDefaultDoesNotChangeExistingShowsButForksNewOnes() {
        Show before = TestData.show("before");
        registry.createDefaultVersion(PromptKey.JSON_REPAIR, REPAIR_V2, "d2");
        Show after = TestData.show("after");
        assertTrue(repair(resolver.resolve(before.id, PromptResolver.Mode.PRODUCTION)).startsWith("Your previous reply"));
        assertTrue(repair(resolver.resolve(after.id, PromptResolver.Mode.PRODUCTION)).startsWith("JSON broken"));
        assertEquals("Forked from default v2", registry.version(after.id, PromptKey.JSON_REPAIR, 1).orElseThrow().note);
    }

    @Test
    void invalidDefaultIsRejected() {
        assertThrows(InvalidPromptException.class, () -> registry.createDefaultVersion(PromptKey.RANK, "nothing", null));
        assertEquals(1, registry.defaultVersions(PromptKey.RANK).size());
    }

    @Test
    void deletingShowRemovesOnlyItsPrompts() {
        Show show = TestData.show("gone");
        registry.createVersion(show.id, PromptKey.JSON_REPAIR, REPAIR_V2, null);
        QuarkusTransaction.requiringNew().run(() -> Show.deleteById(show.id));
        assertEquals(0L, QuarkusTransaction.requiringNew().call(() -> PromptVersion.count("showId is not null")));
        assertEquals(0L, QuarkusTransaction.requiringNew().call(() -> PromptLabelAssignment.count()));
        assertEquals(1, registry.defaultVersions(PromptKey.JSON_REPAIR).size());
    }

    @Test
    void versionsAreNewestFirst() {
        Show show = TestData.show("hist");
        registry.createVersion(show.id, PromptKey.JSON_REPAIR, REPAIR_V2, "two");
        registry.createVersion(show.id, PromptKey.JSON_REPAIR, REPAIR_V2 + " Please.", "three");
        assertEquals(java.util.List.of(3, 2, 1), registry.versions(show.id, PromptKey.JSON_REPAIR).stream().map(v -> v.version).toList());
        assertEquals("two", registry.version(show.id, PromptKey.JSON_REPAIR, 2).orElseThrow().note);
    }
```

Replace `PromptSeederTest.seedsVersionOneWithBothLabels` and `seedingIsIdempotent` (keep `seedBodiesHaveExpectedEndings`) with:

```java
    @Inject PromptRegistry registry;

    @Test
    void seedsDefaultVersionOneWithoutLabels() {
        for (PromptKey key : PromptKey.values()) {
            PromptVersion v = registry.latestDefault(key);
            assertEquals(1, v.version);
            assertEquals(key.seedBody(), v.body);
        }
        assertEquals(0L, QuarkusTransaction.requiringNew().call(() -> PromptLabelAssignment.count()));
    }

    @Test
    void seedingIsIdempotent() {
        seeder.seed();
        seeder.seed();
        assertEquals(PromptKey.values().length, QuarkusTransaction.requiringNew().call(() -> PromptVersion.count("showId is null")));
    }

    @Test
    void backfillsShowsMissingPrompts() {
        long showId = QuarkusTransaction.requiringNew().call(() -> {
            org.roncax.podcaster.domain.Show s = new org.roncax.podcaster.domain.Show();
            s.name = "Raw"; s.slug = "raw"; s.language = "it"; s.voiceId = "it_IT-paola-medium"; s.writerModel = "fake";
            s.persist();
            return s.id;
        });
        seeder.seed();
        for (PromptKey key : PromptKey.values()) {
            assertEquals(Map.of(PromptLabel.PRODUCTION, 1, PromptLabel.DRAFT, 1), registry.labels(showId, key));
        }
    }
```

(Add the imports `java.util.Map` and `jakarta.inject.Inject` if they are missing. Check `Show` for other required non-null fields: `TestData.show` sets the same fields, plus `minItems`/`targetDurationMinutes`, which have defaults.)

- [ ] **Step 7: Run them to verify they fail**

Run: `./mvnw -q test -Dtest='PromptRegistryTest,PromptSeederTest'`
Expected: compilation failure (`createVersion(long, …)`, `latestDefault`, … don't exist).

- [ ] **Step 8: Implement registry, resolver, seeder**

`PromptRegistry.java`, full replacement:

```java
package org.roncax.podcaster.prompts;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import java.util.*;
import org.roncax.podcaster.domain.Show;

/** Show prompts (versioned, labelled per show) and default prompts (versioned, no labels; the fork source). */
@ApplicationScoped
public class PromptRegistry {
    @Inject PromptRenderer renderer;
    @Inject PromptResolver resolver;

    public PromptVersion createVersion(long showId, PromptKey key, String body, String note) {
        validate(key, body);
        PromptVersion created = QuarkusTransaction.requiringNew().call(() -> {
            requireShow(showId);
            PromptVersion v = insert(showId, key, body, note);
            assign(showId, key, PromptLabel.DRAFT, v.id);
            return v;
        });
        resolver.invalidate();
        return created;
    }

    public void setLabel(long showId, PromptKey key, PromptLabel label, int version) {
        QuarkusTransaction.requiringNew().run(() -> {
            requireShow(showId);
            assign(showId, key, label, require(showId, key, version).id);
        });
        resolver.invalidate();
    }

    /** New show draft with the latest default text; production is left alone. */
    public PromptVersion resetToDefault(long showId, PromptKey key) {
        PromptVersion created = QuarkusTransaction.requiringNew().call(() -> {
            requireShow(showId);
            PromptVersion d = latestDefaultIn(key);
            PromptVersion v = insert(showId, key, d.body, "Reset to default v" + d.version);
            assign(showId, key, PromptLabel.DRAFT, v.id);
            return v;
        });
        resolver.invalidate();
        return created;
    }

    /** Gives the show v1 of every prompt it lacks, from the latest defaults, with both labels. */
    @Transactional(Transactional.TxType.MANDATORY)
    public void fork(long showId) {
        for (PromptKey key : PromptKey.values()) {
            if (PromptVersion.count("showId = ?1 and promptKey = ?2", showId, key.dbKey()) > 0) continue;
            PromptVersion d = latestDefaultIn(key);
            PromptVersion v = insert(showId, key, d.body, "Forked from default v" + d.version);
            for (PromptLabel label : PromptLabel.values()) assign(showId, key, label, v.id);
        }
        resolver.invalidate();
    }

    public List<PromptVersion> versions(long showId, PromptKey key) {
        return QuarkusTransaction.requiringNew().call(() -> PromptVersion.history(showId, key));
    }

    public Optional<PromptVersion> version(long showId, PromptKey key, int n) {
        return QuarkusTransaction.requiringNew().call(() -> PromptVersion.find(showId, key, n));
    }

    public Map<PromptLabel, Integer> labels(long showId, PromptKey key) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Map<PromptLabel, Integer> out = new EnumMap<>(PromptLabel.class);
            for (PromptLabelAssignment a : PromptLabelAssignment.<PromptLabelAssignment>list("showId = ?1 and promptKey = ?2", showId, key.dbKey())) {
                PromptVersion v = PromptVersion.findById(a.versionId);
                PromptLabel.fromDb(a.label).ifPresent(l -> out.put(l, v.version));
            }
            return out;
        });
    }

    public List<String> labelsOf(long showId, PromptKey key, int version) {
        List<String> out = new ArrayList<>();
        labels(showId, key).forEach((label, v) -> { if (v == version) out.add(label.dbValue()); });
        return out;
    }

    public PromptVersion createDefaultVersion(PromptKey key, String body, String note) {
        validate(key, body);
        return QuarkusTransaction.requiringNew().call(() -> insert(null, key, body, note));
    }

    public List<PromptVersion> defaultVersions(PromptKey key) {
        return QuarkusTransaction.requiringNew().call(() -> PromptVersion.history(null, key));
    }

    public Optional<PromptVersion> defaultVersion(PromptKey key, int n) {
        return QuarkusTransaction.requiringNew().call(() -> PromptVersion.find(null, key, n));
    }

    public PromptVersion latestDefault(PromptKey key) {
        return QuarkusTransaction.requiringNew().call(() -> latestDefaultIn(key));
    }

    private void validate(PromptKey key, String body) {
        List<String> errors = renderer.validate(key, body);
        if (!errors.isEmpty()) throw new InvalidPromptException(errors);
    }

    private PromptVersion latestDefaultIn(PromptKey key) {
        return PromptVersion.history(null, key).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No default version for prompt " + key.dbKey()));
    }

    private PromptVersion insert(Long showId, PromptKey key, String body, String note) {
        PromptVersion v = new PromptVersion();
        v.showId = showId;
        v.promptKey = key.dbKey();
        v.version = PromptVersion.history(showId, key).stream().findFirst().map(p -> p.version).orElse(0) + 1;
        v.body = body;
        v.note = note == null || note.isBlank() ? null : note.trim();
        v.persist();
        return v;
    }

    private static void requireShow(long showId) {
        if (Show.findById(showId) == null) throw new NotFoundException("Show " + showId + " not found");
    }

    private PromptVersion require(long showId, PromptKey key, int version) {
        return PromptVersion.find(showId, key, version)
                .orElseThrow(() -> new InvalidPromptException(List.of("Prompt " + key.dbKey() + " has no version " + version + " in this show")));
    }

    private void assign(long showId, PromptKey key, PromptLabel label, Long versionId) {
        PromptLabelAssignment a = PromptLabelAssignment.<PromptLabelAssignment>find("showId = ?1 and promptKey = ?2 and label = ?3",
                        showId, key.dbKey(), label.dbValue())
                .firstResultOptional().orElseGet(() -> {
                    PromptLabelAssignment n = new PromptLabelAssignment();
                    n.showId = showId;
                    n.promptKey = key.dbKey();
                    n.label = label.dbValue();
                    return n;
                });
        a.versionId = versionId;
        a.persist();
    }
}
```

`PromptResolver.java`: change `resolve(Long showId, …)` to `resolve(long showId, Mode mode)`, and make `load` read only the show's label:

```java
    private PromptSet load(long showId, Mode mode) {
        String label = (mode == Mode.DRAFT ? PromptLabel.DRAFT : PromptLabel.PRODUCTION).dbValue();
        Map<PromptKey, PromptSet.Entry> entries = QuarkusTransaction.requiringNew().call(() -> {
            Map<PromptKey, PromptSet.Entry> map = new EnumMap<>(PromptKey.class);
            for (PromptKey key : PromptKey.values()) {
                Long versionId = PromptLabelAssignment.<PromptLabelAssignment>find("showId = ?1 and promptKey = ?2 and label = ?3",
                                showId, key.dbKey(), label)
                        .firstResultOptional().map(a -> a.versionId)
                        .orElseThrow(() -> new GenerationException("No " + label + " version for prompt " + key.dbKey() + " in show " + showId));
                PromptVersion v = PromptVersion.findById(versionId);
                map.put(key, new PromptSet.Entry(v.version, v.body));
            }
            return map;
        });
        return new PromptSet(renderer, entries);
    }
```

Update the class Javadoc to "Resolves which version of each prompt a show uses: the show's label for the mode."

`PromptSeeder.seed()`: seed defaults without labels, then backfill shows:

```java
    @Inject PromptRegistry registry;

    public void seed() {
        QuarkusTransaction.requiringNew().run(() -> {
            for (PromptKey key : PromptKey.values()) {
                if (Prompt.findById(key.dbKey()) == null) {
                    Prompt p = new Prompt();
                    p.key = key.dbKey();
                    p.description = key.description();
                    p.persist();
                }
                if (PromptVersion.count("showId is null and promptKey = ?1", key.dbKey()) > 0) continue;
                PromptVersion v = new PromptVersion();
                v.promptKey = key.dbKey();
                v.version = 1;
                v.body = key.seedBody();
                v.note = "Initial version";
                v.persist();
                LOG.infof("Seeded default prompt %s v1", key.dbKey());
            }
            for (Show show : Show.<Show>listAll()) registry.fork(show.id);
        });
    }
```

Update the class Javadoc: "Inserts missing default prompts with their seed text and forks prompts for shows that lack any. Idempotent." Import `org.roncax.podcaster.domain.Show`.

- [ ] **Step 9: Fork on show creation; test data**

`ShowService`: `@Inject PromptRegistry prompts;`, and in `create` inside the transaction after `s.persist();` add `prompts.fork(s.id);`.

`TestData.resetPrompts()`: remove the `ShowPromptOverride.deleteAll()` line. `TestData.show(...)`: after `s.persist();` add

```java
            io.quarkus.arc.Arc.container().instance(org.roncax.podcaster.prompts.PromptRegistry.class).get().fork(s.id);
```

- [ ] **Step 10: REST resources**

`PromptViews`: keep the records and add `public record DefaultPromptSummary(String key, String description, int latest) {}`. Keep `DryRunRequest` (Task 5 removes it).

`PromptResource` (defaults): remove `setLabel`. `list()` returns `List<DefaultPromptSummary>` using `registry.latestDefault(k).version`. `versions` maps `registry.defaultVersions(k)` to `VersionSummary(v.version, v.note, v.createdAt, List.of())`. `version` uses `registry.defaultVersion(k, n)`. `create` calls `registry.createDefaultVersion` and returns 201 with `VersionDetail(…, List.of(), v.body)`. Keep `dryRun` and `key(String)` unchanged.

`ShowPromptResource`, full replacement:

```java
@Path("/api/shows/{id}/prompts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ShowPromptResource {
    @Inject PromptRegistry registry;

    @GET
    public List<PromptSummary> list(@RestPath long id) {
        requireShow(id);
        return Arrays.stream(PromptKey.values()).map(k -> summary(id, k)).toList();
    }

    @GET
    @Path("/{key}/versions")
    public List<VersionSummary> versions(@RestPath long id, @RestPath String key) {
        requireShow(id);
        PromptKey k = PromptResource.key(key);
        return registry.versions(id, k).stream()
                .map(v -> new VersionSummary(v.version, v.note, v.createdAt, registry.labelsOf(id, k, v.version))).toList();
    }

    @GET
    @Path("/{key}/versions/{n}")
    public VersionDetail version(@RestPath long id, @RestPath String key, @RestPath int n) {
        PromptKey k = PromptResource.key(key);
        return registry.version(id, k, n).map(v -> detail(id, k, v)).orElseThrow(NotFoundException::new);
    }

    @POST
    @Path("/{key}/versions")
    public RestResponse<VersionDetail> create(@RestPath long id, @RestPath String key, @Valid NewVersion request) {
        PromptKey k = PromptResource.key(key);
        PromptVersion v = registry.createVersion(id, k, request.body(), request.note());
        return RestResponse.status(Response.Status.CREATED, detail(id, k, v));
    }

    @PUT
    @Path("/{key}/labels/{label}")
    public PromptSummary setLabel(@RestPath long id, @RestPath String key, @RestPath String label, @Valid VersionRef request) {
        PromptKey k = PromptResource.key(key);
        PromptLabel l = PromptLabel.fromDb(label).orElseThrow(NotFoundException::new);
        registry.setLabel(id, k, l, request.version());
        return summary(id, k);
    }

    @POST
    @Path("/{key}/reset")
    public RestResponse<VersionDetail> reset(@RestPath long id, @RestPath String key) {
        PromptKey k = PromptResource.key(key);
        return RestResponse.status(Response.Status.CREATED, detail(id, k, registry.resetToDefault(id, k)));
    }

    private PromptSummary summary(long id, PromptKey k) {
        Map<PromptLabel, Integer> labels = registry.labels(id, k);
        return new PromptSummary(k.dbKey(), k.description(), labels.get(PromptLabel.PRODUCTION), labels.get(PromptLabel.DRAFT));
    }

    private VersionDetail detail(long id, PromptKey k, PromptVersion v) {
        return new VersionDetail(v.version, v.note, v.createdAt, registry.labelsOf(id, k, v.version), v.body);
    }

    private static void requireShow(long id) {
        if (QuarkusTransaction.requiringNew().call(() -> Show.findById(id)) == null) throw new NotFoundException();
    }
}
```

The `@POST /reset` takes no body, so `@Consumes` JSON with no body must work. If RESTEasy rejects a missing Content-Type, add `@Consumes(MediaType.WILDCARD)` on that method.

- [ ] **Step 11: Update API and pipeline tests**

In `PromptApiTest`, replace `listsPromptsWithLabels`, `createPromoteAndInspectVersions`, `unknownKeyLabelOrVersion` and the show-override test (around lines 75–89) with the tests below. Keep `invalidBodyIs400` and `versionsCannotBeEdited`. In `dryRunUsesDraftAndPersistsNothing`, change the RANK_V2 POST path to `"/api/shows/" + show.id + "/prompts/rank/versions"`.

```java
    @Test
    void listsDefaults() {
        api().get("/api/prompts").then().statusCode(200)
                .body("size()", is(4)).body("find { it.key == 'rank' }.latest", is(1));
    }

    @Test
    void createAndInspectDefaultVersions() {
        api().body(Map.of("body", RANK_V2, "note", "marker")).post("/api/prompts/rank/versions").then().statusCode(201)
                .body("version", is(2)).body("labels", empty());
        api().get("/api/prompts/rank/versions").then().statusCode(200).body("version", contains(2, 1));
        api().get("/api/prompts/rank/versions/1").then().statusCode(200).body("body", is(PromptKey.RANK.seedBody()));
        api().body(Map.of("version", 2)).put("/api/prompts/rank/labels/production").then().statusCode(404);
    }

    @Test
    void showPromptsCreatePromoteReset() {
        Show show = TestData.show("sp");
        String base = "/api/shows/" + show.id + "/prompts";
        api().get(base).then().statusCode(200).body("size()", is(4))
                .body("find { it.key == 'rank' }.production", is(1)).body("find { it.key == 'rank' }.draft", is(1));
        api().body(Map.of("body", RANK_V2, "note", "marker")).post(base + "/rank/versions").then().statusCode(201)
                .body("version", is(2)).body("labels", contains("draft"));
        api().body(Map.of("version", 2)).put(base + "/rank/labels/production").then().statusCode(200).body("production", is(2));
        api().get(base + "/rank/versions").then().statusCode(200).body("version", contains(2, 1))
                .body("[0].labels", hasItems("production", "draft"));
        api().post(base + "/rank/reset").then().statusCode(201).body("version", is(3)).body("note", is("Reset to default v1"))
                .body("body", is(PromptKey.RANK.seedBody()));
    }

    @Test
    void showPromptErrors() {
        Show show = TestData.show("spe");
        String base = "/api/shows/" + show.id + "/prompts";
        api().get("/api/shows/999999/prompts").then().statusCode(404);
        api().get(base + "/nope/versions").then().statusCode(404);
        api().body(Map.of("version", 1)).put(base + "/rank/labels/staging").then().statusCode(404);
        api().body(Map.of("version", 42)).put(base + "/rank/labels/production").then().statusCode(400);
        api().get(base + "/rank/versions/42").then().statusCode(404);
        api().body(Map.of("body", "hello {nonsense}")).post(base + "/rank/versions").then().statusCode(400);
        api().delete(base + "/rank").then().statusCode(405);
    }
```

In `PromptPipelineTest`:
- In `promotedRankVersionIsUsed` and `retryAfterPromotionRecordsNewVersions`, add `show.id` as the first argument to `createVersion`/`setLabel`.
- Replace `pinnedShowUsesPinnedDraftVersion` with:

```java
    @Test
    void otherShowsKeepTheirOwnPrompts() {
        Show other = TestData.show("other-pipe");
        TestData.item(other.id, TestData.source(other.id, "http://unused/other").id, "http://story/other", Instant.now());
        registry.createVersion(show.id, PromptKey.RANK, RANK_V2, "only this show");
        registry.setLabel(show.id, PromptKey.RANK, PromptLabel.PRODUCTION, 2);

        Run run = TestData.awaitRun(launcher.launch(other.id, RunTrigger.MANUAL));

        assertEquals(RunStatus.DONE, run.status, run.error);
        assertFalse(model.userMessage(0).contains("MARKER-RANK-V2"));
        assertEquals(1, episodeOf(run).promptVersions.get("rank"));
    }
```

(Look at how the test's `@BeforeEach` creates `show` and its items, and mirror it for `other` if it needs more, e.g. `minItems`. Add imports as needed.)

- [ ] **Step 12: Default prompt admin pages (shared view record)**

`PromptAdminViews`: delete `OverrideRow`, and add:

```java
    public record Crumb(String label, String href) {}
    public record ShowPromptRow(String key, String description, Integer production, Integer draft) {}
    /** One prompt's page; shared by default prompts ({@code labelled=false}) and show prompts. */
    public record PromptPage(String basePath, List<Crumb> crumbs, String activeNav, String key, String description,
                             String summary, String banner, boolean labelled, List<VersionRow> versions, int shownVersion,
                             String compareTitle, String baseBody, String shownBody, List<DiffRow> diff,
                             String editorBody, String note, List<String> errors, List<String> variables,
                             String resetAction, String testHref) {}
```

Change `PromptRow` to `record PromptRow(String key, String description, int latest)`.

`PromptPages`:
- `Templates.list(List<PromptRow> rows)`, `Templates.detail(PromptPage page)` and `Templates.dryRun(...)`, the last unchanged until Task 5.
- Remove `setLabel`.
- `list()`: build rows from `prompts.latestDefault(key).version`.
- `detail(key, v)`: `shown = v != null ? v : latest`. Base = version `shown - 1` if it exists, else `shown`. Editor prefilled with the latest body.
- `create`: calls `createDefaultVersion`, and redirects to `/admin/prompts/{key}?v={n}`. On `InvalidPromptException`, it re-renders with the errors and the typed text.
- Extract static helpers that Task 2 reuses:

```java
    static List<DiffRow> diffRows(String base, String shown) {
        return LineDiff.diff(base, shown).stream()
                .map(l -> new DiffRow(l.op() == '+' ? "diff-add" : l.op() == '-' ? "diff-del" : "diff-same", String.valueOf(l.op()), l.text()))
                .toList();
    }

    static List<String> variables(PromptKey k) {
        return new TreeSet<>(k.variables()).stream().filter(n -> !n.equals("contract") || k.contract() != null)
                .map(n -> "{" + n + "}").toList();
    }

    private TemplateInstance defaultPage(PromptKey k, int shown, String editorBody, String note, List<String> errors) {
        PromptVersion shownV = prompts.defaultVersion(k, shown).orElseThrow(NotFoundException::new);
        PromptVersion base = prompts.defaultVersion(k, shown - 1).orElse(shownV);
        int latest = prompts.latestDefault(k).version;
        List<VersionRow> versions = prompts.defaultVersions(k).stream()
                .map(ver -> new VersionRow(ver.version, ver.note == null ? "" : ver.note, AdminSupport.when(ver.createdAt), ver.version == latest ? "latest" : ""))
                .toList();
        String compare = base == shownV ? "v" + shown + " (first version)" : "v" + shown + " compared with v" + base.version;
        return Templates.detail(new PromptPage("/admin/prompts/" + k.dbKey(),
                List.of(new Crumb("Default prompts", "/admin/prompts")), "prompts", k.dbKey(), k.description(),
                "latest v" + latest, "Used for new shows. Existing shows change only via Reset to default.", false,
                versions, shown, compare, base.body, shownV.body, diffRows(base.body, shownV.body),
                editorBody, note, errors, variables(k), null, null));
    }
```

`templates/PromptPages/detail.html`: rewrite against `page`. Keep the existing markup and classes, with these changes:
- `{#layout title=page.key active=page.activeNav editor=true}`.
- Breadcrumb: `{#for c in page.crumbs}<a href="{c.href}" class="link">{c.label}</a> / {/for}{page.key}`.
- Header subtitle `{page.description} · {page.summary}`, plus `{#if page.banner}<p class="m-0 rounded-lg bg-info-bg px-3 py-2 text-sm">{page.banner}</p>{/if}`. If `bg-info-bg` isn't a defined token in `app.css`, use `bg-ground ring-1 ring-line`.
- Version cards link to `{page.basePath}?v={v.version}`. The Promote/Set draft forms are wrapped in `{#if page.labelled}` and post to `{page.basePath}/labels/production|draft`.
- After the versions section: `{#if page.resetAction}<form method="post" action="{page.resetAction}"><button class="btn btn-secondary">Reset to default</button></form>{/if}` and `{#if page.testHref}<a href="{page.testHref}" class="btn btn-secondary">Test this draft</a>{/if}`.
- Diff section title `{page.compareTitle}`, with templates `{page.baseBody}`/`{page.shownBody}` and rows `{#for d in page.diff}`.
- **Delete the Dry-run section.**
- The editor form posts to `{page.basePath}/versions`. Variables come from `page.variables`, errors from `page.errors`, the body from `page.editorBody` and the note from `{page.note ?: ''}`.

`templates/PromptPages/list.html`: title and h1 "Default prompts". The intro says "Starting prompts for new shows. Each show has its own copy under its Prompts tab; editing a default never changes existing shows." Each card shows `{#badge tone='info'}latest v{r.latest}{/badge}`.

`templates/tags/layout.html`: change the nav label "Prompts" to "Default prompts".

`ShowPages`:
- Remove `"prompts"` from `TABS` (Task 2 adds it back), `saveOverrides`, `overrideRows`, the `overrides` template parameter and the `nz` helper if it's unused.
- Remove the `PromptRegistry` injection if it's unused.
- In `templates/ShowPages/detail.html`, remove the "Prompt overrides" tab link, the `{#if tab == 'prompts'}` block and the "Dry-run drafts" header button.

`PromptAdminTest`:
- Delete `promoteFromForm` and `showOverridesFromForm`. Keep `createVersionFromForm`, `invalidFormShowsErrorsAndKeepsText`, the `dryRun*` tests and `formWorksWithoutIsland`, adjusting their asserted text where it changed.
- `promptsPageListsAllPrompts` should assert `containsString("Default prompts")`.
- Add:

```java
    @Test
    void firstDefaultVersionRenders() {
        admin().get("/admin/prompts/rank").then().statusCode(200)
                .body(containsString("v1 (first version)")).body(containsString("Existing shows change only via Reset to default"))
                .body(not(containsString("Promote")));
    }
```

In `ShowPagesTest` around line 149, delete the assertion that `?tab=prompts` contains "Prompt overrides". Task 2 adds the new tab test.

- [ ] **Step 13: Run the full suite**

Run: `./mvnw -q test`
Expected: all tests PASS. Fix any remaining compile errors: grep for `resolve(null`, `.pin(`, `overrides(`, `ShowPromptOverride` and `PromptVersion.find(` with two args.

- [ ] **Step 14: Commit**

```bash
git add -A
git commit -m "Per-show prompts: shows fork their own versioned prompts from defaults

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Show Prompts tab and show prompt pages, episode lineage note

**Files:**
- Create: `admin/ShowPromptPages.java`
- Modify: `admin/ShowPages.java`, `templates/ShowPages/detail.html`, `admin/EpisodePages.java`, `templates/EpisodePages/detail.html`
- Test: `admin/PromptAdminTest.java`, `admin/EpisodePagesTest.java`

**Interfaces:**
- Consumes: `PromptRegistry` show methods, `PromptPages.Templates.detail(PromptPage)`, `PromptPages.diffRows`, `PromptPages.variables`, and the `PromptAdminViews.PromptPage`/`Crumb`/`ShowPromptRow` records (all from Task 1)
- Produces: routes under `/admin/shows/{id}/prompts/{key}`, and the show tab `?tab=prompts`. Task 7 sets `testHref` on this page.

- [ ] **Step 1: Write failing admin tests** (append to `PromptAdminTest`)

```java
    @Test
    void showPromptsTabListsKeys() {
        Show show = TestData.show("tabp");
        admin().get("/admin/shows/" + show.id + "?tab=prompts").then().statusCode(200)
                .body(containsString("/admin/shows/" + show.id + "/prompts/rank")).body(containsString("production v1"));
    }

    @Test
    void showPromptCreatePromoteAndReset() {
        Show show = TestData.show("pp");
        String base = "/admin/shows/" + show.id + "/prompts/json_repair";
        String location = admin().formParam("body", REPAIR_V2).formParam("note", "shorter")
                .post(base + "/versions").then().statusCode(303).extract().header("Location");
        assertTrue(location.endsWith(base + "?v=2"), location);
        admin().get(base + "?v=2").then().statusCode(200).body(containsString("shorter")).body(containsString("diff-add"))
                .body(containsString("Promote")).body(containsString("Reset to default"));
        admin().formParam("version", "2").post(base + "/labels/production").then().statusCode(303);
        assertEquals(2, registry.labels(show.id, PromptKey.JSON_REPAIR).get(PromptLabel.PRODUCTION));
        admin().post(base + "/reset").then().statusCode(303);
        assertEquals(3, registry.labels(show.id, PromptKey.JSON_REPAIR).get(PromptLabel.DRAFT));
    }

    @Test
    void invalidShowPromptKeepsText() {
        Show show = TestData.show("ppbad");
        admin().formParam("body", "no placeholders").post("/admin/shows/" + show.id + "/prompts/json_repair/versions")
                .then().statusCode(200).body(containsString("Missing required variable")).body(containsString("no placeholders"));
    }
```

In `EpisodePagesTest`, add a test that creates an episode with `promptVersionsGlobal = true`, opens `/admin/episodes/{id}` and expects `containsString("global registry")`. Follow the existing episode fixture setup in that test class.

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw -q test -Dtest='PromptAdminTest,EpisodePagesTest'`
Expected: FAIL (404s, and the missing text).

- [ ] **Step 3: Implement `ShowPromptPages`**

```java
package org.roncax.podcaster.admin;

// imports: QuarkusTransaction, TemplateInstance, Inject, jakarta.ws.rs.*, MediaType, Response, URI, List, Map,
// RestForm, RestPath, RestQuery, PromptAdminViews.*, Show, prompts.*

@Path("/admin/shows/{id}/prompts")
@Produces(MediaType.TEXT_HTML)
public class ShowPromptPages {
    @Inject PromptRegistry prompts;

    @GET
    @Path("/{key}")
    public TemplateInstance detail(@RestPath long id, @RestPath String key, @RestQuery Integer v) {
        PromptKey k = key(key);
        Map<PromptLabel, Integer> labels = prompts.labels(id, k);
        int draft = labels.get(PromptLabel.DRAFT);
        return page(show(id), k, v != null ? v : draft, prompts.version(id, k, draft).orElseThrow().body, null, List.of());
    }

    @POST
    @Path("/{key}/versions")
    public Response create(@RestPath long id, @RestPath String key, @RestForm String body, @RestForm String note) {
        PromptKey k = key(key);
        try {
            PromptVersion created = prompts.createVersion(id, k, body, note);
            return Response.seeOther(URI.create(base(id, k) + "?v=" + created.version)).build();
        } catch (InvalidPromptException e) {
            return Response.ok(page(show(id), k, prompts.labels(id, k).get(PromptLabel.DRAFT), body, note, e.errors())).build();
        }
    }

    @POST
    @Path("/{key}/labels/{label}")
    public Response setLabel(@RestPath long id, @RestPath String key, @RestPath String label, @RestForm int version) {
        PromptKey k = key(key);
        prompts.setLabel(id, k, PromptLabel.fromDb(label).orElseThrow(NotFoundException::new), version);
        return Response.seeOther(URI.create(base(id, k) + "?v=" + version)).build();
    }

    @POST
    @Path("/{key}/reset")
    public Response reset(@RestPath long id, @RestPath String key) {
        PromptKey k = key(key);
        PromptVersion v = prompts.resetToDefault(id, k);
        return Response.seeOther(URI.create(base(id, k) + "?v=" + v.version)).build();
    }

    private TemplateInstance page(Show show, PromptKey k, int shown, String editorBody, String note, List<String> errors) {
        Map<PromptLabel, Integer> labels = prompts.labels(show.id, k);
        int production = labels.get(PromptLabel.PRODUCTION);
        String shownBody = prompts.version(show.id, k, shown).orElseThrow(NotFoundException::new).body;
        String productionBody = prompts.version(show.id, k, production).orElseThrow().body;
        List<VersionRow> versions = prompts.versions(show.id, k).stream()
                .map(ver -> new VersionRow(ver.version, ver.note == null ? "" : ver.note, AdminSupport.when(ver.createdAt),
                        String.join(", ", prompts.labelsOf(show.id, k, ver.version))))
                .toList();
        return PromptPages.Templates.detail(new PromptPage(base(show.id, k),
                List.of(new Crumb("Shows", "/admin/shows"), new Crumb(show.name, "/admin/shows/" + show.id + "?tab=prompts")),
                "shows", k.dbKey(), k.description(), "production v" + production + " · draft v" + labels.get(PromptLabel.DRAFT),
                null, true, versions, shown, "v" + shown + " compared with production (v" + production + ")",
                productionBody, shownBody, PromptPages.diffRows(productionBody, shownBody),
                editorBody, note, errors, PromptPages.variables(k), base(show.id, k) + "/reset", null));
    }

    private static String base(long id, PromptKey k) { return "/admin/shows/" + id + "/prompts/" + k.dbKey(); }

    private static Show show(long id) {
        return QuarkusTransaction.requiringNew().call(() -> Show.<Show>findByIdOptional(id).orElseThrow(NotFoundException::new));
    }

    private static PromptKey key(String key) { return PromptKey.fromDb(key).orElseThrow(NotFoundException::new); }
}
```

(`PromptPages.Templates` is a package-private static nested class, and this class is in the same package, so the call compiles. Check that `@CheckedTemplate` templates can be rendered from another resource; they can, since a template is just a `TemplateInstance`.)

- [ ] **Step 4: Show Prompts tab**

`ShowPages`:
- Add `"prompts"` back to `TABS`, and re-inject `PromptRegistry prompts`.
- Add the `List<ShowPromptRow> prompts` parameter to `Templates.detail` (where `overrides` was) and pass `promptRows(show.id)`:

```java
    private List<ShowPromptRow> promptRows(long showId) {
        return java.util.Arrays.stream(PromptKey.values()).map(k -> {
            Map<PromptLabel, Integer> labels = prompts.labels(showId, k);
            return new ShowPromptRow(k.dbKey(), k.description(), labels.get(PromptLabel.PRODUCTION), labels.get(PromptLabel.DRAFT));
        }).toList();
    }
```

`templates/ShowPages/detail.html`: add the tab link `Prompts` after Settings (same markup as the others, `?tab=prompts`) and the block:

```html
{#if tab == 'prompts'}
<section class="card">
  <h2 class="m-0 text-base font-semibold">Prompts</h2>
  <p class="m-0 text-sm text-muted">This show's own prompts. Runs use <strong>production</strong>; the Test tab can use <strong>draft</strong>.</p>
  <table class="w-full border-collapse text-sm"><tbody>
    {#for p in prompts}
    <tr class="border-t border-line">
      <td class="py-2.5"><a href="/admin/shows/{show.id}/prompts/{p.key}" class="link mono">{p.key}</a></td>
      <td class="py-2.5 text-muted">{p.description}</td>
      <td class="py-2.5">{#badge tone='ok'}production v{p.production}{/badge} {#badge tone='info'}draft v{p.draft}{/badge}</td>
    </tr>
    {/for}
  </tbody></table>
</section>
{/if}
```

- [ ] **Step 5: Episode lineage note**

In `EpisodePages.detail`, pass a `boolean globalPrompts = e.promptVersionsGlobal`: add it as a field on `EpisodeView`, or as an extra template parameter, whichever matches how `EpisodeView` is built. In `templates/EpisodePages/detail.html`, next to the "made with" list, add `{#if view.globalPrompts}<p class="m-0 text-xs text-muted">Prompt versions refer to the global registry (pre per-show prompts).</p>{/if}`. Adjust the expression to how the template names the view.

- [ ] **Step 6: Run the tests**

Run: `./mvnw -q test`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "Admin: show Prompts tab with per-show versions, reset to default; lineage note on old episodes

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: `IngestionService.collect` (fetch + filter + extract without persisting)

**Files:**
- Create: `ingestion/CollectedItem.java`
- Modify: `ingestion/IngestionService.java`
- Test: `ingestion/IngestionServiceTest.java`

**Interfaces:**
- Produces: `public record CollectedItem(RawItem raw, String fullText, String contentHash)` and `IngestionService.collect(Source source, Instant since, Predicate<String> seen) throws Exception → List<CollectedItem>` (used by Task 4)

- [ ] **Step 1: Write the failing test** (append to `IngestionServiceTest`)

```java
    @Test
    void collectExtractsWithoutPersisting() throws Exception {
        Show show = TestData.show("col");
        Source source = TestData.source(show.id, wm.baseUrl() + "/feedc");
        stubFeed("/feedc", rss("First story|/c1", "Second story|/c2"));
        stubArticle("/c1");
        stubArticle("/c2");

        List<CollectedItem> items = ingestion.collect(source, since(), url -> url.endsWith("/c2"));

        assertEquals(1, items.size());
        assertTrue(items.get(0).fullText().contains("sciopero"));
        assertNotNull(items.get(0).contentHash());
        assertEquals(0L, (long) QuarkusTransaction.requiringNew().call(() -> Item.count("showId", show.id)));
        assertNull(QuarkusTransaction.requiringNew().call(() -> Source.<Source>findById(source.id).lastFetchedAt));
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw -q test -Dtest=IngestionServiceTest#collectExtractsWithoutPersisting`
Expected: compilation failure (`collect`, `CollectedItem`).

- [ ] **Step 3: Implement**

`CollectedItem.java`:

```java
package org.roncax.podcaster.ingestion;

/** A fetched item after link filtering and full-text extraction; not yet stored. */
public record CollectedItem(RawItem raw, String fullText, String contentHash) {}
```

In `IngestionService`, replace `ingestSource` and `save` with:

```java
    private int ingestSource(long showId, Source source, Instant since) throws Exception {
        int added = 0;
        for (CollectedItem item : collect(source, since, url -> exists(showId, url))) {
            if (save(showId, source.id, item)) added++;
        }
        return added;
    }

    /** Fetches, filters and extracts a source's items. Stores nothing; {@code seen} URLs are skipped. */
    public List<CollectedItem> collect(Source source, Instant since, Predicate<String> seen) throws Exception {
        SourceConnector connector = registry.find(source.connectorType)
                .orElseThrow(() -> new IllegalStateException("Unknown connector type '" + source.connectorType + "'"));
        List<RawItem> raws = connector.fetch(new SourceConfig(source.config), since, seen);
        List<CollectedItem> out = new ArrayList<>();
        for (RawItem raw : raws) {
            if (!org.roncax.podcaster.admin.SafeHref.isHttp(raw.url())) {
                LOG.debugf("Skipping item with non-http link from %s", source.label());
                continue;
            }
            if (raw.url().length() > MAX_URL || seen.test(raw.url())) continue;
            String fullText = raw.fullText();
            if (fullText == null && source.fetchFullText && !connector.providesFullText()) {
                try {
                    fullText = extraction.extract(raw.url()).orElse(null);
                } catch (Exception e) {
                    LOG.debugf("Extraction failed for %s: %s", raw.url(), e.getMessage());
                }
            }
            out.add(new CollectedItem(raw, fullText, ContentHasher.hash(raw.title(), fullText != null ? fullText : raw.summary())));
        }
        return out;
    }

    private boolean save(long showId, long sourceId, CollectedItem collected) {
        RawItem raw = collected.raw();
        return QuarkusTransaction.requiringNew().call(() -> {
            if (Item.count("showId = ?1 and contentHash = ?2", showId, collected.contentHash()) > 0) return false;
            Item item = new Item();
            item.showId = showId;
            item.sourceId = sourceId;
            item.url = raw.url();
            item.contentHash = collected.contentHash();
            item.title = raw.title().length() > MAX_TITLE ? raw.title().substring(0, MAX_TITLE) : raw.title();
            item.author = raw.author();
            item.publishedAt = raw.publishedAt();
            item.fetchedAt = Instant.now();
            item.summary = raw.summary();
            item.fullText = collected.fullText();
            item.discussionUrl = org.roncax.podcaster.admin.SafeHref.isHttp(raw.discussionUrl()) ? raw.discussionUrl() : null;
            item.persist();
            return true;
        });
    }
```

Import `java.util.function.Predicate`. Make `MAX_TITLE` package-visible (`static final int MAX_TITLE`) so Task 4 can reuse it via `IngestionService.MAX_TITLE`. Change it to `public static final` if Task 4's class is in another package; it is (`testing`), so make it `public static final int MAX_TITLE = 1000;`.

- [ ] **Step 4: Run the tests**

Run: `./mvnw -q test`
Expected: PASS (all existing ingestion and Reddit tests unchanged).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Ingestion: extract collect() so items can be fetched without storing them

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Test session infrastructure and source dry-run (REST)

**Files:**
- Create: `testing/TestSession.java`, `testing/TestSessionStore.java`, `testing/TestRunner.java`, `testing/TestStepBusyException.java`, `testing/SourceDryRun.java`
- Create: `api/TestResource.java`, `api/TestViews.java`
- Modify: `api/ApiExceptionMappers.java` (409 for `TestStepBusyException`)
- Test: `src/test/java/org/roncax/podcaster/testing/TestApiTest.java`, `src/test/java/org/roncax/podcaster/testing/TestSessionStoreTest.java`

**Interfaces:**
- Consumes: `IngestionService.collect`, `CollectedItem`, `IngestionService.MAX_TITLE` (Task 3)
- Produces (for Tasks 5–7):
  - `TestSession` with `enum Step { SOURCES, PROMPTS, VOICE }`, `enum Status { IDLE, RUNNING, DONE, FAILED }` and `record StepState(Status status, String error)`
  - `record SourceResult(long sourceId, String label, int items, String error)`
  - `record Fetched(List<SourceResult> sources, List<Item> items)`
  - `record Clip(byte[] wav, double durationSeconds, double wordsPerMinute, String voiceId, double lengthScale)`
  - accessors `state(Step)`, `fetched()`, `dryRun()` (type `PromptDryRun.Result`, Object-typed until Task 5; see Step 3), `clip()`
  - setters `fetched(Fetched)` (clears dryRun and clip), `dryRun(...)`, `clip(Clip)`
  - `TestSessionStore.session(long showId) → TestSession` and `find(long) → Optional<TestSession>`
  - `TestRunner.start(long showId, TestSession.Step step, Work work)` with `interface Work { void run(TestSession s) throws Exception; }`; it throws `TestStepBusyException`
  - `TestViews.Session` (JSON view) and `TestViews.of(TestSession)`
  - `TestResource` at `/api/shows/{id}/test`

- [ ] **Step 1: Write the failing tests**

`src/test/java/org/roncax/podcaster/testing/TestApiTest.java`:

```java
package org.roncax.podcaster.testing;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.domain.*;
import org.roncax.podcaster.support.*;

@QuarkusTest
@WithTestResource(WireMockResource.class)
@WithTestResource(org.roncax.podcaster.support.PostgresResource.class)
class TestApiTest {
    @InjectWireMock WireMockServer wm;
    FakeChatModel model;

    @BeforeEach
    void setup() {
        TestData.cleanDb();
        WireMockResource.installDefaults(wm);
        model = new FakeChatModel().responder(FakeResponses::pipeline);
        FakeChatModelRegistry.install(model);
    }

    RequestSpecification api() {
        return given().header("X-API-Key", "test-api-key-0123456789").contentType(ContentType.JSON);
    }

    /** Polls the session until {@code step} is no longer RUNNING. */
    JsonPath await(long showId, String step) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            JsonPath json = api().get("/api/shows/" + showId + "/test").then().statusCode(200).extract().jsonPath();
            if (!"RUNNING".equals(json.getString("steps." + step + ".status"))) return json;
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        throw new AssertionError(step + " still running after 30s");
    }

    String rss(String... items) {
        String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.now().atOffset(ZoneOffset.UTC));
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>t</title><link>http://x</link><description>d</description>");
        for (String it : items) {
            String[] p = it.split("\\|");
            sb.append("<item><title>").append(p[0]).append("</title><link>").append(wm.baseUrl()).append(p[1])
              .append("</link><pubDate>").append(date).append("</pubDate><description>Teaser</description></item>");
        }
        return sb.append("</channel></rss>").toString();
    }

    Show showWithFeed(String slug) {
        Show show = TestData.show(slug);
        TestData.source(show.id, wm.baseUrl() + "/" + slug + "/feed");
        wm.stubFor(get("/" + slug + "/feed").willReturn(okXml(rss("First story|/" + slug + "/a1", "Second story|/" + slug + "/a2"))));
        wm.stubFor(get(urlPathMatching("/" + slug + "/a.")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/html").withBody(Fixtures.bytes("ilpost-article.html"))));
        return show;
    }

    @Test
    void emptySessionIsIdle() {
        Show show = TestData.show("idle");
        api().get("/api/shows/" + show.id + "/test").then().statusCode(200)
                .body("steps.SOURCES.status", is("IDLE")).body("items", empty());
        api().get("/api/shows/999999/test").then().statusCode(404);
    }

    @Test
    void sourceStepFetchesWithoutPersisting() {
        Show show = showWithFeed("src");
        Source broken = TestData.source(show.id, wm.baseUrl() + "/src/missing");

        api().post("/api/shows/" + show.id + "/test/sources").then().statusCode(202);
        JsonPath json = await(show.id, "SOURCES");

        assertEquals("DONE", json.getString("steps.SOURCES.status"));
        assertEquals(2, json.getList("items").size());
        assertEquals(1, json.getInt("items[0].id"));
        assertTrue(json.getBoolean("items[0].hasFullText"));
        assertEquals(2, json.getInt("sources[0].items")); // sources are in id order; the working feed is first
        assertNotNull(json.getString("sources.find { it.sourceId == " + broken.id + " }.error"));
        assertEquals(0L, (long) QuarkusTransaction.requiringNew().call(() -> Item.count()));
        QuarkusTransaction.requiringNew().run(() -> {
            for (Source s : Source.<Source>list("showId", show.id)) {
                assertNull(s.lastFetchedAt);
                assertNull(s.lastError);
            }
        });
    }

    @Test
    void concurrentStepIs409() {
        Show show = showWithFeed("busy");
        wm.stubFor(get("/busy/feed").willReturn(okXml(rss("Slow|/busy/a1")).withFixedDelay(1500)));
        api().post("/api/shows/" + show.id + "/test/sources").then().statusCode(202);
        api().post("/api/shows/" + show.id + "/test/sources").then().statusCode(409);
        await(show.id, "SOURCES");
    }
}
```

`src/test/java/org/roncax/podcaster/testing/TestSessionStoreTest.java` (plain JUnit):

```java
package org.roncax.podcaster.testing;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TestSessionStoreTest {
    @Test
    void idleSessionsExpireButRunningOnesStay() {
        TestSessionStore store = new TestSessionStore();
        store.session(1L);
        TestSession running = store.session(2L);
        assertTrue(running.begin(TestSession.Step.SOURCES));

        store.evictIdle(Instant.now().plus(Duration.ofMinutes(31)));

        assertTrue(store.find(1L).isEmpty());
        assertTrue(store.find(2L).isPresent());
    }

    @Test
    void newFetchClearsLaterResults() {
        TestSession s = new TestSession(1L);
        s.clip(new TestSession.Clip(new byte[0], 1, 150, "v", 1.0));
        s.fetched(new TestSession.Fetched(java.util.List.of(), java.util.List.of()));
        assertNull(s.clip());
    }

    @Test
    void onlyOneStepRunsAtATime() {
        TestSession s = new TestSession(1L);
        assertTrue(s.begin(TestSession.Step.SOURCES));
        assertFalse(s.begin(TestSession.Step.VOICE));
        s.finish(TestSession.Step.SOURCES, null);
        assertTrue(s.begin(TestSession.Step.VOICE));
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw -q test -Dtest='TestApiTest,TestSessionStoreTest'`
Expected: compilation failure.

- [ ] **Step 3: Implement the session, store, runner and exception**

`testing/TestSession.java`:

```java
package org.roncax.podcaster.testing;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.roncax.podcaster.domain.Item;

/** One show's dry-run state, in memory only. Items are transient (never persisted). */
public final class TestSession {
    public enum Step { SOURCES, PROMPTS, VOICE }
    public enum Status { IDLE, RUNNING, DONE, FAILED }

    public record StepState(Status status, String error) {
        static final StepState IDLE = new StepState(Status.IDLE, null);
    }
    public record SourceResult(long sourceId, String label, int items, String error) {}
    public record Fetched(List<SourceResult> sources, List<Item> items) {}
    public record Clip(byte[] wav, double durationSeconds, double wordsPerMinute, String voiceId, double lengthScale) {}

    private final long showId;
    private final Map<Step, StepState> states = new EnumMap<>(Step.class);
    private volatile Instant touchedAt = Instant.now();
    private Fetched fetched;
    private Object dryRun; // PromptDryRun.Result; typed in Task 5
    private Clip clip;

    public TestSession(long showId) {
        this.showId = showId;
        for (Step step : Step.values()) states.put(step, StepState.IDLE);
    }

    public long showId() { return showId; }
    public Instant touchedAt() { return touchedAt; }
    void touch() { touchedAt = Instant.now(); }

    /** Marks {@code step} running unless any step already is. */
    public synchronized boolean begin(Step step) {
        if (running()) return false;
        states.put(step, new StepState(Status.RUNNING, null));
        touch();
        return true;
    }

    public synchronized void finish(Step step, String error) {
        states.put(step, new StepState(error == null ? Status.DONE : Status.FAILED, error));
        touch();
    }

    public synchronized boolean running() {
        return states.values().stream().anyMatch(s -> s.status() == Status.RUNNING);
    }

    public synchronized StepState state(Step step) { return states.get(step); }
    public synchronized Fetched fetched() { return fetched; }
    public synchronized Clip clip() { return clip; }
    public synchronized Object dryRun() { return dryRun; }

    /** A new fetch invalidates results built on the previous items. */
    public synchronized void fetched(Fetched value) {
        fetched = value;
        dryRun = null;
        clip = null;
        states.put(Step.PROMPTS, StepState.IDLE);
        states.put(Step.VOICE, StepState.IDLE);
    }

    public synchronized void dryRun(Object value) { dryRun = value; }
    public synchronized void clip(Clip value) { clip = value; }
}
```

Watch out: `fetched(...)` resets PROMPTS/VOICE to IDLE. It is called from inside the SOURCES step, while no other step can be running, so that is safe.

`testing/TestSessionStore.java`:

```java
package org.roncax.podcaster.testing;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class TestSessionStore {
    static final Duration IDLE = Duration.ofMinutes(30);
    private final Map<Long, TestSession> sessions = new ConcurrentHashMap<>();

    public TestSession session(long showId) {
        TestSession s = sessions.computeIfAbsent(showId, TestSession::new);
        s.touch();
        return s;
    }

    public Optional<TestSession> find(long showId) {
        TestSession s = sessions.get(showId);
        if (s != null) s.touch();
        return Optional.ofNullable(s);
    }

    @Scheduled(every = "5m", identity = "test-session-sweep")
    void sweep() { evictIdle(Instant.now()); }

    void evictIdle(Instant now) {
        sessions.values().removeIf(s -> !s.running() && s.touchedAt().plus(IDLE).isBefore(now));
    }
}
```

`testing/TestStepBusyException.java`:

```java
package org.roncax.podcaster.testing;

public class TestStepBusyException extends RuntimeException {
    public TestStepBusyException(long showId) {
        super("A test step is already running for show " + showId);
    }
}
```

`testing/TestRunner.java`:

```java
package org.roncax.podcaster.testing;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jboss.logging.Logger;
import org.roncax.podcaster.util.Exceptions;

/** Runs test steps in the background, one per show at a time; separate from real runs. */
@ApplicationScoped
public class TestRunner {
    private static final Logger LOG = Logger.getLogger(TestRunner.class);

    public interface Work { void run(TestSession session) throws Exception; }

    @Inject TestSessionStore store;
    private ExecutorService executor;

    @PostConstruct
    void start() {
        executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "podcaster-test");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void stop() { executor.shutdownNow(); }

    public void start(long showId, TestSession.Step step, Work work) {
        TestSession session = store.session(showId);
        if (!session.begin(step)) throw new TestStepBusyException(showId);
        executor.submit(() -> {
            try {
                work.run(session);
                session.finish(step, null);
            } catch (Throwable t) {
                LOG.infof("Test step %s for show %d failed: %s", step, showId, Exceptions.message(t));
                session.finish(step, Exceptions.message(t));
            }
        });
    }
}
```

`ApiExceptionMappers`: add a mapper for `TestStepBusyException` → 409 with `ErrorBody(e.getMessage(), List.of())`, following the existing mapper methods' annotations.

- [ ] **Step 4: Implement `SourceDryRun`**

```java
package org.roncax.podcaster.testing;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.*;
import org.roncax.podcaster.config.PodcasterConfig;
import org.roncax.podcaster.domain.Item;
import org.roncax.podcaster.domain.Source;
import org.roncax.podcaster.ingestion.CollectedItem;
import org.roncax.podcaster.ingestion.IngestionService;
import org.roncax.podcaster.util.Exceptions;

/** Fetches all enabled sources like ingestion does, but keeps the items in memory and touches no row. */
@ApplicationScoped
public class SourceDryRun {
    @Inject IngestionService ingestion;
    @Inject PodcasterConfig config;

    public TestSession.Fetched run(long showId) {
        List<Source> sources = QuarkusTransaction.requiringNew()
                .call(() -> Source.<Source>list("showId = ?1 and enabled = true order by id", showId));
        Instant since = Instant.now().minus(config.selection().firstRunWindow());
        List<TestSession.SourceResult> results = new ArrayList<>();
        List<Item> items = new ArrayList<>();
        Set<String> urls = new HashSet<>();
        Set<String> hashes = new HashSet<>();
        for (Source source : sources) {
            try {
                int kept = 0;
                for (CollectedItem c : ingestion.collect(source, since, url -> false)) {
                    if (!urls.add(c.raw().url()) || !hashes.add(c.contentHash())) continue;
                    items.add(toItem(showId, source.id, c));
                    kept++;
                }
                results.add(new TestSession.SourceResult(source.id, source.label(), kept, null));
            } catch (Exception e) {
                results.add(new TestSession.SourceResult(source.id, source.label(), 0, Exceptions.rootMessage(e)));
            }
        }
        items.sort(Comparator.comparing(Item::effectiveDate, Comparator.nullsLast(Comparator.reverseOrder())));
        List<Item> capped = new ArrayList<>(items.subList(0, Math.min(items.size(), config.selection().maxCandidates())));
        for (int i = 0; i < capped.size(); i++) capped.get(i).id = (long) (i + 1); // shown to the LLM as [id=N]
        return new TestSession.Fetched(results, capped);
    }

    private static Item toItem(long showId, long sourceId, CollectedItem c) {
        Item item = new Item();
        item.showId = showId;
        item.sourceId = sourceId;
        item.url = c.raw().url();
        item.contentHash = c.contentHash();
        String title = c.raw().title();
        item.title = title.length() > IngestionService.MAX_TITLE ? title.substring(0, IngestionService.MAX_TITLE) : title;
        item.author = c.raw().author();
        item.publishedAt = c.raw().publishedAt();
        item.fetchedAt = Instant.now();
        item.summary = c.raw().summary();
        item.fullText = c.fullText();
        return item;
    }
}
```

(Check `Item.effectiveDate()`. If it never returns null, the `nullsLast` is harmless.)

- [ ] **Step 5: Implement `TestViews` and `TestResource`**

`api/TestViews.java`. These are views only, so transient `Item` entities are never serialized directly:

```java
package org.roncax.podcaster.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.roncax.podcaster.testing.TestSession;

public final class TestViews {
    private TestViews() {}

    public record StepView(String status, String error) {}
    public record ItemView(long id, long sourceId, String title, String url, Instant publishedAt, boolean hasFullText, int textLength) {}
    public record ClipView(double durationSeconds, double wordsPerMinute, String voiceId, double lengthScale, String audioUrl) {}
    /** {@code steps} is keyed by step name (SOURCES, PROMPTS, VOICE) so JSON and Qute can both look steps up by string. */
    public record Session(Map<String, StepView> steps, List<TestSession.SourceResult> sources, List<ItemView> items,
                          Object dryRun, ClipView clip) {}

    public static Session of(TestSession s, String audioUrl) {
        Map<String, StepView> steps = new LinkedHashMap<>();
        for (TestSession.Step step : TestSession.Step.values()) {
            TestSession.StepState st = s.state(step);
            steps.put(step.name(), new StepView(st.status().name(), st.error()));
        }
        TestSession.Fetched f = s.fetched();
        List<ItemView> items = f == null ? List.of() : f.items().stream()
                .map(i -> new ItemView(i.id, i.sourceId, i.title, i.url, i.publishedAt, i.fullText != null,
                        i.bestText() == null ? 0 : i.bestText().length()))
                .toList();
        TestSession.Clip c = s.clip();
        ClipView clip = c == null ? null : new ClipView(c.durationSeconds(), c.wordsPerMinute(), c.voiceId(), c.lengthScale(), audioUrl);
        return new Session(steps, f == null ? List.of() : f.sources(), items, s.dryRun(), clip);
    }

    public static Session empty(long showId) {
        return of(new TestSession(showId), null);
    }
}
```

`api/TestResource.java`:

```java
package org.roncax.podcaster.api;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestResponse;
import org.roncax.podcaster.domain.Show;
import org.roncax.podcaster.testing.*;

@Path("/api/shows/{id}/test")
@Produces(MediaType.APPLICATION_JSON)
public class TestResource {
    @Inject TestSessionStore store;
    @Inject TestRunner runner;
    @Inject SourceDryRun sources;

    @GET
    public TestViews.Session get(@RestPath long id) {
        requireShow(id);
        return store.find(id).map(s -> TestViews.of(s, audioUrl(id))).orElseGet(() -> TestViews.empty(id));
    }

    @POST
    @Path("/sources")
    @Consumes(MediaType.WILDCARD)
    public RestResponse<TestViews.Session> fetchSources(@RestPath long id) {
        requireShow(id);
        runner.start(id, TestSession.Step.SOURCES, s -> s.fetched(sources.run(id)));
        return RestResponse.status(Response.Status.ACCEPTED, get(id));
    }

    static String audioUrl(long id) { return "/api/shows/" + id + "/test/voice.wav"; }

    static void requireShow(long id) {
        if (QuarkusTransaction.requiringNew().call(() -> Show.findById(id)) == null) throw new NotFoundException();
    }
}
```

- [ ] **Step 6: Run the tests**

Run: `./mvnw -q test`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "Test tab backend: in-memory test sessions and source dry-run

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Prompt dry-run as a test step (draft/production, session/database items)

**Files:**
- Move: `prompts/PromptDryRun.java` → `testing/PromptDryRun.java`
- Move: `prompts/NoCandidatesException.java` → keep it in `prompts` (the mappers already reference it); just import it
- Modify: `testing/TestSession.java` (type `dryRun` as `PromptDryRun.Result`), `api/TestViews.java`, `api/TestResource.java`
- Modify: `api/PromptResource.java` (remove `dryRun`), `api/PromptViews.java` (remove `DryRunRequest`), `admin/PromptPages.java` (remove `dryRun` endpoint and template method)
- Delete: `templates/PromptPages/dryRun.html`
- Test: `testing/TestApiTest.java`; remove the dry-run tests from `api/PromptApiTest.java` and `admin/PromptAdminTest.java`

**Interfaces:**
- Consumes: `TestSession`, `TestRunner` (Task 4), `PromptResolver.resolve(long, Mode)` (Task 1)
- Produces:
  - `PromptDryRun.ItemsSource { SESSION, DATABASE }`
  - `PromptDryRun.Prepared(Show show, List<Item> candidates, PromptSet prompts)`
  - `prepare(long showId, PromptResolver.Mode mode, ItemsSource items, TestSession session) → Prepared` (throws `NoCandidatesException`)
  - `execute(Prepared) → Result`
  - `Result` unchanged: `(Map<String,Integer> promptVersions, Selection selection, Outline outline, String title, String description, List<String> scriptParts)`
  - `TestSession.dryRun() → PromptDryRun.Result`
  - `POST /api/shows/{id}/test/prompts {mode, items}`

- [ ] **Step 1: Write the failing tests** (append to `TestApiTest`)

```java
    @Test
    void promptDryRunOverFetchedItems() {
        Show show = showWithFeed("pdr");
        api().post("/api/shows/" + show.id + "/test/sources").then().statusCode(202);
        await(show.id, "SOURCES");

        api().body(java.util.Map.of("mode", "draft", "items", "session")).post("/api/shows/" + show.id + "/test/prompts").then().statusCode(202);
        JsonPath json = await(show.id, "PROMPTS");

        assertEquals("DONE", json.getString("steps.PROMPTS.status"), json.getString("steps.PROMPTS.error"));
        assertEquals(3, json.getList("dryRun.scriptParts").size());
        assertEquals("Test episode", json.getString("dryRun.title"));
        assertTrue(model.userMessage(0).contains("[id=1]"), model.userMessage(0));
        assertEquals(java.util.List.of(1, 2), json.getList("dryRun.selection.clusters[0].itemIds", Integer.class));
        assertEquals(0L, (long) QuarkusTransaction.requiringNew().call(() -> Episode.count()));
        assertEquals(0L, (long) QuarkusTransaction.requiringNew().call(() -> Item.count()));
        assertEquals(0L, (long) QuarkusTransaction.requiringNew().call(() -> VoiceCalibration.count()));
    }

    @Test
    void promptDryRunUsesDraftOrProductionFromDatabase() {
        Show show = TestData.show("pdb");
        Source source = TestData.source(show.id, "http://unused/feed");
        Item item = TestData.item(show.id, source.id, "http://story/1", Instant.now());
        String rankV2 = "MARKER-RANK-V2 for \"{showName}\".\n{contract}\n\nITEMS:\n{items}\n";
        api().body(java.util.Map.of("body", rankV2)).post("/api/shows/" + show.id + "/prompts/rank/versions").then().statusCode(201);

        api().body(java.util.Map.of("mode", "production", "items", "database")).post("/api/shows/" + show.id + "/test/prompts").then().statusCode(202);
        assertEquals(1, await(show.id, "PROMPTS").getInt("dryRun.promptVersions.rank"));
        assertFalse(model.userMessage(0).contains("MARKER-RANK-V2"));

        api().body(java.util.Map.of("mode", "draft", "items", "database")).post("/api/shows/" + show.id + "/test/prompts").then().statusCode(202);
        assertEquals(2, await(show.id, "PROMPTS").getInt("dryRun.promptVersions.rank"));
        assertTrue(model.requests.stream().anyMatch(r -> FakeChatModel.lastUser(r).contains("MARKER-RANK-V2")));
        assertNull(QuarkusTransaction.requiringNew().call(() -> Item.<Item>findById(item.id).usedInEpisodeId));
    }

    @Test
    void promptDryRunWithoutCandidatesIs409() {
        Show show = TestData.show("pnone");
        api().body(java.util.Map.of("mode", "draft", "items", "session")).post("/api/shows/" + show.id + "/test/prompts").then()
                .statusCode(409).body("error", containsString("Fetch sources first"));
        api().body(java.util.Map.of("mode", "draft", "items", "database")).post("/api/shows/" + show.id + "/test/prompts").then()
                .statusCode(409).body("error", containsString("No unused items"));
        api().body(java.util.Map.of("mode", "staging", "items", "database")).post("/api/shows/" + show.id + "/test/prompts").then()
                .statusCode(400);
        assertTrue(model.requests.isEmpty());
    }

    @Test
    void promptDryRunFailureIsReportedOnTheStep() {
        Show show = TestData.show("pfail");
        TestData.item(show.id, TestData.source(show.id, "http://unused/feed").id, "http://story/broken", Instant.now());
        model.responder(prompt -> "this is not json");
        api().body(java.util.Map.of("mode", "draft", "items", "database")).post("/api/shows/" + show.id + "/test/prompts").then().statusCode(202);
        JsonPath json = await(show.id, "PROMPTS");
        assertEquals("FAILED", json.getString("steps.PROMPTS.status"));
        assertTrue(json.getString("steps.PROMPTS.error").contains("did not return valid JSON"), json.getString("steps.PROMPTS.error"));
    }
```

`FakeResponses.pipeline` puts every `[id=N]` from the rank prompt in one cluster, so `[1, 2]` holds. Both fixture articles share an HTML body, but their content hashes differ because the hash includes the title ("First story" / "Second story").

Delete `PromptApiTest.dryRunUsesDraftAndPersistsNothing`, `dryRunWithoutItemsIs409` and `dryRunFailureIsReportedNot500`, and `PromptAdminTest.dryRunFragmentExplainsMissingItems` and `dryRunFragmentShowsModelFailure`. The tests above cover them.

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw -q test -Dtest=TestApiTest`
Expected: FAIL (404 on `/test/prompts`).

- [ ] **Step 3: Move and extend `PromptDryRun`**

`git mv src/main/java/org/roncax/podcaster/prompts/PromptDryRun.java src/main/java/org/roncax/podcaster/testing/PromptDryRun.java`, then rewrite it:

```java
package org.roncax.podcaster.testing;

// imports as before, plus org.roncax.podcaster.prompts.*

/** Runs ranking, outline and script writing with a show's draft or production prompts. Persists nothing. */
@ApplicationScoped
public class PromptDryRun {
    public enum ItemsSource { SESSION, DATABASE }
    public record Result(Map<String, Integer> promptVersions, Selection selection, Outline outline,
                         String title, String description, List<String> scriptParts) {}
    public record Prepared(Show show, List<Item> candidates, PromptSet prompts) {}

    @Inject ChatModelRegistry models;
    @Inject StoryRanker ranker;
    @Inject OutlinePlanner planner;
    @Inject ScriptWriter writer;
    @Inject VoiceCalibrationService calibration;
    @Inject PromptResolver resolver;
    @Inject PodcasterConfig config;

    /** Cheap checks done before the step is started, so the caller gets 404/409 synchronously. */
    public Prepared prepare(long showId, PromptResolver.Mode mode, ItemsSource items, TestSession session) {
        Show show = QuarkusTransaction.requiringNew().call(() -> Show.<Show>findByIdOptional(showId)
                .orElseThrow(() -> new NotFoundException("Show " + showId + " not found")));
        List<Item> candidates;
        if (items == ItemsSource.SESSION) {
            TestSession.Fetched fetched = session.fetched();
            if (fetched == null || fetched.items().isEmpty()) {
                throw new NoCandidatesException("No fetched items for show '" + show.name
                        + "' (or the test session expired). Fetch sources first");
            }
            candidates = fetched.items();
        } else {
            Instant since = Instant.now().minus(config.selection().firstRunWindow());
            candidates = QuarkusTransaction.requiringNew().call(() ->
                    Item.unusedCandidates(show.id, since, config.selection().maxCandidates()));
            if (candidates.isEmpty()) {
                throw new NoCandidatesException("No unused items from the last " + config.selection().firstRunWindow().toHours()
                        + "h for show '" + show.name + "'; run ingestion or fetch sources first");
            }
        }
        return new Prepared(show, candidates, resolver.resolve(show.id, mode));
    }

    public Result execute(Prepared p) {
        Show show = p.show();
        Selection selection = ranker.rank(models.get(show.effectiveRankerModel()), p.prompts(),
                show.name, show.language, show.focusPrompt, p.candidates());
        Outline outline = planner.plan(selection, show.targetDurationMinutes,
                calibration.wordsPerMinute(show.voiceId, show.lengthScale));
        Map<Long, Item> byId = p.candidates().stream().collect(Collectors.toMap(i -> i.id, Function.identity()));
        Script script = writer.write(models.get(show.writerModel), p.prompts(), show, outline, byId, LocalDate.now());
        return new Result(p.prompts().versions(PromptKey.values()), selection, outline,
                script.title(), script.description(), script.parts());
    }
}
```

`TestSession`: change the `dryRun` field, getter and setter type from `Object` to `PromptDryRun.Result`. In `TestViews.Session`, change `Object dryRun` to `PromptDryRun.Result dryRun`.

- [ ] **Step 4: Endpoint and cleanup**

In `TestResource`, add `@Inject PromptDryRun prompts;` and:

```java
    public record PromptStepRequest(String mode, String items) {}

    @POST
    @Path("/prompts")
    @Consumes(MediaType.APPLICATION_JSON)
    public RestResponse<TestViews.Session> dryRunPrompts(@RestPath long id, PromptStepRequest request) {
        requireShow(id);
        PromptResolver.Mode mode = parse(PromptResolver.Mode.class, request == null ? null : request.mode(), PromptResolver.Mode.DRAFT, "mode");
        PromptDryRun.ItemsSource items = parse(PromptDryRun.ItemsSource.class, request == null ? null : request.items(),
                PromptDryRun.ItemsSource.SESSION, "items");
        PromptDryRun.Prepared prepared = prompts.prepare(id, mode, items, store.session(id));
        runner.start(id, TestSession.Step.PROMPTS, s -> s.dryRun(prompts.execute(prepared)));
        return RestResponse.status(Response.Status.ACCEPTED, get(id));
    }

    static <E extends Enum<E>> E parse(Class<E> type, String value, E fallback, String field) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException(List.of("Unknown " + field + " '" + value + "'"));
        }
    }
```

(Check `InvalidRequestException`'s constructor in `api/InvalidRequestException.java`. It takes `List<String> errors`, as used in `ShowService`.)

Remove `PromptResource.dryRun` plus its `PromptDryRun` injection, `PromptViews.DryRunRequest`, `PromptPages.dryRun` plus `Templates.dryRun` plus the `PromptDryRun` injection, and `templates/PromptPages/dryRun.html`. Fix imports wherever `org.roncax.podcaster.prompts.PromptDryRun` was referenced (grep for it).

- [ ] **Step 5: Run the tests**

Run: `./mvnw -q test`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Test tab backend: prompt dry-run step with draft/production and fetched/DB items

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Voice dry-run (short clip)

**Files:**
- Create: `tts/WavConcat.java`, `testing/VoiceDryRun.java`
- Modify: `tts/AudioAssembler.java` (use `WavConcat`), `api/TestResource.java`
- Test: `src/test/java/org/roncax/podcaster/tts/WavConcatTest.java`, `src/test/java/org/roncax/podcaster/testing/VoiceTestApiTest.java`

**Interfaces:**
- Consumes: `TestSession.Clip`, `TestSession.dryRun()`, `TestRunner` (Tasks 4–5), `TtsEngine`, `ScriptChunker`, `TtsTextNormalizer`, `PodcasterConfig.tts()`
- Produces:
  - `WavConcat.concat(List<byte[]> wavs, List<Duration> pausesAfter, List<Integer> partOfChunk) → WavConcat.Joined(Wav format, byte[] pcm, List<Double> partStarts)`, with `durationSeconds()` and `toWavBytes()`
  - `VoiceDryRun.MAX_CHARS = 1000`
  - `VoiceDryRun.Request(String text, Integer part, String voiceId, Double lengthScale)`
  - `VoiceDryRun.Prepared(String text, String voiceId, double lengthScale, String language)`
  - `prepare(long showId, Request, TestSession) → Prepared` (throws `InvalidRequestException`)
  - `execute(Prepared) → TestSession.Clip`
  - `POST /api/shows/{id}/test/voice` and `GET /api/shows/{id}/test/voice.wav`

- [ ] **Step 1: Write the failing tests**

`WavConcatTest.java`:

```java
package org.roncax.podcaster.tts;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.support.TestAudio;

class WavConcatTest {
    @Test
    void joinsChunksWithPauses() {
        byte[] a = TestAudio.sineWav(0.5);
        WavConcat.Joined joined = WavConcat.concat(List.of(a, a), List.of(Duration.ofMillis(200), Duration.ZERO), List.of(0, 1));
        assertEquals(1.2, joined.durationSeconds(), 0.01);
        assertEquals(List.of(0.0, 0.7), joined.partStarts().stream().map(d -> Math.round(d * 100) / 100.0).toList());
        assertTrue(Wav.looksLikeWav(joined.toWavBytes()));
        assertEquals(joined.pcm().length, Wav.parse(joined.toWavBytes()).pcm().length);
    }

    @Test
    void rejectsMixedFormats() {
        byte[] mono = TestAudio.sineWav(0.1);
        byte[] stereo = TestAudio.wav(new byte[400], TestAudio.SAMPLE_RATE, 2, 16);
        assertThrows(IllegalStateException.class,
                () -> WavConcat.concat(List.of(mono, stereo), List.of(Duration.ZERO, Duration.ZERO), List.of(0, 0)));
    }
}
```

`VoiceTestApiTest.java` (QuarkusTest; reuse `TestApiTest`'s `api()` and `await` by copying them, since tests are independent):

```java
@QuarkusTest
@WithTestResource(WireMockResource.class)
@WithTestResource(org.roncax.podcaster.support.PostgresResource.class)
class VoiceTestApiTest {
    @InjectWireMock WireMockServer wm;

    @BeforeEach
    void setup() {
        TestData.cleanDb();
        WireMockResource.installDefaults(wm);
    }

    // api() and await(showId, step): same as TestApiTest

    @Test
    void synthesizesShortClipWithShowVoice() {
        Show show = TestData.show("voice");
        api().body(Map.of("text", "Buongiorno. Questa è una prova della voce.")).post("/api/shows/" + show.id + "/test/voice")
                .then().statusCode(202);
        JsonPath json = await(show.id, "VOICE");
        assertEquals("DONE", json.getString("steps.VOICE.status"), json.getString("steps.VOICE.error"));
        assertEquals("it_IT-paola-medium", json.getString("clip.voiceId"));
        assertTrue(json.getDouble("clip.durationSeconds") > 0.4);
        byte[] wav = api().get("/api/shows/" + show.id + "/test/voice.wav").then().statusCode(200)
                .contentType("audio/wav").extract().asByteArray();
        assertTrue(Wav.looksLikeWav(wav));
        wm.verify(postRequestedFor(urlEqualTo("/synthesize")).withRequestBody(containing("it_IT-paola-medium")));
        assertEquals(0L, (long) QuarkusTransaction.requiringNew().call(() -> VoiceCalibration.count()));
    }

    @Test
    void voiceOverrideIsUsed() {
        Show show = TestData.show("voice2");
        api().body(Map.of("text", "Hello there.", "voiceId", "en_US-lessac-medium", "lengthScale", 1.2))
                .post("/api/shows/" + show.id + "/test/voice").then().statusCode(202);
        assertEquals("en_US-lessac-medium", await(show.id, "VOICE").getString("clip.voiceId"));
        wm.verify(postRequestedFor(urlEqualTo("/synthesize")).withRequestBody(containing("en_US-lessac-medium")));
    }

    @Test
    void blankTextIs400() {
        Show show = TestData.show("vblank");
        api().body(Map.of("text", "   \n ")).post("/api/shows/" + show.id + "/test/voice").then().statusCode(400);
        wm.verify(0, postRequestedFor(urlEqualTo("/synthesize")));
    }

    @Test
    void validationErrors() {
        Show show = TestData.show("vbad");
        String base = "/api/shows/" + show.id + "/test/voice";
        api().body(Map.of("text", "a".repeat(1001))).post(base).then().statusCode(400)
                .body("details[0]", containsString("1000"));
        api().body(Map.of("part", 0)).post(base).then().statusCode(400)
                .body("details[0]", containsString("Run the prompt dry-run first"));
        api().body(Map.of("text", "Ciao.", "voiceId", "xx_nope")).post(base).then().statusCode(400);
        api().body(Map.of("text", "Ciao.", "lengthScale", 3.0)).post(base).then().statusCode(400);
        api().get("/api/shows/" + show.id + "/test/voice.wav").then().statusCode(404);
    }

    @Test
    void piperFailureMarksStepFailed() {
        Show show = TestData.show("vdown");
        wm.stubFor(post("/synthesize").willReturn(aResponse().withStatus(500).withBody("boom")));
        api().body(Map.of("text", "Ciao.")).post("/api/shows/" + show.id + "/test/voice").then().statusCode(202);
        assertEquals("FAILED", await(show.id, "VOICE").getString("steps.VOICE.status"));
    }
}
```

(Check how `PiperHttpTtsEngine` sends the voice: JSON body or query parameter. Adjust `withRequestBody(containing(...))` to `withQueryParam` if needed. Also check whether it retries on 500. If it does, `piperFailureMarksStepFailed` may take the retry delays; that's acceptable within `await`'s 30s.)

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw -q test -Dtest='WavConcatTest,VoiceTestApiTest'`
Expected: compilation failure.

- [ ] **Step 3: Implement `WavConcat` and use it in `AudioAssembler`**

```java
package org.roncax.podcaster.tts;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Joins PCM WAV chunks with silence between them. */
public final class WavConcat {
    public record Joined(Wav format, byte[] pcm, List<Double> partStarts) {
        public double durationSeconds() { return (double) pcm.length / format.bytesPerSecond(); }

        public byte[] toWavBytes() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.writeBytes(Wav.header(format.sampleRate(), format.channels(), format.bitsPerSample(), pcm.length));
            out.writeBytes(pcm);
            return out.toByteArray();
        }
    }

    private WavConcat() {}

    public static Joined concat(List<byte[]> wavs, List<Duration> pausesAfter, List<Integer> partOfChunk) {
        if (wavs.isEmpty()) throw new IllegalArgumentException("No audio chunks to assemble");
        Wav first = null;
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        List<Double> partStarts = new ArrayList<>();
        for (int i = 0; i < wavs.size(); i++) {
            Wav wav = Wav.parse(wavs.get(i));
            if (first == null) {
                first = wav;
            } else if (!first.sameFormat(wav)) {
                throw new IllegalStateException("Chunk " + i + " has a different audio format");
            }
            if (i == 0 || !partOfChunk.get(i).equals(partOfChunk.get(i - 1))) {
                partStarts.add((double) pcm.size() / first.bytesPerSecond());
            }
            pcm.writeBytes(wav.pcm());
            long silence = Math.round(pausesAfter.get(i).toMillis() / 1000.0 * first.bytesPerSecond());
            silence -= silence % first.blockAlign();
            pcm.writeBytes(new byte[(int) silence]);
        }
        return new Joined(first, pcm.toByteArray(), List.copyOf(partStarts));
    }
}
```

In `AudioAssembler.assemble`, read each path with `Files.readAllBytes` into a `List<byte[]>` and call `WavConcat.concat`. Write `joined.toWavBytes()` to the temp `.wav` file, and use `joined.durationSeconds()` and `joined.partStarts()`. Keep the existing error message for mismatched formats compatible with `AudioAssemblerTest`: if that test asserts the chunk file name in the message, catch the `IllegalStateException` in `assemble` and rethrow it with `wavChunks.get(i).getFileName()`, or relax the assertion. Check `AudioAssemblerTest` first.

- [ ] **Step 4: Implement `VoiceDryRun`**

```java
package org.roncax.podcaster.testing;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.roncax.podcaster.api.InvalidRequestException;
import org.roncax.podcaster.config.PodcasterConfig;
import org.roncax.podcaster.domain.Show;
import org.roncax.podcaster.generation.ScriptChunker;
import org.roncax.podcaster.generation.TtsChunk;
import org.roncax.podcaster.generation.TtsTextNormalizer;
import org.roncax.podcaster.tts.*;

/** Synthesizes a short clip with a show's voice (or an override). Stores nothing but the session clip. */
@ApplicationScoped
public class VoiceDryRun {
    public static final int MAX_CHARS = 1000;

    public record Request(String text, Integer part, String voiceId, Double lengthScale) {}
    public record Prepared(String text, String voiceId, double lengthScale, String language) {}

    @Inject TtsEngine tts;
    @Inject PodcasterConfig config;

    public Prepared prepare(long showId, Request r, TestSession session) {
        Show show = QuarkusTransaction.requiringNew().call(() -> Show.<Show>findByIdOptional(showId).orElseThrow(NotFoundException::new));
        List<String> errors = new ArrayList<>();
        String text = r == null ? null : r.text();
        if (r != null && r.part() != null) {
            PromptDryRun.Result dryRun = session.dryRun();
            if (dryRun == null) errors.add("No script parts yet. Run the prompt dry-run first");
            else if (r.part() < 0 || r.part() >= dryRun.scriptParts().size()) errors.add("Unknown script part " + r.part());
            else text = dryRun.scriptParts().get(r.part());
        }
        if (errors.isEmpty() && (text == null || text.isBlank())) errors.add("Text is empty");
        if (errors.isEmpty() && text.length() > MAX_CHARS) errors.add("Text is " + text.length() + " characters; the limit is " + MAX_CHARS);
        String voice = r != null && r.voiceId() != null && !r.voiceId().isBlank() ? r.voiceId().trim() : show.voiceId;
        double scale = r != null && r.lengthScale() != null ? r.lengthScale() : show.lengthScale;
        if (scale < 0.5 || scale > 2.0) errors.add("lengthScale must be between 0.5 and 2.0");
        if (!voice.equals(show.voiceId)) {
            try {
                Set<String> voices = tts.voices();
                if (!voices.contains(voice)) errors.add("voiceId '" + voice + "' is not installed in Piper; available: " + voices);
            } catch (Exception e) {
                // Piper unreachable: the step itself will report it
            }
        }
        if (!errors.isEmpty()) throw new InvalidRequestException(errors);
        return new Prepared(text.trim(), voice, scale, show.language);
    }

    public TestSession.Clip execute(Prepared p) throws Exception {
        PodcasterConfig.Tts cfg = config.tts();
        String text = TtsTextNormalizer.normalize(p.text(), p.language());
        List<TtsChunk> chunks = ScriptChunker.chunk(List.of(text), cfg.chunkChars(), cfg.chunkPause(), cfg.segmentPause());
        VoiceConfig voice = new VoiceConfig(p.voiceId(), p.lengthScale());
        List<byte[]> wavs = new ArrayList<>();
        for (TtsChunk chunk : chunks) wavs.add(tts.synthesize(chunk.text(), voice));
        WavConcat.Joined joined = WavConcat.concat(wavs, chunks.stream().map(TtsChunk::pauseAfter).toList(),
                chunks.stream().map(TtsChunk::part).toList());
        int words = text.isBlank() ? 0 : text.trim().split("\\s+").length;
        double wpm = joined.durationSeconds() > 0 ? words / (joined.durationSeconds() / 60.0) : 0;
        return new TestSession.Clip(joined.toWavBytes(), joined.durationSeconds(), wpm, p.voiceId(), p.lengthScale());
    }
}
```

(Check that `InvalidRequestException` is public and its constructor is accessible from the `testing` package. If it's package-private, make it public.)

- [ ] **Step 5: Endpoints**

In `TestResource`, add `@Inject VoiceDryRun voice;` and:

```java
    @POST
    @Path("/voice")
    @Consumes(MediaType.APPLICATION_JSON)
    public RestResponse<TestViews.Session> voice(@RestPath long id, VoiceDryRun.Request request) {
        requireShow(id);
        VoiceDryRun.Prepared prepared = voice.prepare(id, request, store.session(id));
        runner.start(id, TestSession.Step.VOICE, s -> s.clip(voice.execute(prepared)));
        return RestResponse.status(Response.Status.ACCEPTED, get(id));
    }

    @GET
    @Path("/voice.wav")
    @Produces("audio/wav")
    public byte[] clip(@RestPath long id) {
        return store.find(id).map(TestSession::clip).map(TestSession.Clip::wav).orElseThrow(NotFoundException::new);
    }
```

- [ ] **Step 6: Run the tests**

Run: `./mvnw -q test`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "Test tab backend: voice dry-run producing a short WAV clip

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Admin Test tab UI

**Files:**
- Create: `admin/TestPages.java`, `templates/TestPages/panel.html`
- Modify: `admin/ShowPages.java` (`TABS` plus the `test` tab), `templates/ShowPages/detail.html`, `admin/ShowPromptPages.java` (set `testHref`), `src/main/resources/web/app/app.js` ("Voice this")
- Test: `src/test/java/org/roncax/podcaster/admin/TestPagesTest.java`

**Interfaces:**
- Consumes: `TestSessionStore`, `TestRunner`, `SourceDryRun`, `PromptDryRun`, `VoiceDryRun`, `TestViews.of` (Tasks 4–6), `AdminSupport.voices()`
- Produces:
  - `GET /admin/shows/{id}/test` → panel fragment (htmx polling target)
  - `POST /admin/shows/{id}/test/sources|prompts|voice` → panel fragment for htmx, else a 303 to `?tab=test`
  - `GET /admin/shows/{id}/test/voice.wav`

- [ ] **Step 1: Write the failing tests**

`TestPagesTest.java`: copy the `admin()` request spec from `PromptAdminTest` (it logs in or uses the cookie in the same way), and the setup from `TestApiTest`, including `showWithFeed`.

```java
    @Test
    void testTabRendersIdlePanel() {
        Show show = TestData.show("tt");
        admin().get("/admin/shows/" + show.id + "?tab=test").then().statusCode(200)
                .body(containsString("Fetch sources")).body(containsString("Run dry-run")).body(containsString("Synthesize"))
                .body(containsString("Fetch sources first"));
    }

    @Test
    void fullChainThroughAdminForms() {
        Show show = showWithFeed("ttc");
        String base = "/admin/shows/" + show.id + "/test";
        admin().header("HX-Request", "true").post(base + "/sources").then().statusCode(200).body(containsString("hx-trigger=\"every 2s\""));
        awaitIdle(show.id);
        admin().get(base).then().statusCode(200).body(containsString("First story"));

        admin().header("HX-Request", "true").formParam("mode", "draft").formParam("items", "session").post(base + "/prompts").then().statusCode(200);
        awaitIdle(show.id);
        admin().get(base).then().statusCode(200).body(containsString("Test episode")).body(containsString("data-voice-part"));

        admin().header("HX-Request", "true").formParam("text", "Ciao a tutti.").post(base + "/voice").then().statusCode(200);
        awaitIdle(show.id);
        admin().get(base).then().statusCode(200).body(containsString("<audio"));
        admin().get(base + "/voice.wav").then().statusCode(200).contentType("audio/wav");
    }

    @Test
    void validationErrorIsShownInPanel() {
        Show show = TestData.show("tterr");
        admin().header("HX-Request", "true").formParam("text", "a".repeat(1001)).post("/admin/shows/" + show.id + "/test/voice")
                .then().statusCode(200).body(containsString("the limit is 1000"));
    }

    @Test
    void showPromptPageLinksToTestTab() {
        Show show = TestData.show("ttl");
        admin().get("/admin/shows/" + show.id + "/prompts/rank").then().statusCode(200)
                .body(containsString("/admin/shows/" + show.id + "?tab=test"));
    }

    void awaitIdle(long showId) {
        TestSessionStore store = io.quarkus.arc.Arc.container().instance(TestSessionStore.class).get();
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (store.find(showId).map(TestSession::running).orElse(false)) {
            if (Instant.now().isAfter(deadline)) throw new AssertionError("still running");
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw -q test -Dtest=TestPagesTest`
Expected: FAIL.

- [ ] **Step 3: Implement `TestPages`**

```java
package org.roncax.podcaster.admin;

// imports: CheckedTemplate, TemplateInstance, Inject, jakarta.ws.rs.*, MediaType, Response, URI, List,
// RestForm, RestHeader, RestPath, api.InvalidRequestException, api.TestViews, prompts.NoCandidatesException,
// prompts.PromptResolver, testing.*

@Path("/admin/shows/{id}/test")
@Produces(MediaType.TEXT_HTML)
public class TestPages {
    @CheckedTemplate
    static class Templates {
        static native TemplateInstance panel(long showId, TestViews.Session session, boolean running, List<String> errors,
                                             java.util.Set<String> voices, String defaultVoice, double defaultLengthScale, int maxChars);
    }

    @Inject TestSessionStore store;
    @Inject TestRunner runner;
    @Inject SourceDryRun sources;
    @Inject PromptDryRun prompts;
    @Inject VoiceDryRun voice;
    @Inject AdminSupport support;

    @GET
    public TemplateInstance panel(@RestPath long id) {
        return panel(id, List.of());
    }

    @POST
    @Path("/sources")
    public Response fetch(@RestPath long id, @RestHeader("HX-Request") String htmx) {
        return start(id, htmx, () -> runner.start(id, TestSession.Step.SOURCES, s -> s.fetched(sources.run(id))));
    }

    @POST
    @Path("/prompts")
    public Response dryRun(@RestPath long id, @RestForm String mode, @RestForm String items, @RestHeader("HX-Request") String htmx) {
        return start(id, htmx, () -> {
            PromptResolver.Mode m = "production".equals(mode) ? PromptResolver.Mode.PRODUCTION : PromptResolver.Mode.DRAFT;
            PromptDryRun.ItemsSource src = "database".equals(items) ? PromptDryRun.ItemsSource.DATABASE : PromptDryRun.ItemsSource.SESSION;
            PromptDryRun.Prepared prepared = prompts.prepare(id, m, src, store.session(id));
            runner.start(id, TestSession.Step.PROMPTS, s -> s.dryRun(prompts.execute(prepared)));
        });
    }

    @POST
    @Path("/voice")
    public Response synthesize(@RestPath long id, @RestForm String text, @RestForm String voiceId, @RestForm String lengthScale,
                               @RestHeader("HX-Request") String htmx) {
        return start(id, htmx, () -> {
            Double scale = lengthScale == null || lengthScale.isBlank() ? null : Double.valueOf(lengthScale.trim());
            VoiceDryRun.Prepared prepared = voice.prepare(id, new VoiceDryRun.Request(text, null, voiceId, scale), store.session(id));
            runner.start(id, TestSession.Step.VOICE, s -> s.clip(voice.execute(prepared)));
        });
    }

    @GET
    @Path("/voice.wav")
    @Produces("audio/wav")
    public byte[] clip(@RestPath long id) {
        return store.find(id).map(TestSession::clip).map(TestSession.Clip::wav).orElseThrow(NotFoundException::new);
    }

    private Response start(long id, String htmx, Runnable action) {
        List<String> errors = List.of();
        try {
            action.run();
        } catch (InvalidRequestException e) {
            errors = e.errors();
        } catch (NoCandidatesException | TestStepBusyException | NumberFormatException e) {
            errors = List.of(e.getMessage());
        }
        if (htmx == null && errors.isEmpty()) return Response.seeOther(URI.create("/admin/shows/" + id + "?tab=test")).build();
        return Response.ok(panel(id, errors)).build();
    }

    TemplateInstance panel(long id, List<String> errors) {
        Show show = QuarkusTransaction.requiringNew().call(() -> Show.<Show>findByIdOptional(id).orElseThrow(NotFoundException::new));
        TestViews.Session session = store.find(id).map(s -> TestViews.of(s, "/admin/shows/" + id + "/test/voice.wav"))
                .orElseGet(() -> TestViews.empty(id));
        boolean running = store.find(id).map(TestSession::running).orElse(false);
        return Templates.panel(id, session, running, errors, support.voices(), show.voiceId, show.lengthScale, VoiceDryRun.MAX_CHARS);
    }
}
```

A non-htmx form POST that fails validation renders only the panel fragment. That's acceptable, because the panel is a complete readable page section. Alternatively, wrap it with the layout; keep it simple.

- [ ] **Step 4: Panel template**

`templates/TestPages/panel.html`. It uses the same classes as `ShowPages/live.html` and the Sources tab tables:

```html
<section id="test-panel" class="flex flex-col gap-4" aria-live="polite"
  {#if running}hx-get="/admin/shows/{showId}/test" hx-trigger="every 2s" hx-swap="outerHTML"{/if}>
  {#if errors}<div role="alert" class="rounded-xl bg-fail-bg p-3 text-sm text-fail"><ul class="m-0 pl-5">{#for e in errors}<li>{e}</li>{/for}</ul></div>{/if}

  <div class="card">
    <div class="flex flex-wrap items-center gap-3">
      <h2 class="m-0 text-base font-semibold">1 · Sources</h2>
      <span class="mono text-xs text-muted">{session.steps.get('SOURCES').status}</span>
      <form class="ml-auto" method="post" action="/admin/shows/{showId}/test/sources" hx-post="/admin/shows/{showId}/test/sources" hx-target="#test-panel" hx-swap="outerHTML">
        <button type="submit" class="btn btn-secondary" {#if running}disabled{/if}>Fetch sources</button>
      </form>
    </div>
    <p class="m-0 text-xs text-muted">Fetches every enabled source like a real run, but stores nothing.</p>
    {#if session.steps.get('SOURCES').error}<p class="m-0 rounded-lg bg-fail-bg px-3 py-2 text-sm text-fail">{session.steps.get('SOURCES').error}</p>{/if}
    {#for src in session.sources}
    <details class="border-t border-line pt-2">
      <summary class="cursor-pointer text-sm"><strong>{src.label}</strong> · {#if src.error}<span class="text-fail">{src.error}</span>{#else}{src.items} items{/if}</summary>
      <ul class="m-0 mt-2 flex flex-col gap-1 pl-5 text-sm">
        {#for it in session.items}{#if it.sourceId == src.sourceId}
        <li><a href="{it.url}" class="link" rel="noreferrer">{it.title}</a> <span class="mono text-xs text-muted">#{it.id} · {#if it.hasFullText}full text{#else}summary only{/if} · {it.textLength} chars</span></li>
        {/if}{/for}
      </ul>
    </details>
    {/for}
  </div>

  <div class="card">
    <div class="flex flex-wrap items-center gap-3">
      <h2 class="m-0 text-base font-semibold">2 · Prompts</h2>
      <span class="mono text-xs text-muted">{session.steps.get('PROMPTS').status}</span>
    </div>
    <form method="post" action="/admin/shows/{showId}/test/prompts" hx-post="/admin/shows/{showId}/test/prompts" hx-target="#test-panel" hx-swap="outerHTML" class="flex flex-wrap items-end gap-2">
      <label class="field"><span class="field-label">Prompts</span><select class="field-input" name="mode"><option value="draft">Draft</option><option value="production">Production</option></select></label>
      <label class="field"><span class="field-label">Items</span><select class="field-input" name="items">
        <option value="session" {#if session.items.isEmpty()}disabled{/if}>Fetched in step 1</option>
        <option value="database" {#if session.items.isEmpty()}selected{/if}>Unused in database</option></select></label>
      <button type="submit" class="btn btn-secondary" {#if running}disabled{/if}>Run dry-run</button>
      {#if session.items.isEmpty()}<span class="text-xs text-muted">Fetch sources first to test on fresh items.</span>{/if}
    </form>
    {#if session.steps.get('PROMPTS').error}<p class="m-0 rounded-lg bg-fail-bg px-3 py-2 text-sm text-fail">{session.steps.get('PROMPTS').error}</p>{/if}
    {#if session.dryRun}
    <div class="flex flex-col gap-2 rounded-xl bg-ground p-4 text-sm">
      <p class="m-0"><strong>{session.dryRun.title}</strong> <span class="mono text-xs text-muted">· prompt versions {session.dryRun.promptVersions}</span></p>
      <p class="m-0 whitespace-pre-wrap">{session.dryRun.description}</p>
      <ol class="m-0 pl-5">{#for seg in session.dryRun.outline.segments}<li>{seg.headline} <span class="mono text-xs text-muted">{seg.words} words · items {seg.itemIds}</span></li>{/for}</ol>
      {#for part in session.dryRun.scriptParts}
      <div class="flex flex-col gap-1 border-t border-line pt-2">
        <p class="m-0 leading-relaxed whitespace-pre-wrap" id="part-{part_index}">{part}</p>
        <div><button type="button" class="btn btn-secondary btn-sm" data-voice-part="part-{part_index}">Voice this</button></div>
      </div>
      {/for}
    </div>
    {/if}
  </div>

  <div class="card">
    <div class="flex flex-wrap items-center gap-3">
      <h2 class="m-0 text-base font-semibold">3 · Voice</h2>
      <span class="mono text-xs text-muted">{session.steps.get('VOICE').status}</span>
    </div>
    <form method="post" action="/admin/shows/{showId}/test/voice" hx-post="/admin/shows/{showId}/test/voice" hx-target="#test-panel" hx-swap="outerHTML" class="flex flex-col gap-2">
      <label class="field"><span class="field-label">Text (max {maxChars} characters)</span>
        <textarea class="field-input" name="text" rows="5" maxlength="{maxChars}" data-voice-text></textarea></label>
      <div class="flex flex-wrap items-end gap-2">
        <label class="field"><span class="field-label">Voice</span><select class="field-input" name="voiceId">{#for v in voices}<option value="{v}" {#if v == defaultVoice}selected{/if}>{v}</option>{/for}</select></label>
        <label class="field"><span class="field-label">Length scale</span><input class="field-input" name="lengthScale" type="number" step="0.05" min="0.5" max="2.0" value="{defaultLengthScale}"></label>
        <button type="submit" class="btn btn-secondary" {#if running}disabled{/if}>Synthesize</button>
      </div>
    </form>
    {#if session.steps.get('VOICE').error}<p class="m-0 rounded-lg bg-fail-bg px-3 py-2 text-sm text-fail">{session.steps.get('VOICE').error}</p>{/if}
    {#if session.clip}
    <div class="flex flex-wrap items-center gap-3">
      <audio controls preload="auto" src="{session.clip.audioUrl}?t={session.clip.durationSeconds}"></audio>
      <span class="mono text-xs text-muted">{session.clip.voiceId} · ×{session.clip.lengthScale} · {session.clip.durationSeconds}s · {session.clip.wordsPerMinute} wpm</span>
    </div>
    {/if}
  </div>
</section>
```

Qute notes:
- `session.steps` is a `Map<String, StepView>` (Task 4), so `session.steps.get('SOURCES')` works.
- Qute type-safe templates need numbers formatted: use `{str:format('%.1f', session.clip.durationSeconds)}` only if the project already registers such an extension (check `TemplateTypes`/`CollectionExtensions`); otherwise round in `ClipView` (e.g. store `Math.round(x * 10) / 10.0`).
- If `{#if session.items.isEmpty()}` fails type checking, use `session.items.isEmpty` or a template extension already in `CollectionExtensions`.

- [ ] **Step 5: Wire the tab, the "Voice this" button and the prompt-page link**

`ShowPages`: add `"test"` to `TABS`. Render the panel server-side, so the tab works without JS and `testTabRendersIdlePanel` sees it in the initial HTML:
- inject `TestPages testPages`
- in `detailTemplate`, compute `String testPanel = t.equals("test") ? testPages.panel(show.id, List.of()).render() : null;`
- add a `String testPanel` parameter to `Templates.detail`

In `templates/ShowPages/detail.html`, add the tab link `Test` (`?tab=test`) last, and:

```html
{#if tab == 'test'}{testPanel.raw}{/if}
```

(`.raw` is Qute's built-in unescaped output; the panel is our own template, so its contents are already escaped.)

`app.js`: add delegated handling, following the file's existing style:

```js
document.addEventListener('click', (event) => {
  const button = event.target.closest('[data-voice-part]');
  if (!button) return;
  const part = document.getElementById(button.dataset.voicePart);
  const target = document.querySelector('[data-voice-text]');
  if (!part || !target) return;
  target.value = part.textContent.trim().slice(0, Number(target.maxLength) || 1000);
  target.focus();
});
```

`ShowPromptPages.page(...)`: set `testHref` to `"/admin/shows/" + show.id + "?tab=test"`. The panel's prompt mode defaults to Draft, which matches "Test this draft".

- [ ] **Step 6: Run the tests**

Run: `./mvnw -q test`
Expected: PASS

- [ ] **Step 7: Check it manually in the browser**

Run the app per the `run` skill or the README (`./mvnw quarkus:dev` with the dev Postgres from `docker-compose.yml`). Open a show, go to Test, and click Fetch sources → Run dry-run → Voice this → Synthesize, then play the clip. Also check the show Prompts tab → a key → New version → Test this draft, and the Default prompts page. Fix any layout issues.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "Admin: Test tab chaining source fetch, prompt dry-run and voice clip

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Docs

**Files:**
- Modify: `README.md` (Prompts section and admin UI paragraph), `docs/BACKLOG.md`

- [ ] **Step 1: Update the README**

Replace the `## Prompts` bullets with:
- Every show owns its prompts (`rank`, `segment`, `framing`, `json_repair`) as immutable versions with `production` (used by runs) and `draft` labels: show page → Prompts, or `/api/shows/{id}/prompts`.
- New shows copy the **default prompts** (`/admin/prompts`, `/api/prompts`). Editing a default never changes existing shows; use **Reset to default** on a show's prompt to pull it in as a draft.
- Keep the existing bullets on template syntax and episode lineage.

Add a `## Test tab` section:
- the show page → Test tab dry-runs (1) fetching all sources, (2) rank/outline/script with draft or production prompts on the fetched or unused DB items, and (3) a clip of up to 1,000 characters in the show's voice or another one
- nothing is stored, and results live in memory for 30 minutes
- API: `/api/shows/{id}/test` (`GET`, `POST /sources`, `POST /prompts {mode, items}`, `POST /voice {text|part, voiceId?, lengthScale?}`, `GET /voice.wav`)

In the admin UI paragraph, change "prompt versions with diff, editor and dry-run" to "per-show prompt versions with diff and editor, default prompts, and a Test tab".

- [ ] **Step 2: Backlog**

Add to the `Done:` line: "per-show prompts and show Test tab — 2026-10-17".

- [ ] **Step 3: Commit**

```bash
git add README.md docs/BACKLOG.md
git commit -m "Docs: per-show prompts and the Test tab

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
