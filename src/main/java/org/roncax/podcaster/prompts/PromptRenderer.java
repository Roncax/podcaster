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
