package org.roncax.podcaster.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "sources")
public class Source extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Long showId;
    public String connectorType;
    @JdbcTypeCode(SqlTypes.JSON) public Map<String, String> config = new HashMap<>();
    public boolean fetchFullText = true;
    public boolean enabled = true;
    public Instant lastFetchedAt;
    public String lastError;
    @CreationTimestamp public Instant createdAt;
    @UpdateTimestamp public Instant updatedAt;

    public String label() {
        return connectorType + " " + config.getOrDefault("url", "#" + id);
    }
}
