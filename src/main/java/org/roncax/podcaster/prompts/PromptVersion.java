package org.roncax.podcaster.prompts;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.hibernate.annotations.Immutable;

/** One immutable prompt version. Never updated or deleted. */
@Entity
@Immutable
@Table(name = "prompt_versions")
public class PromptVersion extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Long showId;
    public String promptKey;
    public int version;
    public String body;
    public String note;
    public Instant createdAt = Instant.now();

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
}
