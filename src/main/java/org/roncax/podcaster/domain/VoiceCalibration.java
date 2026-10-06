package org.roncax.podcaster.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

@Entity
@Table(name = "voice_calibrations")
public class VoiceCalibration extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public String voiceId;
    public double lengthScale;
    public double wordsPerMinute;
    public int samples;
}
