# Prompt Registry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move every LLM prompt into the database with immutable versions, `production`/`draft` labels, per-show overrides, episode lineage and a script-only dry-run, manageable through the REST API and admin UI.

**Architecture:** New `org.roncax.podcaster.prompts` package. Schema comes from Flyway `V2`; seed text lives in classpath files and is inserted by a startup `PromptSeeder`. Prompt bodies are Qute templates rendered by a standalone, sandboxed Qute `Engine` (no `inject:` or `config:` namespaces). A resolved `PromptSet` is passed into `StoryRanker`, `ScriptWriter` and `JsonChat`. The fixed `TASK:` header and JSON contract are supplied by code; the contract enters the body through a required `{contract}` variable.

**Tech Stack:** Java 25, Quarkus 3.40.1, Hibernate ORM Panache, Flyway, Qute (standalone `io.quarkus.qute.Engine`, already on the classpath via `quarkus-rest-qute`), Quarkus REST, embedded PostgreSQL + WireMock in tests.

**Spec:** `docs/superpowers/specs/2026-10-06-prompt-registry-design.md` (parent: `docs/superpowers/specs/2026-10-06-podcaster-design.md`)

## Global Constraints

- Work on branch `feature/db-prompts`. Run Maven with JDK 25: `export JAVA_HOME=$HOME/.jdks/temurin-25 PATH=$HOME/.jdks/temurin-25/bin:$HOME/.local/bin:$PATH` before `./mvnw`.
- Prompt keys are exactly `rank`, `segment`, `framing`, `json_repair`. Labels are exactly `production` and `draft`.
- `prompt_versions` rows are never updated or deleted by application code (entity is `@Immutable`; no update/delete endpoint).
- After seeding, every rendered prompt is byte-for-byte identical to the pre-change text (golden test against `LegacyPrompts`).
- The `TASK: <NAME>` first line and the JSON contract text are code; the body must contain `{contract}` for `rank` and `framing` (required variable).
- Templates may not use namespaces (`inject:`, `config:`, …); only the allowed variables of their key.
- Resolution per key: show override → label (`production` for runs, `draft` for dry-run).
- Every episode records `promptVersions` as `{"rank":n,"json_repair":n,"segment":n,"framing":n}`.
- All new `@QuarkusTest` classes are annotated `@WithTestResource(WireMockResource.class)` and `@WithTestResource(org.roncax.podcaster.support.PostgresResource.class)`.
- No real LLM, Piper or network calls in tests (`FakeChatModel`, `FakeChatModelRegistry`, WireMock).
- Commit messages end with the two trailer lines `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01LbcoMupq1VXnfzdqroFmyp`.

## Review Focus

1. **A template that tries to read secrets** (`{config:quarkus.datasource.password}`, `{inject:someBean}`) must be rejected on save, and must not render even if inserted directly into the DB. Test: Task 2 `PromptRendererTest.rejectsNamespaces` + `standaloneEngineHasNoNamespaces`.
2. **A literal brace or a stray `{` in an edited prompt** (e.g. pasting example JSON) must produce a clear 400 validation error, not a stored version that fails every run. Test: Task 2 `PromptRendererTest.reportsSyntaxErrors`.
3. **Promoting while a run is in flight** must not mix versions inside one stage, and a retry after a promotion must record the versions it actually used. Test: Task 4 `PromptPipelineTest.retryAfterPromotionRecordsNewVersions`.
4. **Deleting a show with pinned prompts** must not fail and must not delete any prompt version. Test: Task 3 `PromptRegistryTest.deletingShowRemovesOnlyItsOverrides`.
5. **Dry-run on a show with no fresh items** must answer 409 with a clear message instead of calling the LLM. Test: Task 5 `PromptApiTest.dryRunWithoutItemsIs409`.

---

## File Structure

```
src/main/resources/db/migration/V2__prompt_registry.sql      tables + episodes.prompt_versions
src/main/resources/prompts/{rank,segment,framing,json_repair}.txt   v1 seed bodies (Qute)
src/main/java/org/roncax/podcaster/prompts/
  PromptKey.java            enum: db key, TASK header, variables, required, contract, seed body
  PromptLabel.java          enum: PRODUCTION("production"), DRAFT("draft")
  Prompt.java               entity  (prompts)
  PromptVersion.java        entity  (prompt_versions, @Immutable)
  PromptLabelAssignment.java entity (prompt_labels)
  ShowPromptOverride.java   entity  (show_prompt_overrides)
  PromptSeeder.java         startup: insert missing prompts + v1 + labels
  PromptRenderer.java       sandboxed Qute engine: render(), validate()
  PromptSet.java            resolved versions; render(key, vars) adds header + contract
  PromptResolver.java       resolve(showId, Mode) with cache + invalidate()
  PromptRegistry.java       createVersion / setLabel / pin / unpin / queries
  InvalidPromptException.java
  PromptDryRun.java         script-only dry-run (no persistence)
  NoCandidatesException.java
  LineDiff.java             line diff for the admin UI
src/main/java/org/roncax/podcaster/api/PromptResource.java       /api/prompts…
src/main/java/org/roncax/podcaster/api/ShowPromptResource.java   /api/shows/{id}/prompts…
src/main/resources/templates/AdminResource/{prompts,prompt,dryRun}.html
Modify: generation/Prompts.java, generation/StoryRanker.java, generation/ScriptWriter.java,
        llm/JsonChat.java, runs/SelectStage.java, runs/ScriptStage.java, domain/Episode.java,
        domain/Item.java, api/ApiExceptionMappers.java, admin/AdminResource.java,
        templates/base.html, templates/AdminResource/show.html, README.md
Tests: src/test/java/org/roncax/podcaster/prompts/*Test.java, support/LegacyPrompts.java,
       support/TestPrompts.java, support/TestData.java (resetPrompts), api/PromptApiTest.java,
       admin/PromptAdminTest.java, plus updates to StoryRankerTest, ScriptWriterTest
```

---

### Task 1: Schema, entities, seed prompts

**Files:**
- Create: `src/main/resources/db/migration/V2__prompt_registry.sql`
- Create: `src/main/resources/prompts/rank.txt`, `segment.txt`, `framing.txt`, `json_repair.txt`
- Create: `src/main/java/org/roncax/podcaster/prompts/{PromptKey,PromptLabel,Prompt,PromptVersion,PromptLabelAssignment,ShowPromptOverride,PromptSeeder}.java`
- Modify: `src/main/java/org/roncax/podcaster/domain/Episode.java` (add `promptVersions`)
- Modify: `src/test/java/org/roncax/podcaster/support/TestData.java` (add `resetPrompts()`, call it from `cleanDb()`)
- Test: `src/test/java/org/roncax/podcaster/prompts/PromptSeederTest.java`

**Interfaces:**
- Produces:
  - `enum PromptKey { RANK, SEGMENT, FRAMING, JSON_REPAIR }` with `dbKey() → String`, `header() → String` (null for JSON_REPAIR), `variables() → Set<String>`, `required() → Set<String>`, `contract() → String` (null for SEGMENT, JSON_REPAIR), `description() → String`, `seedBody() → String`, `static fromDb(String) → Optional<PromptKey>`.
  - `enum PromptLabel { PRODUCTION, DRAFT }` with `dbValue() → "production"|"draft"`, `static fromDb(String) → Optional<PromptLabel>`.
  - Entities (public fields, Panache): `Prompt{String key; String description}`, `PromptVersion{Long id; String promptKey; int version; String body; String note; Instant createdAt}`, `PromptLabelAssignment{Long id; String promptKey; String label; Long versionId}`, `ShowPromptOverride{Long id; Long showId; String promptKey; Long versionId}`.
  - `Episode.promptVersions` (`Map<String,Integer>`, JSONB).
  - `PromptSeeder#seed()` — idempotent; runs on startup.
  - Test: `TestData.resetPrompts()` — deletes overrides, labels, versions, then re-seeds and invalidates the resolver cache (the resolver is added in Task 3; until then it only re-seeds).

- [ ] **Step 1: Write the migration**

```sql
create table prompts (
    key varchar(50) primary key,
    description text not null
);

create table prompt_versions (
    id bigserial primary key,
    prompt_key varchar(50) not null references prompts(key),
    version int not null,
    body text not null,
    note text,
    created_at timestamptz not null default now(),
    unique (prompt_key, version)
);

create table prompt_labels (
    id bigserial primary key,
    prompt_key varchar(50) not null references prompts(key),
    label varchar(20) not null check (label in ('production', 'draft')),
    version_id bigint not null references prompt_versions(id),
    unique (prompt_key, label)
);

create table show_prompt_overrides (
    id bigserial primary key,
    show_id bigint not null references shows(id) on delete cascade,
    prompt_key varchar(50) not null references prompts(key),
    version_id bigint not null references prompt_versions(id),
    unique (show_id, prompt_key)
);

alter table episodes add column prompt_versions jsonb;
```

- [ ] **Step 2: Write the seed bodies**

Exact content matters (golden test in Task 2). Write them with a heredoc so trailing newlines are exact. `rank.txt`, `segment.txt`, `framing.txt` end with exactly one newline; `json_repair.txt` has **no** trailing newline.

```bash
mkdir -p src/main/resources/prompts
cat > src/main/resources/prompts/rank.txt <<'EOF'
You are the editor of the news podcast "{showName}".
Below are news items collected for the next episode. Group items that report the same story into clusters, then rate each cluster's importance for the listener from 1 (minor) to 10 (major).
{#if focus}Editorial focus from the show owner: {focus}{/if}
Rules:
- Use only item ids from the list; an item belongs to at most one cluster.
- Leave out items that are not news (ads, promotions, games, horoscopes).
- Write every headline in {language}.
{contract}

ITEMS:
{items}
EOF
cat > src/main/resources/prompts/segment.txt <<'EOF'
You write one segment of a spoken news podcast in {language}. It will be read aloud by a single narrator using text-to-speech.
Segment topic: {headline}
Target length: about {words} words.
Style:
- Plain spoken prose only: no markdown, lists, headings, URLs, emojis or stage directions.
- Explain the story clearly for a listener who has not read the articles; name the sources when attributing claims.
- Write numbers, dates and abbreviations the way a narrator would say them.
- Do not greet the listener or close the episode: this segment sits in the middle of the episode.
{#if previousTail}The previous segment ended with: "{previousTail}"
Open with a short, natural transition from it.{#else}This is the first story of the episode.{/if}
{#if focus}Editorial focus from the show owner: {focus}{/if}
SOURCES:
{sources}

Write the segment now, in {language}.
EOF
cat > src/main/resources/prompts/framing.txt <<'EOF'
You write the opening and closing of an episode of the spoken news podcast "{showName}", in {language}, read by a single narrator using text-to-speech. Episode date: {date}.
The episode covers these stories, in order:
{stories}

Return ONLY a JSON object, no prose, with these fields:
- "title": short episode title, at most 80 characters
- "description": 2-3 sentences of show notes
- "intro": spoken opening of 80-120 words that greets the listener, says the date and previews the stories
- "outro": spoken closing of 50-80 words
intro and outro are plain spoken prose: no markdown, URLs or emojis.
{contract}
EOF
printf '%s' 'Your previous reply could not be parsed as JSON ({error}). Reply again with ONLY the JSON object: no prose, no code fences.' > src/main/resources/prompts/json_repair.txt
```

- [ ] **Step 3: Write the failing test**

