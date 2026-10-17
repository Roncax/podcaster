package org.roncax.podcaster.testing;

public class TestStepBusyException extends RuntimeException {
    public TestStepBusyException(long showId) {
        super("A test step is already running for show " + showId);
    }
}
