package org.roncax.podcaster.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "items")
public class Item extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Long showId;
    public Long sourceId;
    public String url;
    public String contentHash;
    public String title;
    public String author;
    public Instant publishedAt;
    public Instant fetchedAt;
    public String summary;
    public String fullText;
    public Long usedInEpisodeId;

    public Instant effectiveDate() {
        return publishedAt != null ? publishedAt : fetchedAt;
    }

    /** Best available body text: full text, else summary, else empty. */
    public String bestText() {
        if (fullText != null && !fullText.isBlank()) return fullText;
        return summary == null ? "" : summary;
    }
}