```java
package org.roncax.podcaster.prompts;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.support.TestData;
import org.roncax.podcaster.support.WireMockResource;

@QuarkusTest
@WithTestResource(WireMockResource.class)
@WithTestResource(org.roncax.podcaster.support.PostgresResource.class)
class PromptSeederTest {
    @Inject PromptSeeder seeder;

    @BeforeEach
    void clean() { TestData.cleanDb(); }

    @Test
    void seedsVersionOneWithBothLabels() {
        for (PromptKey key : PromptKey.values()) {
            PromptVersion v = QuarkusTransaction.requiringNew().call(() ->
                    PromptVersion.<PromptVersion>find("promptKey = ?1 and version = 1", key.dbKey()).firstResult());
            assertNotNull(v, "missing v1 for " + key);
            assertEquals(key.seedBody(), v.body);
            for (PromptLabel label : PromptLabel.values()) {
                PromptLabelAssignment a = QuarkusTransaction.requiringNew().call(() ->
                        PromptLabelAssignment.<PromptLabelAssignment>find("promptKey = ?1 and label = ?2", key.dbKey(), label.dbValue()).firstResult());
                assertEquals(v.id, a.versionId, key + " " + label);
            }
        }
    }

    @Test
    void seedingIsIdempotent() {
        seeder.seed();
        seeder.seed();
        long versions = QuarkusTransaction.requiringNew().call(() -> PromptVersion.count());
        assertEquals(PromptKey.values().length, versions);
    }

    @Test
    void seedBodiesHaveExpectedEndings() {
        assertTrue(PromptKey.RANK.seedBody().endsWith("{items}\n"));
        assertFalse(PromptKey.JSON_REPAIR.seedBody().endsWith("\n"));
        assertEquals(PromptKey.SEGMENT, PromptKey.fromDb("segment").orElseThrow());
        assertTrue(PromptKey.fromDb("nope").isEmpty());
    }
}
```

- [ ] **Step 4: Run to verify failure**

Run: `./mvnw -q test -Dtest=PromptSeederTest`
Expected: compilation FAIL (`PromptSeeder` not found).

- [ ] **Step 5: Implement the enums**

```java
package org.roncax.podcaster.prompts;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/** The prompts the application uses. Text lives in the database; this enum holds the fixed contract. */
public enum PromptKey {
    RANK("rank", "RANK", "Clusters and ranks collected news items",
            Set.of("items", "showName", "language", "focus", "contract"), Set.of("items", "contract"),
            "Return ONLY a JSON object, no prose, with this shape:\n"
                    + "{\"clusters\":[{\"headline\":\"...\",\"itemIds\":[1,2],\"importance\":7}]}"),
    SEGMENT("segment", "SEGMENT", "Writes one spoken segment for a story",
            Set.of("sources", "words", "headline", "language", "focus", "previousTail"), Set.of("sources", "words"),
            null),
    FRAMING("framing", "FRAMING", "Writes episode title, show notes, intro and outro",
            Set.of("stories", "showName", "language", "date", "contract"), Set.of("stories", "contract"),
            "{\"title\":\"...\",\"description\":\"...\",\"intro\":\"...\",\"outro\":\"...\"}"),
    JSON_REPAIR("json_repair", null, "Follow-up message when a reply is not valid JSON",
            Set.of("error"), Set.of("error"), null);

    private final String dbKey;
    private final String header;
    private final String description;
    private final Set<String> variables;
    private final Set<String> required;
    private final String contract;

    PromptKey(String dbKey, String header, String description, Set<String> variables, Set<String> required, String contract) {
        this.dbKey = dbKey;
        this.header = header;
        this.description = description;
        this.variables = variables;
        this.required = required;
        this.contract = contract;
    }

    public String dbKey() { return dbKey; }
    public String header() { return header; }
    public String description() { return description; }
    public Set<String> variables() { return variables; }
    public Set<String> required() { return required; }
    public String contract() { return contract; }

    public String seedBody() {
        try (InputStream in = PromptKey.class.getResourceAsStream("/prompts/" + dbKey + ".txt")) {
            if (in == null) throw new IllegalStateException("Missing seed prompt /prompts/" + dbKey + ".txt");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Optional<PromptKey> fromDb(String key) {
        return Arrays.stream(values()).filter(k -> k.dbKey.equals(key)).findFirst();
    }
}
```

```java
package org.roncax.podcaster.prompts;

import java.util.Arrays;
import java.util.Optional;

public enum PromptLabel {
    PRODUCTION("production"), DRAFT("draft");

    private final String dbValue;

    PromptLabel(String dbValue) { this.dbValue = dbValue; }

    public String dbValue() { return dbValue; }

    public static Optional<PromptLabel> fromDb(String value) {
        return Arrays.stream(values()).filter(l -> l.dbValue.equals(value)).findFirst();
    }
}
```

- [ ] **Step 6: Implement the entities**

```java
package org.roncax.podcaster.prompts;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "prompts")
public class Prompt extends PanacheEntityBase {
    @Id public String key;
    public String description;
}
```

```java
package org.roncax.podcaster.prompts;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Optional;
import org.hibernate.annotations.Immutable;

/** One immutable prompt version. Never updated or deleted. */
@Entity
@Immutable
@Table(name = "prompt_versions")
public class PromptVersion extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public String promptKey;
    public int version;
    public String body;
    public String note;
    public Instant createdAt = Instant.now();

    public static Optional<PromptVersion> find(PromptKey key, int version) {
        return find("promptKey = ?1 and version = ?2", key.dbKey(), version).firstResultOptional();
    }
}
```

```java
package org.roncax.podcaster.prompts;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

@Entity
@Table(name = "prompt_labels")
public class PromptLabelAssignment extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public String promptKey;
    public String label;
    public Long versionId;
}
```

```java
package org.roncax.podcaster.prompts;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

@Entity
@Table(name = "show_prompt_overrides")
public class ShowPromptOverride extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Long showId;
    public String promptKey;
    public Long versionId;
}
```

Add to `Episode.java` (next to the other JSON columns):

```java
    @JdbcTypeCode(SqlTypes.JSON) public java.util.Map<String, Integer> promptVersions;
```

- [ ] **Step 7: Implement `PromptSeeder`**

```java
package org.roncax.podcaster.prompts;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

/** Inserts missing prompts with their v1 seed text and points both labels at it. Idempotent. */
@ApplicationScoped
public class PromptSeeder {
    private static final Logger LOG = Logger.getLogger(PromptSeeder.class);

    void onStart(@Observes @Priority(10) StartupEvent event) {
        seed();
    }

    public void seed() {
        QuarkusTransaction.requiringNew().run(() -> {
            for (PromptKey key : PromptKey.values()) {
                if (Prompt.findById(key.dbKey()) == null) {
                    Prompt p = new Prompt();
                    p.key = key.dbKey();
                    p.description = key.description();
                    p.persist();
                }
                if (PromptVersion.count("promptKey", key.dbKey()) > 0) continue;
                PromptVersion v = new PromptVersion();
                v.promptKey = key.dbKey();
                v.version = 1;
                v.body = key.seedBody();
                v.note = "Initial version";
                v.persist();
                for (PromptLabel label : PromptLabel.values()) {
                    PromptLabelAssignment a = new PromptLabelAssignment();
                    a.promptKey = key.dbKey();
                    a.label = label.dbValue();
                    a.versionId = v.id;
                    a.persist();
                }
                LOG.infof("Seeded prompt %s v1", key.dbKey());
            }
        });
    }
}
```

- [ ] **Step 8: Add `resetPrompts()` to `TestData`**

Add the method and call it as the last line inside `cleanDb()` (after the existing `QuarkusTransaction` block):

```java
    public static void resetPrompts() {
        QuarkusTransaction.requiringNew().run(() -> {
            org.roncax.podcaster.prompts.ShowPromptOverride.deleteAll();
            org.roncax.podcaster.prompts.PromptLabelAssignment.deleteAll();
            org.roncax.podcaster.prompts.PromptVersion.deleteAll();
        });
        io.quarkus.arc.Arc.container().instance(org.roncax.podcaster.prompts.PromptSeeder.class).get().seed();
    }
```

`PromptVersion` is `@Immutable`; Panache's `deleteAll()` issues a bulk HQL delete, which Hibernate allows for immutable entities (only entity-level updates are ignored). If your Hibernate version rejects it, use `PromptVersion.getEntityManager().createNativeQuery("delete from prompt_versions").executeUpdate()` instead.

- [ ] **Step 9: Run tests**

Run: `./mvnw -q test -Dtest=PromptSeederTest,DomainPersistenceTest`
Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "Add prompt registry schema, entities and v1 seeding

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01LbcoMupq1VXnfzdqroFmyp"
```

---

### Task 2: Sandboxed renderer, validation, `PromptSet`, golden equivalence

**Files:**
- Create: `src/main/java/org/roncax/podcaster/prompts/{PromptRenderer,PromptSet,InvalidPromptException}.java`
- Create: `src/test/java/org/roncax/podcaster/support/LegacyPrompts.java` (verbatim copy of today's prompt code, test-only)
- Create: `src/test/java/org/roncax/podcaster/support/TestPrompts.java`
- Test: `src/test/java/org/roncax/podcaster/prompts/PromptRendererTest.java`, `src/test/java/org/roncax/podcaster/prompts/PromptGoldenTest.java`

**Interfaces:**
- Consumes: `PromptKey` (Task 1).
- Produces:
  - `PromptRenderer` (public no-arg constructor; `@ApplicationScoped`): `render(String body, Map<String,Object> vars) → String`, `validate(PromptKey key, String body) → List<String>` (empty = valid).
  - `PromptSet`: `record Entry(int version, String body)`; constructor `PromptSet(PromptRenderer, Map<PromptKey, Entry>)`; `render(PromptKey, Map<String,Object> vars) → String` (adds `contract` var, prefixes `"TASK: <header>\n"`, wraps failures in `GenerationException("prompt <key> v<n>: …")`); `version(PromptKey) → int`; `versions(PromptKey... keys) → Map<String,Integer>` (db key → version, insertion-ordered).
  - `InvalidPromptException(List<String> errors)` with `errors()` (unchecked).
  - Test: `TestPrompts.seeded() → PromptSet` (all keys at v1 from seed files); `TestPrompts.with(PromptKey, String body, int version) → PromptSet` (seeded, one key replaced).
  - Test: `LegacyPrompts` with `rank(...)`, `segment(...)`, `framing(...)`, `REPAIR` — exact copies of the current `Prompts`/`JsonChat` text.

- [ ] **Step 1: Copy today's prompt code into `LegacyPrompts` (test-only, kept as the golden reference)**

```java
package org.roncax.podcaster.support;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

/** Frozen copy of the pre-registry prompt code. Golden reference for the seeded v1 templates. */
public final class LegacyPrompts {
    public static final String REPAIR = "Your previous reply could not be parsed as JSON (%s). "
            + "Reply again with ONLY the JSON object: no prose, no code fences.";

    private LegacyPrompts() {}

    static String languageName(String tag) {
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
}
```

- [ ] **Step 2: Write the failing tests**

`TestPrompts` (test support):

```java
package org.roncax.podcaster.support;

import java.util.EnumMap;
import java.util.Map;
import org.roncax.podcaster.prompts.PromptKey;
import org.roncax.podcaster.prompts.PromptRenderer;
import org.roncax.podcaster.prompts.PromptSet;

public final class TestPrompts {
    private static final PromptRenderer RENDERER = new PromptRenderer();

    private TestPrompts() {}

    public static PromptSet seeded() {
        Map<PromptKey, PromptSet.Entry> entries = new EnumMap<>(PromptKey.class);
        for (PromptKey key : PromptKey.values()) entries.put(key, new PromptSet.Entry(1, key.seedBody()));
        return new PromptSet(RENDERER, entries);
    }

    public static PromptSet with(PromptKey key, String body, int version) {
        Map<PromptKey, PromptSet.Entry> entries = new EnumMap<>(PromptKey.class);
        for (PromptKey k : PromptKey.values()) entries.put(k, new PromptSet.Entry(1, k.seedBody()));
        entries.put(key, new PromptSet.Entry(version, body));
        return new PromptSet(RENDERER, entries);
    }
}
```

```java
package org.roncax.podcaster.prompts;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.llm.GenerationException;
import org.roncax.podcaster.support.TestPrompts;

class PromptRendererTest {
    PromptRenderer renderer = new PromptRenderer();

    @Test
    void seedBodiesAreValid() {
        for (PromptKey key : PromptKey.values()) {
            assertEquals(List.of(), renderer.validate(key, key.seedBody()), key.dbKey());
        }
    }

