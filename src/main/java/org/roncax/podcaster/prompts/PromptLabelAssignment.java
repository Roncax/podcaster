package org.roncax.podcaster.prompts;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

@Entity
@Table(name = "prompt_labels")
public class PromptLabelAssignment extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public String promptKey;
    public String label;
    public Long versionId;
}
