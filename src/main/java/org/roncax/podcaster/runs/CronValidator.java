package org.roncax.podcaster.runs;

import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;
import java.util.Optional;

public final class CronValidator {
    private static final CronParser PARSER = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX));

    private CronValidator() {}

    public static Optional<String> validate(String expression) {
        if (expression == null || expression.isBlank()) return Optional.empty();
        try {
            PARSER.parse(expression.trim()).validate();
            return Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.of("Invalid cron expression '" + expression + "' (use 5-field Unix syntax, e.g. '0 7 * * *'): " + e.getMessage());
        }
    }
}