    @Test
    void reportsSyntaxErrors() {
        List<String> errors = renderer.validate(PromptKey.SEGMENT, "{sources} {words} {#if focus}unclosed");
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).startsWith("Template syntax error"), errors.get(0));
    }

    @Test
    void reportsUnknownAndMissingVariables() {
        List<String> errors = renderer.validate(PromptKey.RANK, "Hello {showName} {nonsense}");
        assertTrue(errors.contains("Unknown variable 'nonsense'"), errors.toString());
        assertTrue(errors.contains("Missing required variable {items}"), errors.toString());
        assertTrue(errors.contains("Missing required variable {contract}"), errors.toString());
    }

    @Test
    void rejectsNamespaces() {
        List<String> errors = renderer.validate(PromptKey.JSON_REPAIR, "{error} {config:quarkus.datasource.password} {inject:foo}");
        assertEquals(2, errors.stream().filter(e -> e.startsWith("Namespaces are not allowed")).count(), errors.toString());
    }

    @Test
    void standaloneEngineHasNoNamespaces() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("error", "x");
        assertThrows(RuntimeException.class, () -> renderer.render("{config:quarkus.datasource.password}", vars));
    }

    @Test
    void emptyBodyIsInvalid() {
        assertEquals(List.of("Prompt body is empty"), renderer.validate(PromptKey.SEGMENT, "  "));
    }

    @Test
    void promptSetAddsHeaderContractAndReportsVersionOnFailure() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("error", "boom");
        assertEquals(LegacyRepair.text("boom"), TestPrompts.seeded().render(PromptKey.JSON_REPAIR, vars));

        var broken = TestPrompts.with(PromptKey.SEGMENT, "{sources} {words} {headline.length.foo}", 7);
        Map<String, Object> segVars = new HashMap<>();
        segVars.put("sources", "s");
        segVars.put("words", 10);
        segVars.put("headline", "h");
        GenerationException ex = assertThrows(GenerationException.class, () -> broken.render(PromptKey.SEGMENT, segVars));
        assertTrue(ex.getMessage().startsWith("prompt segment v7: "), ex.getMessage());
        assertEquals(Map.of("segment", 7, "rank", 1), broken.versions(PromptKey.SEGMENT, PromptKey.RANK));
    }

    static final class LegacyRepair {
        static String text(String error) { return org.roncax.podcaster.support.LegacyPrompts.REPAIR.formatted(error); }
    }
}
```

```java
package org.roncax.podcaster.prompts;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.support.LegacyPrompts;
import org.roncax.podcaster.support.TestPrompts;

/** Seeded v1 must render exactly what the pre-registry code produced. */
class PromptGoldenTest {
    PromptSet prompts = TestPrompts.seeded();

    private static Map<String, Object> vars(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void rankWithAndWithoutFocus() {
        List<String> lines = List.of("[id=1] Primo — testo \"citato\" & altro", "[id=2] Secondo — 45% {graffe}");
        for (String focus : new String[] {null, "Prioritise AI news"}) {
            String expected = LegacyPrompts.rank("Daily", "it", focus, lines);
            String actual = prompts.render(PromptKey.RANK, vars(
                    "showName", "Daily", "language", "Italian", "focus", focus, "items", String.join("\n", lines)));
            assertEquals(expected, actual, "focus=" + focus);
        }
    }

    @Test
    void segmentFirstAndFollowing() {
        String sources = "TITLE: A\nURL: https://x/1\nTEXT:\nBody with \"quotes\" & {braces}";
        assertEquals(LegacyPrompts.segment("it", "Story A", 400, sources, null, null),
                prompts.render(PromptKey.SEGMENT, vars("language", "Italian", "headline", "Story A", "words", 400,
                        "sources", sources, "previousTail", null, "focus", null)));
        assertEquals(LegacyPrompts.segment("it", "Story B", 300, sources, "…fine del segmento.", "Solo politica"),
                prompts.render(PromptKey.SEGMENT, vars("language", "Italian", "headline", "Story B", "words", 300,
                        "sources", sources, "previousTail", "…fine del segmento.", "focus", "Solo politica")));
    }

    @Test
    void framing() {
        LocalDate date = LocalDate.of(2026, 10, 6);
        List<String> headlines = List.of("Story A", "Story B");
        String stories = String.join("\n", IntStream.range(0, headlines.size()).mapToObj(i -> (i + 1) + ". " + headlines.get(i)).toList());
        assertEquals(LegacyPrompts.framing("Daily", "it", date, headlines),
                prompts.render(PromptKey.FRAMING, vars("showName", "Daily", "language", "Italian",
                        "date", "6 ottobre 2026", "stories", stories)));
    }

    @Test
    void jsonRepair() {
        assertEquals(LegacyPrompts.REPAIR.formatted("Unexpected character ('I')"),
                prompts.render(PromptKey.JSON_REPAIR, vars("error", "Unexpected character ('I')")));
    }
}
```

- [ ] **Step 3: Run to verify failure**

Run: `./mvnw -q test -Dtest='PromptRendererTest,PromptGoldenTest'`
Expected: compilation FAIL (`PromptRenderer` not found).

- [ ] **Step 4: Implement `InvalidPromptException`, `PromptRenderer`, `PromptSet`**

```java
package org.roncax.podcaster.prompts;

import java.util.List;

public class InvalidPromptException extends RuntimeException {
    private final List<String> errors;

