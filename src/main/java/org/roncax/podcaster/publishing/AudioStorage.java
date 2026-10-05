package org.roncax.podcaster.publishing;

import java.io.IOException;
import java.nio.file.Path;

public interface AudioStorage {
    /** Moves {@code source} into storage under {@code key} (e.g. "daily/42.mp3"). */
    void store(String key, Path source) throws IOException;

    Path resolve(String key);

    boolean exists(String key);

    void delete(String key) throws IOException;
}
