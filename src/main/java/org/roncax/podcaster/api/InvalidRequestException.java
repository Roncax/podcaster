package org.roncax.podcaster.api;

import java.util.List;

public class InvalidRequestException extends RuntimeException {
    private final List<String> errors;

    public InvalidRequestException(List<String> errors) {
        super(String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() { return errors; }
}
