package org.roncax.podcaster.prompts;

import java.util.Arrays;
import java.util.Optional;

public enum PromptLabel {
    PRODUCTION("production"), DRAFT("draft");

    private final String dbValue;

    PromptLabel(String dbValue) { this.dbValue = dbValue; }

    public String dbValue() { return dbValue; }

    public static Optional<PromptLabel> fromDb(String value) {
        return Arrays.stream(values()).filter(l -> l.dbValue.equals(value)).findFirst();
    }
}
