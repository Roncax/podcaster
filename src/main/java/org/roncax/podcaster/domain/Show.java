package org.roncax.podcaster.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Optional;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "shows")
public class Show extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public String name;
    public String slug;
    public String description;
    public String language;
    public String voiceId;
    public double lengthScale = 1.0;
    public String writerModel;
    public String rankerModel;
    public String focusPrompt;
    public int targetDurationMinutes = 20;
    public int minItems = 3;
    public String cron;
    public boolean enabled = true;
    public int retainEpisodes = 30;
    public String feedToken;
    @CreationTimestamp public Instant createdAt;
    @UpdateTimestamp public Instant updatedAt;

    public static Optional<Show> findBySlug(String slug) {
        return find("slug", slug).firstResultOptional();
    }

    public String effectiveRankerModel() {
        return rankerModel == null || rankerModel.isBlank() ? writerModel : rankerModel;
    }
}
