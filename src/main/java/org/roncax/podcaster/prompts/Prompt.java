package org.roncax.podcaster.prompts;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "prompts")
public class Prompt extends PanacheEntityBase {
    @Id public String key;
    public String description;
}
