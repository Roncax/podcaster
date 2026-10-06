package org.roncax.podcaster.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "episodes")
public class Episode extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Long runId;
    public Long showId;
    public String title;
    public String description;
    @JdbcTypeCode(SqlTypes.JSON) public Selection selection;
    @JdbcTypeCode(SqlTypes.JSON) public Outline outline;
    @JdbcTypeCode(SqlTypes.JSON) public List<String> scriptParts;
    public String script;
    public String audioPath;
    public Double durationSeconds;
    public Long sizeBytes;
    public Instant publishedAt;
    @CreationTimestamp public Instant createdAt;

    public static Optional<Episode> findByRun(long runId) {
        return find("runId", runId).firstResultOptional();
    }
}
