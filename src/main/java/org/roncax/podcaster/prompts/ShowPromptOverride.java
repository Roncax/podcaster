package org.roncax.podcaster.prompts;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

@Entity
@Table(name = "show_prompt_overrides")
public class ShowPromptOverride extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Long showId;
    public String promptKey;
    public Long versionId;
}
