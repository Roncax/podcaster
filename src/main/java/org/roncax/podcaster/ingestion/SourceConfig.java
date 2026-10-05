package org.roncax.podcaster.ingestion;

import java.util.Map;
import java.util.Optional;

public record SourceConfig(Map<String, String> values) {
    public SourceConfig {
        values = values == null ? Map.of() : Map.copyOf(values);
    }

    public String require(String key) {
        String v = values.get(key);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("Missing source config '" + key + "'");
        return v.trim();
    }

    public Optional<String> get(String key) {
        return Optional.ofNullable(values.get(key)).filter(v -> !v.isBlank());
    }
}
