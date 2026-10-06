package org.roncax.podcaster.admin;

import java.util.List;

public final class PromptAdminViews {
    private PromptAdminViews() {}

    public record PromptRow(String key, String description, Integer production, Integer draft) {}
    public record VersionRow(int version, String note, String createdAt, String labels) {}
    public record DiffRow(String css, String prefix, String text) {}
    public record OverrideRow(String key, Integer pinned, List<Integer> versions) {}
}
