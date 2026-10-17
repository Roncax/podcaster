package org.roncax.podcaster.admin;

import java.util.List;

public final class PromptAdminViews {
    private PromptAdminViews() {}

    public record PromptRow(String key, String description, int latest) {}
    public record VersionRow(int version, String note, String createdAt, String labels) {}
    public record DiffRow(String css, String prefix, String text) {}
    public record Crumb(String label, String href) {}
    public record ShowPromptRow(String key, String description, Integer production, Integer draft) {}
    /** One prompt's page; shared by default prompts ({@code labelled=false}) and show prompts. */
    public record PromptPage(String basePath, List<Crumb> crumbs, String activeNav, String key, String description,
                             String summary, String banner, boolean labelled, List<VersionRow> versions, int shownVersion,
                             String compareTitle, String baseBody, String shownBody, List<DiffRow> diff,
                             String editorBody, String note, List<String> errors, List<String> variables,
                             String resetAction, String testHref) {}
}
