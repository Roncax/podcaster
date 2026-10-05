package org.roncax.podcaster.tts;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.support.TestData;
import org.roncax.podcaster.support.WireMockResource;

@QuarkusTest
@WithTestResource(WireMockResource.class)
@WithTestResource(org.roncax.podcaster.support.PostgresResource.class)
class VoiceCalibrationServiceTest {
    @Inject VoiceCalibrationService calibration;

    @BeforeEach
    void clean() { TestData.cleanDb(); }

    @Test
    void defaultsThenLearnsFromEpisodes() {
        assertEquals(150.0, calibration.wordsPerMinute("v1", 1.0), 0.001);
        assertEquals(180.0, calibration.record("v1", 1.0, 300, 100), 0.001); // first sample replaces default
        assertEquals(0.7 * 180 + 0.3 * 150, calibration.record("v1", 1.0, 300, 120), 0.001);
        assertEquals(171.0, calibration.wordsPerMinute("v1", 1.0), 0.001);
    }

    @Test
    void lengthScalesAreCalibratedSeparately() {
        calibration.record("v2", 1.0, 300, 100);
        assertEquals(150.0, calibration.wordsPerMinute("v2", 1.2), 0.001);
    }

    @Test
    void ignoresDegenerateSamples() {
        assertEquals(150.0, calibration.record("v3", 1.0, 0, 100), 0.001);
        assertEquals(150.0, calibration.record("v3", 1.0, 100, 0), 0.001);
    }
}