    public InvalidPromptException(List<String> errors) {
        super(String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() { return errors; }
}
```

```java
package org.roncax.podcaster.prompts;

import io.quarkus.qute.Engine;
import io.quarkus.qute.Expression;
import io.quarkus.qute.ReflectionValueResolver;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateException;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renders prompt bodies with a standalone Qute engine. Deliberately not the Quarkus-managed engine:
 * it has no namespace resolvers (inject:, config:), so a template cannot read beans or secrets.
 */
@ApplicationScoped
public class PromptRenderer {
    private final Engine engine = Engine.builder()
            .addDefaults()
            .addValueResolver(new ReflectionValueResolver())
            .removeStandaloneLines(true)
            .strictRendering(true)
            .build();
    private final Map<String, Template> parsed = new ConcurrentHashMap<>();

    public String render(String body, Map<String, Object> vars) {
        Template template = parsed.computeIfAbsent(body, engine::parse);
        return template.data(vars).render();
    }

    public List<String> validate(PromptKey key, String body) {
        if (body == null || body.isBlank()) return List.of("Prompt body is empty");
        Template template;
        try {
            template = engine.parse(body);
        } catch (TemplateException e) {
            return List.of("Template syntax error: " + e.getMessage());
        }
        List<String> errors = new ArrayList<>();
        Set<String> used = new TreeSet<>();
        for (Expression expression : template.getExpressions()) {
            if (expression.hasNamespace()) {
                errors.add("Namespaces are not allowed: {" + expression.toOriginalString() + "}");
                continue;
            }
            if (expression.isLiteral() || expression.getParts().isEmpty()) continue;
            used.add(expression.getParts().get(0).getName());
        }
        for (String name : used) {
            if (!key.variables().contains(name)) errors.add("Unknown variable '" + name + "'");
        }
        for (String name : new TreeSet<>(key.required())) {
            if (!used.contains(name)) errors.add("Missing required variable {" + name + "}");
        }
        return errors;
    }
}
```

```java
package org.roncax.podcaster.prompts;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.roncax.podcaster.llm.GenerationException;

/** The prompt versions resolved for one stage of one show. */
public final class PromptSet {
    public record Entry(int version, String body) {}

    private final PromptRenderer renderer;
    private final Map<PromptKey, Entry> entries;

    public PromptSet(PromptRenderer renderer, Map<PromptKey, Entry> entries) {
        this.renderer = renderer;
        this.entries = Map.copyOf(entries);
    }

    public String render(PromptKey key, Map<String, Object> vars) {
        Entry entry = entries.get(key);
        if (entry == null) throw new GenerationException("No version resolved for prompt " + key.dbKey());
        Map<String, Object> all = new HashMap<>(vars);
        if (key.contract() != null) all.put("contract", key.contract());
        try {
            String body = renderer.render(entry.body(), all);
            return key.header() == null ? body : "TASK: " + key.header() + "\n" + body;
        } catch (RuntimeException e) {
            throw new GenerationException("prompt " + key.dbKey() + " v" + entry.version() + ": " + e.getMessage(), e);
        }
    }

    public int version(PromptKey key) {
        return entries.get(key).version();
    }

    public Map<String, Integer> versions(PromptKey... keys) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (PromptKey key : keys) out.put(key.dbKey(), version(key));
        return out;
    }
}
```

- [ ] **Step 5: Run tests**

Run: `./mvnw -q test -Dtest='PromptRendererTest,PromptGoldenTest'`
Expected: PASS. If a golden assertion fails, compare the two strings character by character (the assertion message shows both) and fix the **seed file** (whitespace, trailing newline, a missing word) — never the legacy copy.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Add sandboxed prompt renderer, validation and golden equivalence tests

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01LbcoMupq1VXnfzdqroFmyp"
```

---

### Task 3: Registry (versions, labels, pins) and resolver

**Files:**
- Create: `src/main/java/org/roncax/podcaster/prompts/{PromptRegistry,PromptResolver}.java`
- Modify: `src/test/java/org/roncax/podcaster/support/TestData.java` (`resetPrompts()` also invalidates the resolver)
- Test: `src/test/java/org/roncax/podcaster/prompts/PromptRegistryTest.java`

**Interfaces:**
- Consumes: entities and enums (Task 1); `PromptRenderer`, `PromptSet`, `InvalidPromptException` (Task 2); `Show` entity.
- Produces:
  - `PromptResolver`: `enum Mode { PRODUCTION, DRAFT }`; `resolve(Long showId, Mode mode) → PromptSet` (showId may be null → labels only); `invalidate()`.
  - `PromptRegistry`:
    - `createVersion(PromptKey key, String body, String note) → PromptVersion` (validates; version = max+1; moves `draft` to it; throws `InvalidPromptException`)
    - `setLabel(PromptKey key, PromptLabel label, int version)` (throws `InvalidPromptException("Prompt <key> has no version <n>")`)
    - `pin(long showId, PromptKey key, int version)` (throws `jakarta.ws.rs.NotFoundException` for unknown show, `InvalidPromptException` for unknown version); `unpin(long showId, PromptKey key)`
    - `versions(PromptKey key) → List<PromptVersion>` (newest first); `version(PromptKey key, int n) → Optional<PromptVersion>`
    - `labels(PromptKey key) → Map<PromptLabel, Integer>`; `labelsOf(PromptKey key, int version) → List<String>`
    - `overrides(long showId) → Map<PromptKey, Integer>`
    - Every write calls `PromptResolver.invalidate()`.

- [ ] **Step 1: Write the failing test**

```java
package org.roncax.podcaster.prompts;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.domain.Show;
import org.roncax.podcaster.support.TestData;
import org.roncax.podcaster.support.WireMockResource;

@QuarkusTest
@WithTestResource(WireMockResource.class)
@WithTestResource(org.roncax.podcaster.support.PostgresResource.class)
class PromptRegistryTest {
    @Inject PromptRegistry registry;
    @Inject PromptResolver resolver;

    static final String REPAIR_V2 = "JSON broken ({error}). Send only the object.";

    @BeforeEach
    void clean() { TestData.cleanDb(); }

    private static String repair(PromptSet set) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("error", "E");
        return set.render(PromptKey.JSON_REPAIR, vars);
    }

    @Test
    void createVersionLandsAsDraftOnly() {
        PromptVersion v2 = registry.createVersion(PromptKey.JSON_REPAIR, REPAIR_V2, "shorter");
        assertEquals(2, v2.version);
        assertEquals(Map.of(PromptLabel.PRODUCTION, 1, PromptLabel.DRAFT, 2), registry.labels(PromptKey.JSON_REPAIR));
        assertEquals("JSON broken (E). Send only the object.", repair(resolver.resolve(null, PromptResolver.Mode.DRAFT)));
        assertTrue(repair(resolver.resolve(null, PromptResolver.Mode.PRODUCTION)).startsWith("Your previous reply"));
    }

    @Test
    void invalidBodyIsRejectedAndNothingStored() {
        InvalidPromptException ex = assertThrows(InvalidPromptException.class,
                () -> registry.createVersion(PromptKey.RANK, "no variables here", null));
        assertTrue(ex.errors().contains("Missing required variable {items}"));
        assertEquals(1, registry.versions(PromptKey.RANK).size());
    }

    @Test
    void promoteAndRollBack() {
        registry.createVersion(PromptKey.JSON_REPAIR, REPAIR_V2, null);
        registry.setLabel(PromptKey.JSON_REPAIR, PromptLabel.PRODUCTION, 2);
        assertTrue(repair(resolver.resolve(null, PromptResolver.Mode.PRODUCTION)).startsWith("JSON broken"));
        registry.setLabel(PromptKey.JSON_REPAIR, PromptLabel.PRODUCTION, 1);
        assertTrue(repair(resolver.resolve(null, PromptResolver.Mode.PRODUCTION)).startsWith("Your previous reply"));
        assertEquals(java.util.List.of("draft"), registry.labelsOf(PromptKey.JSON_REPAIR, 2));
    }

    @Test
    void unknownVersionIsRejected() {
        assertThrows(InvalidPromptException.class, () -> registry.setLabel(PromptKey.RANK, PromptLabel.PRODUCTION, 99));
    }

    @Test
    void showOverrideBeatsLabel() {
        Show pinned = TestData.show("pinned");
        Show other = TestData.show("other");
        registry.createVersion(PromptKey.JSON_REPAIR, REPAIR_V2, null);
        registry.pin(pinned.id, PromptKey.JSON_REPAIR, 2);

        assertTrue(repair(resolver.resolve(pinned.id, PromptResolver.Mode.PRODUCTION)).startsWith("JSON broken"));
        assertTrue(repair(resolver.resolve(other.id, PromptResolver.Mode.PRODUCTION)).startsWith("Your previous reply"));
        assertEquals(Map.of(PromptKey.JSON_REPAIR, 2), registry.overrides(pinned.id));

        registry.unpin(pinned.id, PromptKey.JSON_REPAIR);
        assertTrue(repair(resolver.resolve(pinned.id, PromptResolver.Mode.PRODUCTION)).startsWith("Your previous reply"));
    }

    @Test
    void pinningUnknownShowIs404() {
        assertThrows(jakarta.ws.rs.NotFoundException.class, () -> registry.pin(999_999L, PromptKey.RANK, 1));
    }

    @Test
    void deletingShowRemovesOnlyItsOverrides() {
        Show show = TestData.show("gone");
        registry.createVersion(PromptKey.JSON_REPAIR, REPAIR_V2, null);
        registry.pin(show.id, PromptKey.JSON_REPAIR, 2);

        QuarkusTransaction.requiringNew().run(() -> Show.deleteById(show.id));

        assertEquals(0L, QuarkusTransaction.requiringNew().call(() -> ShowPromptOverride.count()));
        assertEquals(2, registry.versions(PromptKey.JSON_REPAIR).size());
    }

    @Test
    void versionsAreNewestFirst() {
        registry.createVersion(PromptKey.JSON_REPAIR, REPAIR_V2, "two");
        registry.createVersion(PromptKey.JSON_REPAIR, REPAIR_V2 + " Please.", "three");
        assertEquals(java.util.List.of(3, 2, 1), registry.versions(PromptKey.JSON_REPAIR).stream().map(v -> v.version).toList());
        assertEquals("two", registry.version(PromptKey.JSON_REPAIR, 2).orElseThrow().note);
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -q test -Dtest=PromptRegistryTest`
Expected: compilation FAIL.

- [ ] **Step 3: Implement `PromptResolver`**

```java
package org.roncax.podcaster.prompts;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.roncax.podcaster.llm.GenerationException;

/** Resolves which version of each prompt a show uses: show override, else the label for the mode. */
@ApplicationScoped
public class PromptResolver {
    public enum Mode { PRODUCTION, DRAFT }

    @Inject PromptRenderer renderer;
    private final Map<String, PromptSet> cache = new ConcurrentHashMap<>();

    public PromptSet resolve(Long showId, Mode mode) {
        return cache.computeIfAbsent(showId + ":" + mode, k -> load(showId, mode));
    }

    public void invalidate() {
        cache.clear();
    }

    private PromptSet load(Long showId, Mode mode) {
        String label = (mode == Mode.DRAFT ? PromptLabel.DRAFT : PromptLabel.PRODUCTION).dbValue();
        Map<PromptKey, PromptSet.Entry> entries = QuarkusTransaction.requiringNew().call(() -> {
            Map<PromptKey, PromptSet.Entry> map = new EnumMap<>(PromptKey.class);
            for (PromptKey key : PromptKey.values()) {
                Long versionId = null;
                if (showId != null) {
                    versionId = ShowPromptOverride.<ShowPromptOverride>find("showId = ?1 and promptKey = ?2", showId, key.dbKey())
                            .firstResultOptional().map(o -> o.versionId).orElse(null);
                }
                if (versionId == null) {
                    versionId = PromptLabelAssignment.<PromptLabelAssignment>find("promptKey = ?1 and label = ?2", key.dbKey(), label)
                            .firstResultOptional().map(a -> a.versionId)
                            .orElseThrow(() -> new GenerationException("No " + label + " version for prompt " + key.dbKey()));
                }
                PromptVersion v = PromptVersion.findById(versionId);
                map.put(key, new PromptSet.Entry(v.version, v.body));
            }
            return map;
        });
        return new PromptSet(renderer, entries);
    }
}
```

- [ ] **Step 4: Implement `PromptRegistry`**

```java
package org.roncax.podcaster.prompts;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import java.util.*;
import org.roncax.podcaster.domain.Show;

@ApplicationScoped
public class PromptRegistry {
    @Inject PromptRenderer renderer;
    @Inject PromptResolver resolver;

    public PromptVersion createVersion(PromptKey key, String body, String note) {
        List<String> errors = renderer.validate(key, body);
        if (!errors.isEmpty()) throw new InvalidPromptException(errors);
        PromptVersion created = QuarkusTransaction.requiringNew().call(() -> {
            Integer max = PromptVersion.getEntityManager()
                    .createQuery("select max(v.version) from PromptVersion v where v.promptKey = :k", Integer.class)
                    .setParameter("k", key.dbKey()).getSingleResult();
            PromptVersion v = new PromptVersion();
            v.promptKey = key.dbKey();
            v.version = (max == null ? 0 : max) + 1;
            v.body = body;
            v.note = note == null || note.isBlank() ? null : note.trim();
            v.persist();
            assign(key, PromptLabel.DRAFT, v.id);
            return v;
        });
        resolver.invalidate();
        return created;
    }

    public void setLabel(PromptKey key, PromptLabel label, int version) {
        QuarkusTransaction.requiringNew().run(() -> assign(key, label, require(key, version).id));
        resolver.invalidate();
    }

    public void pin(long showId, PromptKey key, int version) {
        QuarkusTransaction.requiringNew().run(() -> {
            if (Show.findById(showId) == null) throw new NotFoundException("Show " + showId + " not found");
            Long versionId = require(key, version).id;
            ShowPromptOverride o = ShowPromptOverride.<ShowPromptOverride>find("showId = ?1 and promptKey = ?2", showId, key.dbKey())
                    .firstResultOptional().orElseGet(() -> {
                        ShowPromptOverride n = new ShowPromptOverride();
                        n.showId = showId;
                        n.promptKey = key.dbKey();
                        return n;
                    });
            o.versionId = versionId;
            o.persist();
        });
        resolver.invalidate();
    }

    public void unpin(long showId, PromptKey key) {
        QuarkusTransaction.requiringNew().run(() ->
                ShowPromptOverride.delete("showId = ?1 and promptKey = ?2", showId, key.dbKey()));
        resolver.invalidate();
    }

    public List<PromptVersion> versions(PromptKey key) {
        return QuarkusTransaction.requiringNew().call(() ->
                PromptVersion.<PromptVersion>list("promptKey = ?1 order by version desc", key.dbKey()));
    }

    public Optional<PromptVersion> version(PromptKey key, int n) {
        return QuarkusTransaction.requiringNew().call(() -> PromptVersion.find(key, n));
    }

    public Map<PromptLabel, Integer> labels(PromptKey key) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Map<PromptLabel, Integer> out = new EnumMap<>(PromptLabel.class);
            for (PromptLabelAssignment a : PromptLabelAssignment.<PromptLabelAssignment>list("promptKey", key.dbKey())) {
                PromptVersion v = PromptVersion.findById(a.versionId);
                PromptLabel.fromDb(a.label).ifPresent(l -> out.put(l, v.version));
            }
            return out;
        });
    }

    public List<String> labelsOf(PromptKey key, int version) {
        List<String> out = new ArrayList<>();
        labels(key).forEach((label, v) -> { if (v == version) out.add(label.dbValue()); });
        return out;
    }

    public Map<PromptKey, Integer> overrides(long showId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Map<PromptKey, Integer> out = new EnumMap<>(PromptKey.class);
            for (ShowPromptOverride o : ShowPromptOverride.<ShowPromptOverride>list("showId", showId)) {
                PromptVersion v = PromptVersion.findById(o.versionId);
                PromptKey.fromDb(o.promptKey).ifPresent(k -> out.put(k, v.version));
            }
            return out;
        });
    }

    private PromptVersion require(PromptKey key, int version) {
        return PromptVersion.find(key, version)
                .orElseThrow(() -> new InvalidPromptException(List.of("Prompt " + key.dbKey() + " has no version " + version)));
    }

    private void assign(PromptKey key, PromptLabel label, Long versionId) {
        PromptLabelAssignment a = PromptLabelAssignment.<PromptLabelAssignment>find("promptKey = ?1 and label = ?2", key.dbKey(), label.dbValue())
                .firstResultOptional().orElseGet(() -> {
                    PromptLabelAssignment n = new PromptLabelAssignment();
                    n.promptKey = key.dbKey();
                    n.label = label.dbValue();
                    return n;
                });
        a.versionId = versionId;
        a.persist();
    }
}
```

- [ ] **Step 5: Make `TestData.resetPrompts()` invalidate the resolver**

Append to the end of `resetPrompts()`:

```java
        io.quarkus.arc.Arc.container().instance(org.roncax.podcaster.prompts.PromptResolver.class).get().invalidate();
```

- [ ] **Step 6: Run tests**

Run: `./mvnw -q test -Dtest='PromptRegistryTest,PromptSeederTest'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "Add prompt registry with labels, show pins and cached resolver

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01LbcoMupq1VXnfzdqroFmyp"
```

---
### Task 4: Generation uses resolved prompts; episodes record lineage

**Files:**
- Modify: `src/main/java/org/roncax/podcaster/generation/Prompts.java` (rewrite: no text, renders via `PromptSet`)
- Modify: `src/main/java/org/roncax/podcaster/llm/JsonChat.java` (repair message from `json_repair`)
- Modify: `src/main/java/org/roncax/podcaster/generation/StoryRanker.java`, `ScriptWriter.java` (take a `PromptSet`)
- Modify: `src/main/java/org/roncax/podcaster/domain/Item.java` (add `unusedCandidates`)
- Modify: `src/main/java/org/roncax/podcaster/runs/SelectStage.java`, `ScriptStage.java` (resolve prompts, record `promptVersions`)
- Modify: `src/test/java/org/roncax/podcaster/generation/StoryRankerTest.java`, `ScriptWriterTest.java` (pass `TestPrompts.seeded()`)
- Test: `src/test/java/org/roncax/podcaster/prompts/PromptPipelineTest.java`

**Interfaces:**
- Consumes: `PromptSet`, `PromptKey` (Tasks 1–2), `PromptResolver`, `PromptRegistry` (Task 3), `TestPrompts` (Task 2).
- Produces:
  - `Prompts.rank(PromptSet, String showName, String language, String focus, List<String> itemLines)`, `Prompts.segment(PromptSet, String language, String headline, int words, String sources, String previousTail, String focus)`, `Prompts.framing(PromptSet, String showName, String language, LocalDate date, List<String> headlines)`, `Prompts.languageName(String)` — all `→ String`.
  - `JsonChat.ask(ChatModel, String prompt, Class<T>, PromptSet) → T`.
  - `StoryRanker.rank(ChatModel, PromptSet, String showName, String language, String focusPrompt, List<Item> candidates) → Selection`.
  - `ScriptWriter.write(ChatModel, PromptSet, Show, Outline, Map<Long,Item>, LocalDate) → Script`.
  - `Item.unusedCandidates(long showId, Instant since, int limit) → List<Item>` (newest first by `coalesce(publishedAt, fetchedAt)`).
  - `Episode.promptVersions` filled: SELECT writes `rank`, `json_repair`; SCRIPT adds `segment`, `framing`, `json_repair`.

- [ ] **Step 1: Write the failing pipeline test**

```java
package org.roncax.podcaster.prompts;

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
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.domain.*;
import org.roncax.podcaster.runs.RunLauncher;
import org.roncax.podcaster.support.*;

@QuarkusTest
@WithTestResource(WireMockResource.class)
@WithTestResource(org.roncax.podcaster.support.PostgresResource.class)
class PromptPipelineTest {
    static final String RANK_V2 = "MARKER-RANK-V2 for \"{showName}\" in {language}.\n{contract}\n\nITEMS:\n{items}\n";
    static final String SEGMENT_V2 = "MARKER-SEGMENT-V2 about {headline} in {language}, about {words} words.\nSOURCES:\n{sources}\n";

    @InjectWireMock WireMockServer wm;
    @Inject RunLauncher launcher;
    @Inject PromptRegistry registry;
    FakeChatModel model;
    Show show;

    @BeforeEach
    void setup() {
        TestData.cleanDb();
        WireMockResource.installDefaults(wm);
        model = new FakeChatModel().responder(FakeResponses::pipeline);
        FakeChatModelRegistry.install(model);
        show = TestData.show("lineage");
        TestData.source(show.id, wm.baseUrl() + "/lfeed");
        String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.now().atOffset(ZoneOffset.UTC));
        wm.stubFor(get("/lfeed").willReturn(okXml("<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>t</title><link>http://x</link><description>d</description>"
                + "<item><title>Story one</title><link>" + wm.baseUrl() + "/l1</link><pubDate>" + date + "</pubDate></item></channel></rss>")));
        wm.stubFor(get("/l1").willReturn(aResponse().withStatus(200).withBody(Fixtures.bytes("ilpost-article.html"))));
    }

    private Episode episodeOf(Run run) {
        return QuarkusTransaction.requiringNew().call(() -> Episode.findByRun(run.id).orElseThrow());
    }

    @Test
    void recordsPromptVersions() {
        Run run = TestData.awaitRun(launcher.launch(show.id, RunTrigger.MANUAL));
        assertEquals(RunStatus.DONE, run.status, run.error);
        assertEquals(Map.of("rank", 1, "json_repair", 1, "segment", 1, "framing", 1), episodeOf(run).promptVersions);
    }

    @Test
    void promotedRankVersionIsUsed() {
        registry.createVersion(PromptKey.RANK, RANK_V2, "test");
        registry.setLabel(PromptKey.RANK, PromptLabel.PRODUCTION, 2);

        Run run = TestData.awaitRun(launcher.launch(show.id, RunTrigger.MANUAL));

        assertEquals(RunStatus.DONE, run.status, run.error);
        assertTrue(model.userMessage(0).startsWith("TASK: RANK\nMARKER-RANK-V2"), model.userMessage(0));
        assertEquals(2, episodeOf(run).promptVersions.get("rank"));
    }

    @Test
    void pinnedShowUsesPinnedDraftVersion() {
        registry.createVersion(PromptKey.RANK, RANK_V2, "draft only");
        registry.pin(show.id, PromptKey.RANK, 2);

        Run run = TestData.awaitRun(launcher.launch(show.id, RunTrigger.MANUAL));

        assertTrue(model.userMessage(0).contains("MARKER-RANK-V2"));
        assertEquals(2, episodeOf(run).promptVersions.get("rank"));
    }

    @Test
    void retryAfterPromotionRecordsNewVersions() {
        AtomicBoolean failedOnce = new AtomicBoolean();
        model.responder(prompt -> {
            if (prompt.startsWith("TASK: SEGMENT") && failedOnce.compareAndSet(false, true)) {
                throw new IllegalStateException("model outage");
            }
            return FakeResponses.pipeline(prompt);
        });
        Run failed = TestData.awaitRun(launcher.launch(show.id, RunTrigger.MANUAL));
        assertEquals(RunStatus.FAILED, failed.status);
        assertEquals(RunStage.SCRIPT, failed.stage);

        registry.createVersion(PromptKey.SEGMENT, SEGMENT_V2, "fix");
        registry.setLabel(PromptKey.SEGMENT, PromptLabel.PRODUCTION, 2);
        launcher.retry(failed.id);
        Run done = TestData.awaitRun(failed.id);

        assertEquals(RunStatus.DONE, done.status, done.error);
        Map<String, Integer> versions = episodeOf(done).promptVersions;
        assertEquals(1, versions.get("rank"));
        assertEquals(2, versions.get("segment"));
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -q test -Dtest=PromptPipelineTest`
Expected: FAIL — `recordsPromptVersions` gets `null` for `promptVersions`; the marker tests fail because generation still uses hard-coded text.

- [ ] **Step 3: Rewrite `Prompts`**

```java
package org.roncax.podcaster.generation;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;
import org.roncax.podcaster.prompts.PromptKey;
import org.roncax.podcaster.prompts.PromptSet;

/** Builds the variables for each prompt and renders it from the resolved, database-backed versions. */
public final class Prompts {
    private Prompts() {}

    public static String languageName(String tag) {
        String name = Locale.forLanguageTag(tag).getDisplayLanguage(Locale.ENGLISH);
        return name.isBlank() ? tag : name;
    }

    public static String rank(PromptSet prompts, String showName, String language, String focus, List<String> itemLines) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("showName", showName);
        vars.put("language", languageName(language));
        vars.put("focus", blankToNull(focus));
        vars.put("items", String.join("\n", itemLines));
        return prompts.render(PromptKey.RANK, vars);
    }

    public static String segment(PromptSet prompts, String language, String headline, int words, String sources,
                                 String previousTail, String focus) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("language", languageName(language));
        vars.put("headline", headline);
        vars.put("words", words);
        vars.put("sources", sources);
        vars.put("previousTail", previousTail);
        vars.put("focus", blankToNull(focus));
        return prompts.render(PromptKey.SEGMENT, vars);
    }

    public static String framing(PromptSet prompts, String showName, String language, LocalDate date, List<String> headlines) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("showName", showName);
        vars.put("language", languageName(language));
        vars.put("date", date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(Locale.forLanguageTag(language))));
        vars.put("stories", String.join("\n", IntStream.range(0, headlines.size())
                .mapToObj(i -> (i + 1) + ". " + headlines.get(i)).toList()));
        return prompts.render(PromptKey.FRAMING, vars);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
```

- [ ] **Step 4: Update `JsonChat`**

Replace the `REPAIR` constant and the `ask` method with:

```java
    public static <T> T ask(ChatModel model, String prompt, Class<T> type, PromptSet prompts) {
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
                Map<String, Object> vars = new HashMap<>();
                vars.put("error", String.valueOf(e.getMessage()));
                messages.add(UserMessage.from(prompts.render(PromptKey.JSON_REPAIR, vars)));
            }
        }
    }
```

Add imports `java.util.HashMap`, `java.util.Map`, `org.roncax.podcaster.prompts.PromptKey`, `org.roncax.podcaster.prompts.PromptSet`.

- [ ] **Step 5: Update `StoryRanker` and `ScriptWriter`**

`StoryRanker.rank` becomes:

```java
    public Selection rank(ChatModel model, PromptSet prompts, String showName, String language, String focusPrompt, List<Item> candidates) {
        List<String> lines = candidates.stream()
                .map(i -> "[id=" + i.id + "] " + i.title + " — " + snippet(i.bestText()))
                .toList();
        Selection raw = JsonChat.ask(model, Prompts.rank(prompts, showName, language, focusPrompt, lines), Selection.class, prompts);
        return sanitize(raw, candidates);
    }
```

In `ScriptWriter`:
- change the signature to `public Script write(ChatModel model, PromptSet prompts, Show show, Outline outline, Map<Long, Item> items, LocalDate date)`;
- the segment prompt line becomes `String prompt = Prompts.segment(prompts, show.language, seg.headline(), seg.words(), sources(sourceItems), previousTail, show.focusPrompt);`;
- the framing call becomes `Framing framing = JsonChat.ask(model, Prompts.framing(prompts, show.name, show.language, date, headlines), Framing.class, prompts);`.

Add `import org.roncax.podcaster.prompts.PromptSet;` to both.

- [ ] **Step 6: Add `Item.unusedCandidates`**

```java
    public static java.util.List<Item> unusedCandidates(long showId, Instant since, int limit) {
        return find("showId = ?1 and usedInEpisodeId is null and coalesce(publishedAt, fetchedAt) >= ?2 "
                        + "order by coalesce(publishedAt, fetchedAt) desc", showId, since)
                .page(0, limit).list();
    }
```

- [ ] **Step 7: Update `SelectStage`**

Add `@Inject PromptResolver prompts;` (import `org.roncax.podcaster.prompts.*`). Replace the candidate query, the ranker call and the episode block:

```java
        List<Item> candidates = QuarkusTransaction.requiringNew().call(() ->
                Item.unusedCandidates(show.id, run.since, config.selection().maxCandidates()));
        if (candidates.size() < show.minItems) return StageResult.SKIP;

        PromptSet promptSet = prompts.resolve(show.id, PromptResolver.Mode.PRODUCTION);
        Selection selection = ranker.rank(models.get(show.effectiveRankerModel()), promptSet,
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
            episode.promptVersions = merge(episode.promptVersions, promptSet.versions(PromptKey.RANK, PromptKey.JSON_REPAIR));
        });
        return StageResult.CONTINUE;
    }

    static java.util.Map<String, Integer> merge(java.util.Map<String, Integer> existing, java.util.Map<String, Integer> used) {
        java.util.Map<String, Integer> merged = new java.util.LinkedHashMap<>();
        if (existing != null) merged.putAll(existing);
        merged.putAll(used);
        return merged;
    }
```

- [ ] **Step 8: Update `ScriptStage`**

Add `@Inject PromptResolver prompts;`. Before calling the writer resolve the set and pass it; persist the lineage:

```java
        PromptSet promptSet = prompts.resolve(show.id, PromptResolver.Mode.PRODUCTION);
        Script script = writer.write(models.get(show.writerModel), promptSet, show, outline, items, LocalDate.now());

        QuarkusTransaction.requiringNew().run(() -> {
            Episode e = Episode.findById(episode.id);
            e.outline = outline;
            e.title = script.title();
            e.description = script.description();
            e.scriptParts = script.parts();
            e.script = script.joined();
            e.promptVersions = SelectStage.merge(e.promptVersions,
                    promptSet.versions(PromptKey.SEGMENT, PromptKey.FRAMING, PromptKey.JSON_REPAIR));
        });
```

- [ ] **Step 9: Update the unit tests that call the changed signatures**

```bash
sed -i 's/ranker\.rank(model, /ranker.rank(model, TestPrompts.seeded(), /' src/test/java/org/roncax/podcaster/generation/StoryRankerTest.java
sed -i 's/\.write(model, show()/.write(model, TestPrompts.seeded(), show()/; s/\.write(truncating, show()/.write(truncating, TestPrompts.seeded(), show()/' src/test/java/org/roncax/podcaster/generation/ScriptWriterTest.java
sed -i 's/^import org.roncax.podcaster.support.FakeChatModel;/import org.roncax.podcaster.support.FakeChatModel;\nimport org.roncax.podcaster.support.TestPrompts;/' \
  src/test/java/org/roncax/podcaster/generation/StoryRankerTest.java src/test/java/org/roncax/podcaster/generation/ScriptWriterTest.java
```

- [ ] **Step 10: Run tests**

Run: `./mvnw -q test -Dtest='PromptPipelineTest,StoryRankerTest,ScriptWriterTest,PromptGoldenTest,RunPipelineTest,SelectStageTest'`
Expected: PASS. (`StoryRankerTest.repairsInvalidJsonOnce` still finds "could not be parsed as JSON" because the seeded `json_repair` text is unchanged.)

- [ ] **Step 11: Run the full suite**

Run: `./mvnw -q test`
Expected: PASS (all previous tests plus the new ones).

- [ ] **Step 12: Commit**

```bash
git add -A
git commit -m "Render all LLM prompts from the registry and record prompt lineage per episode

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01LbcoMupq1VXnfzdqroFmyp"
```

---

### Task 5: Dry-run and REST API

**Files:**
- Create: `src/main/java/org/roncax/podcaster/prompts/{PromptDryRun,NoCandidatesException}.java`
- Create: `src/main/java/org/roncax/podcaster/api/{PromptResource,ShowPromptResource,PromptViews}.java`
- Modify: `src/main/java/org/roncax/podcaster/api/ApiExceptionMappers.java`
- Test: `src/test/java/org/roncax/podcaster/api/PromptApiTest.java`

**Interfaces:**
- Consumes: `PromptRegistry`, `PromptResolver` (Task 3); `StoryRanker`, `ScriptWriter`, `Item.unusedCandidates` (Task 4); `OutlinePlanner`, `VoiceCalibrationService`, `ChatModelRegistry`, `PodcasterConfig`.
- Produces:
  - `PromptDryRun#run(long showId) → PromptDryRun.Result` where `record Result(Map<String,Integer> promptVersions, Selection selection, Outline outline, String title, String description, List<String> scriptParts)`; throws `NoCandidatesException` (→ 409) and `jakarta.ws.rs.NotFoundException`.
  - `PromptViews` records: `PromptSummary(String key, String description, Integer production, Integer draft)`, `VersionSummary(int version, String note, Instant createdAt, List<String> labels)`, `VersionDetail(int version, String note, Instant createdAt, List<String> labels, String body)`, `NewVersion(@NotBlank String body, String note)`, `VersionRef(@NotNull Integer version)`, `DryRunRequest(@NotNull Long showId)`.
  - REST endpoints exactly as spec §5.1. Unknown key or label → 404. `InvalidPromptException` → 400 `{"error":"Invalid prompt","details":[...]}`.

- [ ] **Step 1: Write the failing test**

```java
package org.roncax.podcaster.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.domain.*;
import org.roncax.podcaster.prompts.PromptKey;
import org.roncax.podcaster.support.*;

@QuarkusTest
@WithTestResource(WireMockResource.class)
@WithTestResource(org.roncax.podcaster.support.PostgresResource.class)
class PromptApiTest {
    static final String RANK_V2 = "MARKER-RANK-V2 for \"{showName}\".\n{contract}\n\nITEMS:\n{items}\n";

    @InjectWireMock WireMockServer wm;
    FakeChatModel model;

    @BeforeEach
    void setup() {
        TestData.cleanDb();
        WireMockResource.installDefaults(wm);
        model = new FakeChatModel().responder(FakeResponses::pipeline);
        FakeChatModelRegistry.install(model);
    }

    private RequestSpecification api() {
        return given().header("X-API-Key", "test-api-key-0123456789").contentType(ContentType.JSON);
    }

    @Test
    void listsPromptsWithLabels() {
        api().get("/api/prompts").then().statusCode(200)
                .body("size()", is(4))
                .body("find { it.key == 'rank' }.production", is(1))
                .body("find { it.key == 'rank' }.draft", is(1));
    }

    @Test
    void createPromoteAndInspectVersions() {
        api().body(Map.of("body", RANK_V2, "note", "marker")).post("/api/prompts/rank/versions").then().statusCode(201)
                .body("version", is(2)).body("labels", contains("draft"));
        api().body(Map.of("version", 2)).put("/api/prompts/rank/labels/production").then().statusCode(200)
                .body("production", is(2));
        api().get("/api/prompts/rank/versions").then().statusCode(200)
                .body("version", contains(2, 1)).body("[0].labels", hasItems("production", "draft"));
        api().get("/api/prompts/rank/versions/1").then().statusCode(200)
                .body("body", is(PromptKey.RANK.seedBody()));
    }

    @Test
    void invalidBodyIs400() {
        api().body(Map.of("body", "hello {nonsense}")).post("/api/prompts/rank/versions").then().statusCode(400)
                .body("details", hasItem("Unknown variable 'nonsense'"));
    }

    @Test
    void unknownKeyLabelOrVersion() {
        api().get("/api/prompts/nope/versions").then().statusCode(404);
        api().body(Map.of("version", 1)).put("/api/prompts/rank/labels/staging").then().statusCode(404);
        api().body(Map.of("version", 42)).put("/api/prompts/rank/labels/production").then().statusCode(400);
        api().get("/api/prompts/rank/versions/42").then().statusCode(404);
    }

    @Test
    void versionsCannotBeEdited() {
        api().body(Map.of("body", "x")).put("/api/prompts/rank/versions/1").then().statusCode(405);
    }

    @Test
    void pinAndUnpinShow() {
        Show show = TestData.show("pins");
        api().body(Map.of("version", 1)).put("/api/shows/" + show.id + "/prompts/json_repair").then().statusCode(200)
                .body("json_repair", is(1));
        api().get("/api/shows/" + show.id + "/prompts").then().statusCode(200).body("json_repair", is(1));
        api().delete("/api/shows/" + show.id + "/prompts/json_repair").then().statusCode(204);
        api().get("/api/shows/" + show.id + "/prompts").then().statusCode(200).body("size()", is(0));
    }

    @Test
    void dryRunUsesDraftAndPersistsNothing() {
        Show show = TestData.show("dry");
        Source source = TestData.source(show.id, "http://unused/feed");
        Item item = TestData.item(show.id, source.id, "http://story/1", Instant.now());
        api().body(Map.of("body", RANK_V2)).post("/api/prompts/rank/versions").then().statusCode(201);

        api().body(Map.of("showId", show.id)).post("/api/prompts/dry-run").then().statusCode(200)
                .body("promptVersions.rank", is(2))
                .body("scriptParts.size()", is(3))
                .body("title", is("Test episode"));

        assertTrue(model.userMessage(0).contains("MARKER-RANK-V2"));
        assertEquals(0L, (long) QuarkusTransaction.requiringNew().call(() -> Episode.count()));
        assertNull(QuarkusTransaction.requiringNew().call(() -> Item.<Item>findById(item.id).usedInEpisodeId));
        assertEquals(0L, (long) QuarkusTransaction.requiringNew().call(() -> VoiceCalibration.count()));
    }

    @Test
    void dryRunWithoutItemsIs409() {
        Show show = TestData.show("dry-empty");
        api().body(Map.of("showId", show.id)).post("/api/prompts/dry-run").then().statusCode(409)
                .body("error", containsString("No unused items"));
        assertTrue(model.requests.isEmpty());
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -q test -Dtest=PromptApiTest`
Expected: FAIL (404s; compilation errors once resources are referenced).

- [ ] **Step 3: Implement `NoCandidatesException` and `PromptDryRun`**

```java
package org.roncax.podcaster.prompts;

public class NoCandidatesException extends RuntimeException {
    public NoCandidatesException(String message) { super(message); }
}
```

```java
package org.roncax.podcaster.prompts;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.roncax.podcaster.config.PodcasterConfig;
import org.roncax.podcaster.domain.*;
import org.roncax.podcaster.generation.OutlinePlanner;
import org.roncax.podcaster.generation.Script;
import org.roncax.podcaster.generation.ScriptWriter;
import org.roncax.podcaster.generation.StoryRanker;
import org.roncax.podcaster.llm.ChatModelRegistry;
import org.roncax.podcaster.tts.VoiceCalibrationService;

/** Runs ranking and script writing with the draft prompts. Persists nothing. */
@ApplicationScoped
public class PromptDryRun {
    public record Result(Map<String, Integer> promptVersions, Selection selection, Outline outline,
                         String title, String description, List<String> scriptParts) {}

    @Inject ChatModelRegistry models;
    @Inject StoryRanker ranker;
    @Inject OutlinePlanner planner;
    @Inject ScriptWriter writer;
    @Inject VoiceCalibrationService calibration;
    @Inject PromptResolver resolver;
    @Inject PodcasterConfig config;

    public Result run(long showId) {
        Show show = QuarkusTransaction.requiringNew().call(() -> Show.<Show>findByIdOptional(showId)
                .orElseThrow(() -> new NotFoundException("Show " + showId + " not found")));
        Instant since = Instant.now().minus(config.selection().firstRunWindow());
        List<Item> candidates = QuarkusTransaction.requiringNew().call(() ->
                Item.unusedCandidates(show.id, since, config.selection().maxCandidates()));
        if (candidates.isEmpty()) {
            throw new NoCandidatesException("No unused items from the last " + config.selection().firstRunWindow().toHours()
                    + "h for show '" + show.name + "'; run ingestion first");
        }
        PromptSet prompts = resolver.resolve(show.id, PromptResolver.Mode.DRAFT);
        Selection selection = ranker.rank(models.get(show.effectiveRankerModel()), prompts,
                show.name, show.language, show.focusPrompt, candidates);
        Outline outline = planner.plan(selection, show.targetDurationMinutes,
                calibration.wordsPerMinute(show.voiceId, show.lengthScale));
        Map<Long, Item> items = candidates.stream().collect(Collectors.toMap(i -> i.id, Function.identity()));
        Script script = writer.write(models.get(show.writerModel), prompts, show, outline, items, LocalDate.now());
        return new Result(prompts.versions(PromptKey.values()), selection, outline,
                script.title(), script.description(), script.parts());
    }
}
```

- [ ] **Step 4: Implement the views, resources and mappers**

```java
package org.roncax.podcaster.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

public final class PromptViews {
    private PromptViews() {}

    public record PromptSummary(String key, String description, Integer production, Integer draft) {}
    public record VersionSummary(int version, String note, Instant createdAt, List<String> labels) {}
    public record VersionDetail(int version, String note, Instant createdAt, List<String> labels, String body) {}
    public record NewVersion(@NotBlank String body, String note) {}
    public record VersionRef(@NotNull Integer version) {}
    public record DryRunRequest(@NotNull Long showId) {}
}
```

```java
package org.roncax.podcaster.api;

import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestResponse;
import org.roncax.podcaster.api.PromptViews.*;
import org.roncax.podcaster.prompts.*;

@Path("/api/prompts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PromptResource {
    @Inject PromptRegistry registry;
    @Inject PromptDryRun dryRun;

    @GET
    public List<PromptSummary> list() {
        return Arrays.stream(PromptKey.values()).map(this::summary).toList();
    }

    @GET
    @Path("/{key}/versions")
    public List<VersionSummary> versions(@RestPath String key) {
        PromptKey k = key(key);
        return registry.versions(k).stream()
                .map(v -> new VersionSummary(v.version, v.note, v.createdAt, registry.labelsOf(k, v.version)))
                .toList();
    }

    @GET
    @Path("/{key}/versions/{n}")
    public VersionDetail version(@RestPath String key, @RestPath int n) {
        PromptKey k = key(key);
        return registry.version(k, n).map(v -> detail(k, v)).orElseThrow(NotFoundException::new);
    }

    @POST
    @Path("/{key}/versions")
    public RestResponse<VersionDetail> create(@RestPath String key, @Valid NewVersion request) {
        PromptKey k = key(key);
        PromptVersion v = registry.createVersion(k, request.body(), request.note());
        return RestResponse.status(Response.Status.CREATED, detail(k, v));
    }

    @PUT
    @Path("/{key}/labels/{label}")
    public PromptSummary setLabel(@RestPath String key, @RestPath String label, @Valid VersionRef request) {
        PromptKey k = key(key);
        PromptLabel l = PromptLabel.fromDb(label).orElseThrow(NotFoundException::new);
        registry.setLabel(k, l, request.version());
        return summary(k);
    }

    @POST
    @Path("/dry-run")
    public PromptDryRun.Result dryRun(@Valid DryRunRequest request) {
        return dryRun.run(request.showId());
    }

    private PromptSummary summary(PromptKey k) {
        Map<PromptLabel, Integer> labels = registry.labels(k);
        return new PromptSummary(k.dbKey(), k.description(), labels.get(PromptLabel.PRODUCTION), labels.get(PromptLabel.DRAFT));
    }

    private VersionDetail detail(PromptKey k, PromptVersion v) {
        return new VersionDetail(v.version, v.note, v.createdAt, registry.labelsOf(k, v.version), v.body);
    }

    static PromptKey key(String key) {
        return PromptKey.fromDb(key).orElseThrow(NotFoundException::new);
    }
}
```

```java
package org.roncax.podcaster.api;

import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jboss.resteasy.reactive.RestPath;
import org.roncax.podcaster.api.PromptViews.VersionRef;
import org.roncax.podcaster.prompts.PromptKey;
import org.roncax.podcaster.prompts.PromptRegistry;

@Path("/api/shows/{id}/prompts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ShowPromptResource {
    @Inject PromptRegistry registry;

    @GET
    public Map<String, Integer> overrides(@RestPath long id) {
        Map<String, Integer> out = new LinkedHashMap<>();
        registry.overrides(id).forEach((k, v) -> out.put(k.dbKey(), v));
        return out;
    }

    @PUT
    @Path("/{key}")
    public Map<String, Integer> pin(@RestPath long id, @RestPath String key, @Valid VersionRef request) {
        registry.pin(id, PromptResource.key(key), request.version());
        return overrides(id);
    }

    @DELETE
    @Path("/{key}")
    public void unpin(@RestPath long id, @RestPath String key) {
        PromptKey k = PromptResource.key(key);
        registry.unpin(id, k);
    }
}
```

Add to `ApiExceptionMappers`:

```java
    @ServerExceptionMapper
    public RestResponse<ErrorBody> invalidPrompt(org.roncax.podcaster.prompts.InvalidPromptException e) {
        return RestResponse.status(Response.Status.BAD_REQUEST, new ErrorBody("Invalid prompt", e.errors()));
    }

    @ServerExceptionMapper
    public RestResponse<ErrorBody> noCandidates(org.roncax.podcaster.prompts.NoCandidatesException e) {
        return RestResponse.status(Response.Status.CONFLICT, new ErrorBody(e.getMessage(), List.of()));
    }
```

- [ ] **Step 5: Run tests**

Run: `./mvnw -q test -Dtest=PromptApiTest`
Expected: PASS. (`versionsCannotBeEdited`: Quarkus REST answers 405 for a PUT on a path that only has GET.)

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Add prompt REST API, show pins and script-only dry-run

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01LbcoMupq1VXnfzdqroFmyp"
```

---

### Task 6: Admin UI — prompts pages, diff, dry-run, show overrides

**Files:**
- Create: `src/main/java/org/roncax/podcaster/prompts/LineDiff.java`
- Create: `src/main/java/org/roncax/podcaster/admin/PromptAdminViews.java`
- Create: `src/main/resources/templates/AdminResource/{prompts,prompt,dryRun}.html`
- Modify: `src/main/java/org/roncax/podcaster/admin/AdminResource.java`
- Modify: `src/main/resources/templates/base.html` (nav link), `src/main/resources/templates/AdminResource/show.html` (overrides section)
- Test: `src/test/java/org/roncax/podcaster/prompts/LineDiffTest.java`, `src/test/java/org/roncax/podcaster/admin/PromptAdminTest.java`

**Interfaces:**
- Consumes: `PromptRegistry`, `PromptDryRun`, `NoCandidatesException`, `InvalidPromptException` (Tasks 3, 5).
- Produces:
  - `LineDiff.diff(String before, String after) → List<LineDiff.Line>` where `record Line(char op, String text)` and `op` ∈ `' '`, `'+'`, `'-'`.
  - `PromptAdminViews` records: `PromptRow(String key, String description, Integer production, Integer draft)`, `VersionRow(int version, String note, String createdAt, String labels)`, `DiffRow(String css, String prefix, String text)`, `OverrideRow(String key, Integer pinned, List<Integer> versions)`.
  - Pages: `GET /admin/prompts`, `GET /admin/prompts/{key}?v=n`, `POST /admin/prompts/{key}/versions` (form `body`, `note`), `POST /admin/prompts/{key}/labels/{label}` (form `version`), `POST /admin/prompts/dry-run` (form `showId`, returns fragment), `POST /admin/shows/{id}/prompts` (form fields `rank`, `segment`, `framing`, `json_repair`: empty = unpin).
  - `AdminResource.Templates.show(...)` gains a trailing `List<OverrideRow> promptOverrides` parameter.

- [ ] **Step 1: Write the failing tests**

```java
package org.roncax.podcaster.prompts;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class LineDiffTest {
    @Test
    void marksAddedRemovedAndUnchangedLines() {
        List<LineDiff.Line> diff = LineDiff.diff("a\nb\nc", "a\nc\nd");
        assertEquals(List.of(
                new LineDiff.Line(' ', "a"),
                new LineDiff.Line('-', "b"),
                new LineDiff.Line(' ', "c"),
                new LineDiff.Line('+', "d")), diff);
    }

    @Test
    void identicalTextsHaveNoChanges() {
        assertTrue(LineDiff.diff("x\ny", "x\ny").stream().allMatch(l -> l.op() == ' '));
    }
}
```

```java
package org.roncax.podcaster.admin;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.domain.Show;
import org.roncax.podcaster.prompts.PromptKey;
import org.roncax.podcaster.prompts.PromptLabel;
import org.roncax.podcaster.prompts.PromptRegistry;
import org.roncax.podcaster.support.*;

@QuarkusTest
@WithTestResource(WireMockResource.class)
@WithTestResource(org.roncax.podcaster.support.PostgresResource.class)
class PromptAdminTest {
    static final String REPAIR_V2 = "JSON broken ({error}). Send only the object.";

