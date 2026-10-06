package org.roncax.podcaster.runs;

public class RunAlreadyActiveException extends RuntimeException {
    public RunAlreadyActiveException(long showId) {
        super("Show " + showId + " already has a running run");
    }
}
