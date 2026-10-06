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
