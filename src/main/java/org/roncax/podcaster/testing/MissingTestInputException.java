package org.roncax.podcaster.testing;

/** A test step needs a result from an earlier step (e.g. a prompt dry-run) that the session does not have. */
public class MissingTestInputException extends RuntimeException {
    public MissingTestInputException(String message) {
        super(message);
    }
}
