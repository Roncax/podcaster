package org.roncax.podcaster.api;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ApiKeyGuardTest {
    @Test
    void rejectsPlaceholdersAndShortKeys() {
        assertTrue(ApiKeyGuard.problem("change-me").isPresent());
        assertTrue(ApiKeyGuard.problem("change-me-to-a-long-random-string").isPresent());
        assertTrue(ApiKeyGuard.problem("short").isPresent());
        assertTrue(ApiKeyGuard.problem("   ").isPresent());
    }

    @Test
    void acceptsLongRandomKey() {
        assertTrue(ApiKeyGuard.problem("k3J9-x8vQ-pL2m-Zt7w").isEmpty());
    }
}
