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
