package org.roncax.podcaster.generation;

import java.util.List;

public record Script(String title, String description, List<String> parts) {
    public String joined() {
        return String.join("\n\n", parts);
    }

    public int wordCount() {
        return parts.stream().mapToInt(p -> p.isBlank() ? 0 : p.trim().split("\\s+").length).sum();
    }
}
