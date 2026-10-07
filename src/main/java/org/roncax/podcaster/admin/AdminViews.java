package org.roncax.podcaster.admin;

import java.util.List;

/** View models for the admin pages. Tones and states are keywords; the Qute tags map them to classes. */
public final class AdminViews {
    private AdminViews() {}

    public record StageDot(String name, String state) {}
    public record Step(String name, String detail, String state) {}
    public record EpisodeLink(long id, String title, long showId, String showName, String date, String duration, String audioUrl) {}
    public record ShowCard(long id, String name, String slug, String meta, String lastRunLabel, String lastRunTone,
                           EpisodeLink latest, String nextRun, long freshItems, long episodes) {}
    public record RunRow(long id, long showId, String showName, List<StageDot> stages, String status, String tone, String when, String error) {}
    public record Attention(String title, String detail, String tone, String href) {}
    public record SourceLink(String site, String title, String url) {}
    public record ChapterView(String title, String time, double start, List<SourceLink> sources) {}
    public record SourceRow(long id, String name, String detail, String type, String typeTone, String fetched, long newToday, String status, String tone) {}
    public record LiveRun(long id, boolean running, String status, String tone, String trigger, String started,
                          List<Step> steps, String progress, String error, Long episodeId) {}
}
