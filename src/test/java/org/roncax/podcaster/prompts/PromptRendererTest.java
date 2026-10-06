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

    @Test
    void rejectsPropertyAndMethodAccess() {
        List<String> errors = renderer.validate(PromptKey.SEGMENT, "{sources} {words} {headline.trim} {focus.length}");
        assertEquals(2, errors.stream().filter(e -> e.startsWith("Property or method access is not allowed")).count(), errors.toString());
    }

    @Test
    void rejectsSectionsOtherThanIf() {
        assertTrue(renderer.validate(PromptKey.SEGMENT, "{sources} {words} {#include foo /}").get(0).startsWith("Template syntax error"));
        assertTrue(renderer.validate(PromptKey.SEGMENT, "{#each sources}{it}{/each} {words}").get(0).startsWith("Template syntax error"));
    }

    @Test
    void contractMustAlwaysBeRendered() {
        List<String> errors = renderer.validate(PromptKey.RANK, "{items}\n{#if showName == 'x'}{contract}{/if}");
        assertTrue(errors.stream().anyMatch(e -> e.contains("{contract}") && e.contains("always")), errors.toString());
        errors = renderer.validate(PromptKey.FRAMING, "{stories}\n{#if focus}{contract}{/if}");
        assertTrue(errors.isEmpty() || errors.stream().anyMatch(e -> e.contains("Unknown variable 'focus'")), errors.toString());
    }
}
