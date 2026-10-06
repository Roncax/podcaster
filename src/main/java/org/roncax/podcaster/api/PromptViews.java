package org.roncax.podcaster.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

public final class PromptViews {
    private PromptViews() {}

    public record PromptSummary(String key, String description, Integer production, Integer draft) {}
    public record VersionSummary(int version, String note, Instant createdAt, List<String> labels) {}
    public record VersionDetail(int version, String note, Instant createdAt, List<String> labels, String body) {}
    public record NewVersion(@NotBlank String body, String note) {}
    public record VersionRef(@NotNull Integer version) {}
    public record DryRunRequest(@NotNull Long showId) {}
}
