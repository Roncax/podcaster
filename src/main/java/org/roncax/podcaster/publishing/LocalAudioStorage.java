package org.roncax.podcaster.publishing;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.roncax.podcaster.config.PodcasterConfig;

@ApplicationScoped
public class LocalAudioStorage implements AudioStorage {
    private final Path root;

    @Inject
    public LocalAudioStorage(PodcasterConfig config) {
        this(Path.of(config.storage().root()));
    }

    public LocalAudioStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() { return root; }

    @Override
    public Path resolve(String key) {
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root) || path.equals(root)) throw new IllegalArgumentException("Invalid storage key: " + key);
        return path;
    }

    @Override
    public void store(String key, Path source) throws IOException {
        Path target = resolve(key);
        Files.createDirectories(target.getParent());
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public boolean exists(String key) {
        return Files.exists(resolve(key));
    }

    @Override
    public void delete(String key) throws IOException {
        Files.deleteIfExists(resolve(key));
    }
}
