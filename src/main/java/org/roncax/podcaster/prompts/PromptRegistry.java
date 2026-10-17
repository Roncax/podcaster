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