    @InjectWireMock WireMockServer wm;
    @Inject PromptRegistry registry;

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
    void promptsPageListsAllPrompts() {
        admin().get("/admin/prompts").then().statusCode(200)
                .body(containsString("rank")).body(containsString("segment"))
                .body(containsString("framing")).body(containsString("json_repair"));
    }

    @Test
    void createVersionFromForm() {
        String location = admin().formParam("body", REPAIR_V2).formParam("note", "shorter")
                .post("/admin/prompts/json_repair/versions").then().statusCode(303).extract().header("Location");
        assertTrue(location.endsWith("/admin/prompts/json_repair?v=2"), location);
        admin().get(location).then().statusCode(200).body(containsString("shorter")).body(containsString("diff-add"));
    }

    @Test
    void invalidFormShowsErrorsAndKeepsText() {
        admin().formParam("body", "no placeholders").post("/admin/prompts/json_repair/versions").then().statusCode(200)
                .body(containsString("Missing required variable {error}"))
                .body(containsString("no placeholders"));
    }

    @Test
    void promoteFromForm() {
        registry.createVersion(PromptKey.JSON_REPAIR, REPAIR_V2, null);
        admin().formParam("version", "2").post("/admin/prompts/json_repair/labels/production").then().statusCode(303);
        assertEquals(2, registry.labels(PromptKey.JSON_REPAIR).get(PromptLabel.PRODUCTION));
    }

