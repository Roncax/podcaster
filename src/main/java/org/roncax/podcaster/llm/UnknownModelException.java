package org.roncax.podcaster.llm;

import java.util.Set;

public class UnknownModelException extends RuntimeException {
    public UnknownModelException(String name, Set<String> available) {
        super("Unknown or disabled model '" + name + "'. Available: " + available);
    }
}
