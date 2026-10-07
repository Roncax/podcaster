package org.roncax.podcaster.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "shows")
public class Show extends PanacheEntityBase {
    private static final SecureRandom RANDOM = new SecureRandom();

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
    /** Secret path segment of the feed and media URLs: knowing the slug alone is not enough to listen. */
    public String feedToken;
    @CreationTimestamp public Instant createdAt;
    @UpdateTimestamp public Instant updatedAt;

    public static Optional<Show> findBySlug(String slug) {
        return find("slug", slug).firstResultOptional();
    }

    public static Optional<Show> findByFeedToken(String token) {
        return find("feedToken", token).firstResultOptional();
    }

    public static String newFeedToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    @PrePersist
    void assignFeedToken() {
        if (feedToken == null) feedToken = newFeedToken();
    }

    public String feedPath() {
        return "/feeds/" + feedToken + "/" + slug + ".xml";
    }

    public String mediaPath(String audioPath) {
        return "/media/" + feedToken + "/" + audioPath;
    }

    public String effectiveRankerModel() {
        return rankerModel == null || rankerModel.isBlank() ? writerModel : rankerModel;
    }
}
