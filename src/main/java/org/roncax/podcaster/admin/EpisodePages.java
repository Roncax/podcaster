package org.roncax.podcaster.admin;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.panache.common.Sort;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.*;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestQuery;
import org.roncax.podcaster.admin.AdminViews.*;
import org.roncax.podcaster.domain.*;
import org.roncax.podcaster.tts.VoiceCalibrationService;

@Path("/admin/episodes")
@Produces(MediaType.TEXT_HTML)
public class EpisodePages {

    public record EpisodeView(long id, String title, long showId, String showName, String date, String duration, String size,
                              long runId, String audioUrl, List<ChapterView> chapters, List<String> madeWith,
                              List<String> scriptParts, String description) {}

    @CheckedTemplate
    static class Templates {
        static native TemplateInstance list(List<EpisodeLink> episodes, List<Show> shows, Long selected);
        static native TemplateInstance detail(EpisodeView episode);
    }

    @Inject AdminSupport support;
    @Inject VoiceCalibrationService calibration;

    @GET
    public TemplateInstance list(@RestQuery Long show) {
        List<Show> shows = QuarkusTransaction.requiringNew().call(() -> Show.<Show>listAll(Sort.by("name")));
        Map<Long, String> names = new HashMap<>();
        shows.forEach(s -> names.put(s.id, s.name));
        List<Episode> episodes = QuarkusTransaction.requiringNew().call(() -> show == null
                ? Episode.<Episode>find("publishedAt is not null order by publishedAt desc").page(0, 100).list()
                : Episode.<Episode>find("showId = ?1 and publishedAt is not null order by publishedAt desc", show).page(0, 100).list());
        return Templates.list(episodes.stream().map(e -> support.episodeLink(e, names.getOrDefault(e.showId, "?"))).toList(), shows, show);
    }

    @GET
    @Path("/{id}")
    public TemplateInstance detail(@RestPath long id) {
        Episode e = QuarkusTransaction.requiringNew().call(() -> Episode.<Episode>findByIdOptional(id).orElseThrow(NotFoundException::new));
        Show show = QuarkusTransaction.requiringNew().call(() -> Show.<Show>findById(e.showId));
        List<String> madeWith = new ArrayList<>();
        if (e.promptVersions != null) new TreeMap<>(e.promptVersions).forEach((k, v) -> madeWith.add(k + " v" + v));
        if (show != null) {
            madeWith.add("model " + show.writerModel);
            madeWith.add(show.voiceId);
            madeWith.add(String.format(Locale.ROOT, "%.0f wpm", calibration.wordsPerMinute(show.voiceId, show.lengthScale)));
        }
        String size = e.sizeBytes == null ? "—" : String.format(Locale.ROOT, "%.1f MB", e.sizeBytes / 1_000_000.0);
        EpisodeView view = new EpisodeView(e.id, e.title == null ? "Untitled episode" : e.title, e.showId,
                show == null ? "Deleted show" : show.name, AdminSupport.day(e.publishedAt), AdminSupport.duration(e.durationSeconds), size,
                e.runId, e.audioPath == null ? null : "/media/" + e.audioPath, support.chapters(e), madeWith,
                e.scriptParts == null ? List.of() : e.scriptParts, e.description);
        return Templates.detail(view);
    }
}
