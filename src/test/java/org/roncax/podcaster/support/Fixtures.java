package org.roncax.podcaster.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

public final class Fixtures {
    private Fixtures() {}

    public static byte[] bytes(String name) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/fixtures/" + name)) {
            if (in == null) throw new IllegalArgumentException("No fixture " + name);
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
