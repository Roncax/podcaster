package org.roncax.podcaster.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "runs")
public class Run extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Long showId;
    @Enumerated(EnumType.STRING) public RunTrigger trigger;
    @Enumerated(EnumType.STRING) public RunStage stage = RunStage.INGEST;
    @Enumerated(EnumType.STRING) public RunStatus status = RunStatus.RUNNING;
    public Instant since;
    public int attempt = 1;
    public String error;
    public Instant startedAt = Instant.now();
    public Instant finishedAt;
}
