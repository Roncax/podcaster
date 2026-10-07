package org.roncax.podcaster.admin;

import com.cronutils.descriptor.CronDescriptor;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.roncax.podcaster.admin.AdminViews.*;
import org.roncax.podcaster.api.ShowRequest;
import org.roncax.podcaster.domain.*;
import org.roncax.podcaster.runs.ShowScheduler;
import org.roncax.podcaster.tts.TtsEngine;

/** Shared helpers for the admin pages: lookups, formatting and view-model building. */
@ApplicationScoped
public class AdminSupport {
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH).withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).withZone(ZoneId.systemDefault());
    private static final CronParser CRON = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX));

    @Inject TtsEngine tts;
    @Inject Validator validator;
    @Inject ShowScheduler scheduler;

    public Set<String> voices() {
        try {
            return tts.voices();
        } catch (Exception e) {
            return Set.of();
        }
    }

    public List<String> beanErrors(ShowRequest request) {
        List<String> errors = new ArrayList<>();
        for (ConstraintViolation<ShowRequest> v : validator.validate(request)) errors.add(v.getPropertyPath() + " " + v.getMessage());
        Collections.sort(errors);
        return errors;
    }

    public ShowCard showCard(Show s) {
        Optional<Run> last = QuarkusTransaction.requiringNew().call(() ->
                Run.<Run>find("showId = ?1 order by startedAt desc", s.id).firstResultOptional());
        Optional<Episode> latest = latestEpisode(s.id);
        long fresh = QuarkusTransaction.requiringNew().call(() -> Item.count(
                "showId = ?1 and usedInEpisodeId is null and coalesce(publishedAt, fetchedAt) >= ?2", s.id, Instant.now().minus(Duration.ofHours(24))));
        long episodes = QuarkusTransaction.requiringNew().call(() -> Episode.count("showId = ?1 and publishedAt is not null", s.id));
        String meta = s.language + " · " + s.voiceId + " · " + s.writerModel + " · " + s.targetDurationMinutes + " min";
        return new ShowCard(s.id, s.name, s.slug, meta,
                last.map(r -> "Last run: " + label(r.status).toLowerCase()).orElse("No runs yet"),
                last.map(r -> tone(r.status)).orElse("neutral"),
                latest.map(e -> episodeLink(e, s.name)).orElse(null),
                scheduler.nextRun(s.id).map(AdminSupport::when).orElse(s.cron == null ? "Not scheduled" : cronText(s.cron)),
                fresh, episodes);
    }

    public Optional<Episode> latestEpisode(long showId) {
        return QuarkusTransaction.requiringNew().call(() -> Episode.<Episode>find(
                "showId = ?1 and publishedAt is not null and audioPath is not null order by publishedAt desc", showId).firstResultOptional());
    }

    public EpisodeLink episodeLink(Episode e, String showName) {
        return new EpisodeLink(e.id, e.title == null ? "Untitled episode" : e.title, e.showId, showName,
                day(e.publishedAt != null ? e.publishedAt : e.createdAt), duration(e.durationSeconds),
                e.audioPath == null ? null : "/media/" + e.audioPath);
    }

    public RunRow runRow(Run r, String showName) {
        return new RunRow(r.id, r.showId, showName, stages(r), label(r.status), tone(r.status), when(r.startedAt), r.error);
    }

    public List<Attention> attention() {
        List<Attention> out = new ArrayList<>();
        QuarkusTransaction.requiringNew().run(() -> {
            for (Source s : Source.<Source>list("lastError is not null order by id")) {
                out.add(new Attention("Source failing: " + s.label(), abbreviate(s.lastError, 200), "warn", "/admin/shows/" + s.showId + "?tab=sources"));
            }
            for (Run r : Run.<Run>list("status = ?1 and startedAt >= ?2 order by startedAt desc", RunStatus.FAILED, Instant.now().minus(Duration.ofDays(7)))) {
                out.add(new Attention("Run #" + r.id + " failed", abbreviate(r.error, 200), "fail", "/admin/shows/" + r.showId));
            }
        });
        return out;
    }

    public LiveRun liveRun(long showId) {
        Optional<Run> run = QuarkusTransaction.requiringNew().call(() ->
                Run.<Run>find("showId = ?1 order by startedAt desc", showId).firstResultOptional());
        if (run.isEmpty()) return null;
        Run r = run.get();
        Long episodeId = QuarkusTransaction.requiringNew().call(() -> Episode.findByRun(r.id).map(e -> e.publishedAt == null ? null : e.id).orElse(null));
        return new LiveRun(r.id, r.status == RunStatus.RUNNING, label(r.status), tone(r.status), r.trigger.name().toLowerCase(),
                when(r.startedAt), steps(r), r.progress, r.error, episodeId);
    }

    /** Chapters with their sources; episodes without recorded chapters fall back to the outline (no timestamps). */
    public List<ChapterView> chapters(Episode e) {
        List<Chapter> chapters = e.chapters;
        if (chapters == null || chapters.isEmpty()) {
            if (e.outline == null) return List.of();
            chapters = e.outline.segments().stream().map(s -> new Chapter(s.headline(), -1, s.itemIds())).toList();
        }
        Set<Long> ids = chapters.stream().flatMap(c -> c.itemIds().stream()).collect(Collectors.toSet());
        Map<Long, Item> items = ids.isEmpty() ? Map.of() : QuarkusTransaction.requiringNew().call(() ->
                Item.<Item>list("id in ?1", ids).stream().collect(Collectors.toMap(i -> i.id, Function.identity())));
        Show show = QuarkusTransaction.requiringNew().call(() -> Show.<Show>findById(e.showId));
        boolean italian = show != null && show.language != null && show.language.startsWith("it");
        List<ChapterView> out = new ArrayList<>();
        for (Chapter c : chapters) {
            List<SourceLink> sources = new ArrayList<>();
            for (Long id : c.itemIds()) {
                Item item = items.get(id);
                if (item == null) continue;
                sources.add(new SourceLink(site(item.url), item.title, item.url));
                if (item.discussionUrl != null && !item.discussionUrl.isBlank()) {
                    sources.add(new SourceLink("Reddit", italian ? "Discussione su Reddit" : "Reddit discussion", item.discussionUrl));
                }
            }
            out.add(new ChapterView(c.title(), c.startSeconds() < 0 ? null : duration(c.startSeconds()), c.startSeconds(), sources));
        }
        return out;
    }

    public static String when(Instant i) { return i == null ? "—" : WHEN.format(i); }

    public static String day(Instant i) { return i == null ? "—" : DAY.format(i); }

    public static String duration(Double seconds) {
        if (seconds == null) return "—";
        long t = Math.round(seconds);
        return (t / 60) + ":" + String.format("%02d", t % 60);
    }

    public static String tone(RunStatus s) {
        return switch (s) { case RUNNING -> "run"; case DONE -> "ok"; case FAILED -> "fail"; case SKIPPED -> "neutral"; };
    }

    public static String label(RunStatus s) {
        return switch (s) { case RUNNING -> "Running"; case DONE -> "Done"; case FAILED -> "Failed"; case SKIPPED -> "Skipped"; };
    }

    static String state(Run r, RunStage stage) {
        if (r.status == RunStatus.DONE) return "done";
        int cmp = Integer.compare(stage.ordinal(), r.stage.ordinal());
        if (cmp < 0) return "done";
        if (cmp > 0) return "pending";
        return switch (r.status) { case RUNNING -> "run"; case FAILED -> "fail"; case SKIPPED -> "skip"; default -> "done"; };
    }

    public static List<StageDot> stages(Run r) {
        return Arrays.stream(RunStage.values()).map(st -> new StageDot(stageName(st), state(r, st))).toList();
    }

    public static List<Step> steps(Run r) {
        return Arrays.stream(RunStage.values()).map(st -> {
            String state = state(r, st);
            String detail = switch (state) { case "done" -> "done"; case "run" -> "in progress"; case "fail" -> "failed"; case "skip" -> "skipped"; default -> "waiting"; };
            return new Step(stageName(st), detail, state);
        }).toList();
    }

    static String stageName(RunStage st) {
        String n = st.name().toLowerCase();
        return st == RunStage.TTS ? "TTS" : Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    public static String site(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? url : host.replaceFirst("^www\\.", "");
        } catch (IllegalArgumentException e) {
            return url;
        }
    }

    public static String cronText(String cron) {
        if (cron == null || cron.isBlank()) return "Not scheduled";
        try {
            return CronDescriptor.instance(Locale.ENGLISH).describe(CRON.parse(cron.trim()));
        } catch (RuntimeException e) {
            return cron;
        }
    }

    static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
