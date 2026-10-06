package org.roncax.podcaster.prompts;

import io.quarkus.qute.Engine;
import io.quarkus.qute.Expression;
import io.quarkus.qute.IfSectionHelper;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateException;
import io.quarkus.qute.ValueResolvers;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renders prompt bodies with a minimal standalone Qute engine. Deliberately not the Quarkus-managed engine:
 * only plain variables and {#if}/{#else} exist — no namespaces (inject:, config:), no property or method
 * access, no include/each/let — so a template can neither read secrets nor pass validation and then fail.
 */
@ApplicationScoped
public class PromptRenderer {
    private final Engine engine = Engine.builder()
            .addSectionHelper(new IfSectionHelper.Factory())
            .addValueResolver(ValueResolvers.mapResolver())
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
            if (expression.getParts().size() > 1) {
                errors.add("Property or method access is not allowed: {" + expression.toOriginalString() + "}");
                continue;
            }
            used.add(expression.getParts().get(0).getName());
        }
        for (String name : used) {
            if (!key.variables().contains(name)) errors.add("Unknown variable '" + name + "'");
        }
        for (String name : new TreeSet<>(key.required())) {
            if (!used.contains(name)) errors.add("Missing required variable {" + name + "}");
        }
        if (errors.isEmpty()) errors.addAll(trialRender(key, template));
        return errors;
    }

    /** Renders with sample values, optional variables both set and null; the contract must appear every time. */
    private List<String> trialRender(PromptKey key, Template template) {
        List<String> errors = new ArrayList<>();
        for (boolean optionalSet : new boolean[] {true, false}) {
            Map<String, Object> vars = new HashMap<>();
            for (String name : key.variables()) {
                boolean optional = !key.required().contains(name);
                vars.put(name, optional && !optionalSet ? null : sample(name));
            }
            if (key.contract() != null) vars.put("contract", key.contract());
            try {
                String out = template.data(vars).render();
                if (key.contract() != null && !out.contains(key.contract())) {
                    errors.add("The {contract} placeholder must always be rendered (it cannot be inside a condition)");
                    break;
                }
            } catch (RuntimeException e) {
                errors.add("Template fails to render: " + e.getMessage());
                break;
            }
        }
        return errors;
    }

    private static Object sample(String name) {
        return name.equals("words") ? Integer.valueOf(400) : "sample " + name;
    }
}
