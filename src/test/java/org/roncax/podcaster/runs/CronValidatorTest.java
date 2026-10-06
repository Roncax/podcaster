package org.roncax.podcaster.runs;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CronValidatorTest {
    @Test
    void acceptsUnixCron() {
        assertTrue(CronValidator.validate("0 7 * * *").isEmpty());
        assertTrue(CronValidator.validate("*/30 6-22 * * 1-5").isEmpty());
        assertTrue(CronValidator.validate("").isEmpty());
        assertTrue(CronValidator.validate(null).isEmpty());
    }

    @Test
    void rejectsInvalidCron() {
        assertTrue(CronValidator.validate("0 7 * *").isPresent());
        assertTrue(CronValidator.validate("61 * * * *").isPresent());
        assertTrue(CronValidator.validate("0 0 7 * * ?").isPresent(), "Quartz syntax is not accepted");
    }
}