    @Test
    void showOverridesFromForm() {
        Show show = TestData.show("ovr");
        admin().formParam("rank", "1").formParam("segment", "").formParam("framing", "").formParam("json_repair", "")
                .post("/admin/shows/" + show.id + "/prompts").then().statusCode(303);
        assertEquals(Map.of(PromptKey.RANK, 1), registry.overrides(show.id));
        admin().get("/admin/shows/" + show.id).then().statusCode(200).body(containsString("Prompt overrides"));
        admin().formParam("rank", "").formParam("segment", "").formParam("framing", "").formParam("json_repair", "")
                .post("/admin/shows/" + show.id + "/prompts").then().statusCode(303);
        assertTrue(registry.overrides(show.id).isEmpty());
    }

    @Test
    void dryRunFragmentExplainsMissingItems() {
        Show show = TestData.show("dryui");
        admin().formParam("showId", String.valueOf(show.id)).post("/admin/prompts/dry-run").then().statusCode(200)
                .body(containsString("No unused items"));
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -q test -Dtest='LineDiffTest,PromptAdminTest'`
Expected: compilation FAIL (`LineDiff` not found).

- [ ] **Step 3: Implement `LineDiff`**

```java
package org.roncax.podcaster.prompts;

import java.util.ArrayList;
import java.util.List;

/** Minimal line diff (longest common subsequence) for showing prompt changes. */
public final class LineDiff {
    public record Line(char op, String text) {}

    private LineDiff() {}

    public static List<Line> diff(String before, String after) {
        String[] a = before.split("\n", -1);
        String[] b = after.split("\n", -1);
        int[][] lcs = new int[a.length + 1][b.length + 1];
        for (int i = a.length - 1; i >= 0; i--) {
            for (int j = b.length - 1; j >= 0; j--) {
                lcs[i][j] = a[i].equals(b[j]) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<Line> out = new ArrayList<>();
        int i = 0, j = 0;
        while (i < a.length && j < b.length) {
            if (a[i].equals(b[j])) {
                out.add(new Line(' ', a[i++]));
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                out.add(new Line('-', a[i++]));
            } else {
                out.add(new Line('+', b[j++]));
            }
        }
        while (i < a.length) out.add(new Line('-', a[i++]));
        while (j < b.length) out.add(new Line('+', b[j++]));
        return out;
    }
}
```

- [ ] **Step 4: Implement the admin view records**

```java
package org.roncax.podcaster.admin;

import java.util.List;

public final class PromptAdminViews {
    private PromptAdminViews() {}

    public record PromptRow(String key, String description, Integer production, Integer draft) {}
    public record VersionRow(int version, String note, String createdAt, String labels) {}
    public record DiffRow(String css, String prefix, String text) {}
    public record OverrideRow(String key, Integer pinned, List<Integer> versions) {}
}
```

- [ ] **Step 5: Extend `AdminResource`**

Add imports `org.roncax.podcaster.prompts.*` and `org.roncax.podcaster.admin.PromptAdminViews.*`; inject `@Inject PromptRegistry prompts;` and `@Inject PromptDryRun dryRun;`.

Extend `Templates` (replace the `show` declaration and add the three new ones):

```java
        static native TemplateInstance show(Show show, ShowForm form, String formAction, List<Source> sources, List<Run> runs,
                                            List<Episode> episodes, List<String> errors, Set<String> models, Set<String> voices,
                                            Set<String> connectors, List<OverrideRow> promptOverrides);
        static native TemplateInstance prompts(List<PromptRow> rows);
        static native TemplateInstance prompt(String key, String description, Integer production, Integer draft,
                                              List<VersionRow> versions, int shownVersion, String shownBody,
                                              List<DiffRow> diff, String editorBody, String note, List<String> errors,
                                              List<Show> shows);
        static native TemplateInstance dryRun(PromptDryRun.Result result, String error);
```

In `showTemplate(...)`, pass the overrides as the last argument:

```java
        return Templates.show(show, form, "/admin/shows/" + show.id, sources, recentRuns(show.id), episodes, errors,
                models.availableNames(), voices(), connectors.types(), overrideRows(show.id));
```

Add the endpoints and helpers:

```java
    @GET
    @Path("/prompts")
    public TemplateInstance promptsPage() {
        List<PromptRow> rows = new ArrayList<>();
        for (PromptKey key : PromptKey.values()) {
            Map<PromptLabel, Integer> labels = prompts.labels(key);
            rows.add(new PromptRow(key.dbKey(), key.description(), labels.get(PromptLabel.PRODUCTION), labels.get(PromptLabel.DRAFT)));
        }
        return Templates.prompts(rows);
    }

    @GET
    @Path("/prompts/{key}")
    public TemplateInstance promptPage(@RestPath String key, @org.jboss.resteasy.reactive.RestQuery Integer v) {
        PromptKey k = promptKey(key);
        Map<PromptLabel, Integer> labels = prompts.labels(k);
        int shown = v != null ? v : labels.get(PromptLabel.DRAFT);
        String draftBody = prompts.version(k, labels.get(PromptLabel.DRAFT)).orElseThrow().body;
        return promptTemplate(k, shown, draftBody, null, List.of());
    }

    @POST
    @Path("/prompts/{key}/versions")
    public Response createPromptVersion(@RestPath String key, @RestForm String body, @RestForm String note) {
        PromptKey k = promptKey(key);
        try {
            PromptVersion created = prompts.createVersion(k, body, note);
            return Response.seeOther(URI.create("/admin/prompts/" + k.dbKey() + "?v=" + created.version)).build();
        } catch (InvalidPromptException e) {
            int shown = prompts.labels(k).get(PromptLabel.DRAFT);
            return Response.ok(promptTemplate(k, shown, body, note, e.errors())).build();
        }
    }

    @POST
    @Path("/prompts/{key}/labels/{label}")
    public Response setPromptLabel(@RestPath String key, @RestPath String label, @RestForm int version) {
        PromptKey k = promptKey(key);
        PromptLabel l = PromptLabel.fromDb(label).orElseThrow(NotFoundException::new);
        prompts.setLabel(k, l, version);
        return Response.seeOther(URI.create("/admin/prompts/" + k.dbKey() + "?v=" + version)).build();
    }

    @POST
    @Path("/prompts/dry-run")
    public TemplateInstance dryRunFragment(@RestForm long showId) {
        try {
            return Templates.dryRun(dryRun.run(showId), null);
        } catch (NoCandidatesException | org.roncax.podcaster.llm.GenerationException | org.roncax.podcaster.llm.UnknownModelException e) {
            return Templates.dryRun(null, e.getMessage());
        }
    }

    @POST
    @Path("/shows/{id}/prompts")
    public Response saveShowPrompts(@RestPath long id, @RestForm String rank, @RestForm String segment,
                                    @RestForm String framing, @RestForm("json_repair") String jsonRepair) {
        Map<PromptKey, String> form = Map.of(PromptKey.RANK, nz(rank), PromptKey.SEGMENT, nz(segment),
                PromptKey.FRAMING, nz(framing), PromptKey.JSON_REPAIR, nz(jsonRepair));
        form.forEach((key, value) -> {
            if (value.isBlank()) prompts.unpin(id, key);
            else prompts.pin(id, key, Integer.parseInt(value.trim()));
        });
        return Response.seeOther(URI.create("/admin/shows/" + id)).build();
    }

    private TemplateInstance promptTemplate(PromptKey k, int shown, String editorBody, String note, List<String> errors) {
        Map<PromptLabel, Integer> labels = prompts.labels(k);
        Integer production = labels.get(PromptLabel.PRODUCTION);
        String shownBody = prompts.version(k, shown).orElseThrow(NotFoundException::new).body;
        String productionBody = prompts.version(k, production).orElseThrow().body;
        List<DiffRow> diff = LineDiff.diff(productionBody, shownBody).stream()
                .map(l -> new DiffRow(l.op() == '+' ? "diff-add" : l.op() == '-' ? "diff-del" : "diff-same",
                        String.valueOf(l.op()), l.text()))
                .toList();
        List<VersionRow> versions = prompts.versions(k).stream()
                .map(v -> new VersionRow(v.version, v.note == null ? "" : v.note, v.createdAt.toString(),
                        String.join(", ", prompts.labelsOf(k, v.version))))
                .toList();
        return Templates.prompt(k.dbKey(), k.description(), production, labels.get(PromptLabel.DRAFT), versions, shown,
                shownBody, diff, editorBody, note, errors, Show.listAll());
    }

    private List<OverrideRow> overrideRows(long showId) {
        Map<PromptKey, Integer> pinned = prompts.overrides(showId);
        List<OverrideRow> rows = new ArrayList<>();
        for (PromptKey key : PromptKey.values()) {
            List<Integer> versions = prompts.versions(key).stream().map(v -> v.version).toList();
            rows.add(new OverrideRow(key.dbKey(), pinned.get(key), versions));
        }
        return rows;
    }

    private static PromptKey promptKey(String key) {
        return PromptKey.fromDb(key).orElseThrow(NotFoundException::new);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
```

- [ ] **Step 6: Write the templates**

`src/main/resources/templates/AdminResource/prompts.html`:

```html
{#include base}
{#title}Prompts · Podcaster{/title}
{#body}
<h1>Prompts</h1>
<p>Every prompt sent to the LLM. Versions are immutable; <strong>production</strong> is used by runs, <strong>draft</strong> by dry-runs.</p>
<table>
  <thead><tr><th>Prompt</th><th>Purpose</th><th>Production</th><th>Draft</th></tr></thead>
  <tbody>
  {#for r in rows}
    <tr>
      <td><a href="/admin/prompts/{r.key}"><code>{r.key}</code></a></td>
      <td>{r.description}</td>
      <td>v{r.production}</td>
      <td>v{r.draft}</td>
    </tr>
  {/for}
  </tbody>
</table>
{/body}
{/include}
```

`src/main/resources/templates/AdminResource/prompt.html`:

```html
{#include base}
{#title}{key} · Prompts · Podcaster{/title}
{#body}
<style>
  .diff { font-family: monospace; white-space: pre-wrap; font-size: .85rem; }
  .diff-add { background: rgba(46, 160, 67, .2); }
  .diff-del { background: rgba(248, 81, 73, .2); text-decoration: line-through; }
</style>
<p><a href="/admin/prompts">← Prompts</a></p>
<hgroup><h1><code>{key}</code></h1><p>{description} · production v{production} · draft v{draft}</p></hgroup>

<h2>Versions</h2>
<table>
  <thead><tr><th>Version</th><th>Note</th><th>Created</th><th>Labels</th><th></th></tr></thead>
  <tbody>
  {#for v in versions}
    <tr>
      <td><a href="/admin/prompts/{key}?v={v.version}">v{v.version}</a></td>
      <td>{v.note}</td><td><small>{v.createdAt}</small></td><td>{v.labels}</td>
      <td>
        <form method="post" action="/admin/prompts/{key}/labels/production" style="display:inline"><input type="hidden" name="version" value="{v.version}"><button class="outline" type="submit">Promote to production</button></form>
        <form method="post" action="/admin/prompts/{key}/labels/draft" style="display:inline"><input type="hidden" name="version" value="{v.version}"><button class="secondary outline" type="submit">Set as draft</button></form>
      </td>
    </tr>
  {/for}
  </tbody>
</table>

<h2>v{shownVersion} compared with production</h2>
<div class="diff">{#for d in diff}<div class="{d.css}">{d.prefix} {d.text}</div>{/for}</div>

<h2>Dry-run the draft</h2>
<form hx-post="/admin/prompts/dry-run" hx-target="#dry-run" hx-indicator="#dry-run-busy">
  <select name="showId">{#for s in shows}<option value="{s.id}">{s.name}</option>{/for}</select>
  <button type="submit">Dry-run on show</button> <span id="dry-run-busy" class="htmx-indicator">running…</span>
</form>
<div id="dry-run"></div>

<h2>New version</h2>
{#if errors}<article role="alert"><ul>{#for e in errors}<li>{e}</li>{/for}</ul></article>{/if}
<form method="post" action="/admin/prompts/{key}/versions">
  <textarea name="body" rows="18" style="font-family: monospace">{editorBody}</textarea>
  <label>Change note <input name="note" value="{note ?: ''}"></label>
  <button type="submit">Save as new draft version</button>
</form>
{/body}
{/include}
```

`src/main/resources/templates/AdminResource/dryRun.html`:

```html
<article>
{#if error}
  <p role="alert">{error}</p>
{#else}
  <p><strong>{result.title}</strong> · prompt versions {result.promptVersions}</p>
  <p class="script">{result.description}</p>
  {#for part in result.scriptParts}<p class="script">{part}</p>{/for}
{/if}
</article>
```

In `base.html`, add a nav item before the API link:

```html
      <li><a href="/admin/prompts">Prompts</a></li>
```

In `show.html`, add before `<h2>Settings</h2>`:

```html
<h2>Prompt overrides</h2>
<form method="post" action="/admin/shows/{show.id}/prompts">
  <table>
    <thead><tr><th>Prompt</th><th>Version</th></tr></thead>
    <tbody>
    {#for o in promptOverrides}
      <tr>
        <td><a href="/admin/prompts/{o.key}"><code>{o.key}</code></a></td>
        <td>
          <select name="{o.key}">
            <option value="">(production label)</option>
            {#for v in o.versions}<option value="{v}" {#if v == o.pinned}selected{/if}>v{v}</option>{/for}
          </select>
        </td>
      </tr>
    {/for}
    </tbody>
  </table>
  <button class="outline" type="submit">Save overrides</button>
</form>
```

- [ ] **Step 7: Run tests**

Run: `./mvnw -q test -Dtest='LineDiffTest,PromptAdminTest,AdminTest'`
Expected: PASS. Qute validates the checked templates at build time; fix any expression it reports.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "Add admin UI for prompts: versions, diff, promote, dry-run and show overrides

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01LbcoMupq1VXnfzdqroFmyp"
```

---

### Task 7: Documentation and final verification

**Files:**
- Modify: `README.md` (new "Prompts" section)
- Modify: `docs/superpowers/specs/2026-10-06-prompt-registry-design.md` (§4.1 surrogate ids, §4.3 `{contract}` variable and framing contract)

**Interfaces:**
- Consumes: everything above.
- Produces: user documentation; spec matches the implementation.

- [ ] **Step 1: Add the README section** (after "LLM slots")

```markdown
## Prompts

Every prompt sent to the LLM (`rank`, `segment`, `framing`, `json_repair`) lives in the database as immutable versions, editable at `/admin/prompts` or via `/api/prompts`.

- Saving creates a new version labelled **draft**. Runs use **production**; promoting a version (or an older one, to roll back) takes effect on the next run, no restart.
- **Dry-run** renders a full script for a show with the draft prompts without publishing anything.
- A show can pin a specific version of any prompt (show page → Prompt overrides).
- Templates use Qute syntax: `{variable}`, `{#if focus}…{/if}`. Allowed variables are listed per prompt; `{contract}` (required in `rank` and `framing`) inserts the JSON format the app parses. Invalid templates are rejected on save.
- Each episode records the prompt versions that produced it (`promptVersions` in `/api/episodes/{id}`).
```

- [ ] **Step 2: Sync the spec with the implementation**

In §4.1 replace the `prompt_labels` and `show_prompt_overrides` lines with:

```
prompt_labels         id bigserial PK, prompt_key → prompts(key), label varchar(20) check in ('production','draft'),
                      version_id → prompt_versions(id), unique (prompt_key, label)
show_prompt_overrides id bigserial PK, show_id → shows(id) on delete cascade, prompt_key → prompts(key),
                      version_id → prompt_versions(id), unique (show_id, prompt_key)
```

Replace §4.3 with:

```markdown
### 4.3 Fixed output contract (code)

The contract text is supplied by `PromptKey` and inserted where the body places the **required** `{contract}` variable, so an edit can move it but never remove it:

- `rank`: "Return ONLY a JSON object, no prose, with this shape:\n{"clusters":[{"headline":"...","itemIds":[1,2],"importance":7}]}"
- `framing`: the JSON shape line `{"title":"...","description":"...","intro":"...","outro":"..."}`; the field descriptions above it are editable text.
- `segment`, `json_repair`: no contract.

The `TASK: <NAME>` header is prepended by code (not for `json_repair`). With seeded v1 the rendered prompts equal the pre-registry text exactly (golden test against `LegacyPrompts`).
```

Also in §3 add `contract` to the variables of `rank` and `framing` (required).

- [ ] **Step 3: Run the full suite**

Run: `./mvnw -q test`
Expected: PASS.

- [ ] **Step 4: Rebuild and smoke-test the container (Docker available locally)**

```bash
docker compose up -d --build podcaster
for i in $(seq 1 40); do curl -fs localhost:8080/q/health >/dev/null && break; sleep 3; done
KEY=$(grep ^PODCASTER_API_KEY .env | cut -d= -f2)
curl -s -H "X-API-Key: $KEY" localhost:8080/api/prompts
```

Expected: health UP (Flyway applies V2 on the existing database, the seeder inserts v1), and the prompts list shows 4 prompts at production v1 / draft v1.

- [ ] **Step 5: Commit**

```bash
git add README.md docs/superpowers/specs/2026-10-06-prompt-registry-design.md
git commit -m "Document the prompt registry and sync the spec with the implementation

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01LbcoMupq1VXnfzdqroFmyp"
```
