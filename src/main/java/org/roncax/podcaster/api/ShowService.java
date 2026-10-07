package org.roncax.podcaster.api;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;
import org.jboss.logging.Logger;
import org.roncax.podcaster.domain.Show;
import org.roncax.podcaster.domain.Source;
import org.roncax.podcaster.extraction.ContentExtractionService;
import org.roncax.podcaster.ingestion.*;
import org.roncax.podcaster.llm.ChatModelRegistry;
import org.roncax.podcaster.publishing.AudioStorage;
import org.roncax.podcaster.runs.CronValidator;
import org.roncax.podcaster.runs.ShowScheduler;
import org.roncax.podcaster.tts.TtsEngine;
import org.roncax.podcaster.util.Exceptions;

@ApplicationScoped
public class ShowService {
    private static final Logger LOG = Logger.getLogger(ShowService.class);
    private static final Duration TEST_WINDOW = Duration.ofDays(7);
    private static final int TEST_ITEMS = 10;

    @Inject ChatModelRegistry models;
    @Inject ConnectorRegistry connectors;
    @Inject ContentExtractionService extraction;
    @Inject TtsEngine tts;
    @Inject ShowScheduler scheduler;
    @Inject AudioStorage storage;

    public Show create(ShowRequest r) {
        throwIfInvalid(validationErrors(r, null));
        Show show = QuarkusTransaction.requiringNew().call(() -> {
            Show s = new Show();
            apply(s, r);
            s.persist();
            return s;
        });
        scheduler.reschedule(show);
        return show;
    }

    public Show update(long id, ShowRequest r) {
        throwIfInvalid(validationErrors(r, id));
        Show show = QuarkusTransaction.requiringNew().call(() -> {
            Show s = Show.<Show>findByIdOptional(id).orElseThrow(NotFoundException::new);
            apply(s, r);
            return s;
        });
        scheduler.reschedule(show);
        return show;
    }

    /** Invalidates the old feed and media URLs: subscribers need the new feed URL. */
    public void regenerateFeedToken(long id) {
        QuarkusTransaction.requiringNew().run(() ->
                Show.<Show>findByIdOptional(id).orElseThrow(NotFoundException::new).feedToken = Show.newFeedToken());
    }

    public void delete(long id) {
        String slug = QuarkusTransaction.requiringNew().call(() -> {
            Show s = Show.<Show>findByIdOptional(id).orElseThrow(NotFoundException::new);
            String sl = s.slug;
            s.delete();
            return sl;
        });
        scheduler.unschedule(id);
        try {
            Path dir = storage.resolve(slug);
            if (Files.exists(dir)) {
                try (Stream<Path> paths = Files.walk(dir)) {
                    for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
                }
            }
        } catch (IOException e) {
            LOG.warnf("Could not delete audio of show %s: %s", slug, e.getMessage());
        }
    }

    public List<String> validationErrors(ShowRequest r, Long excludeId) {
        List<String> errors = new ArrayList<>();
        if (!models.isAvailable(r.writerModel())) {
            errors.add("writerModel '" + r.writerModel() + "' is not an enabled model; available: " + models.availableNames());
        }
        if (r.rankerModel() != null && !r.rankerModel().isBlank() && !models.isAvailable(r.rankerModel())) {
            errors.add("rankerModel '" + r.rankerModel() + "' is not an enabled model; available: " + models.availableNames());
        }
        CronValidator.validate(r.cron()).ifPresent(errors::add);
        try {
            Set<String> voices = tts.voices();
            if (!voices.contains(r.voiceId())) {
                errors.add("voiceId '" + r.voiceId() + "' is not installed in Piper; available: " + voices);
            }
        } catch (Exception e) {
            LOG.warnf("Could not verify voice, Piper unreachable: %s", e.getMessage());
        }
        long clash = QuarkusTransaction.requiringNew().call(() -> excludeId == null
                ? Show.count("slug", r.slug())
                : Show.count("slug = ?1 and id <> ?2", r.slug(), excludeId));
        if (clash > 0) errors.add("slug '" + r.slug() + "' is already used by another show");
        return errors;
    }

    public Source addSource(long showId, SourceRequest r) {
        validateSource(r);
        return QuarkusTransaction.requiringNew().call(() -> {
            if (Show.findById(showId) == null) throw new NotFoundException();
            Source s = new Source();
            s.showId = showId;
            applySource(s, r);
            s.persist();
            return s;
        });
    }

    public Source updateSource(long id, SourceRequest r) {
        validateSource(r);
        return QuarkusTransaction.requiringNew().call(() -> {
            Source s = Source.<Source>findByIdOptional(id).orElseThrow(NotFoundException::new);
            applySource(s, r);
            return s;
        });
    }

    public void deleteSource(long id) {
        QuarkusTransaction.requiringNew().run(() -> {
            Source s = Source.<Source>findByIdOptional(id).orElseThrow(NotFoundException::new);
            s.delete();
        });
    }

    public SourceTestResult testSource(long id) {
        Source source = QuarkusTransaction.requiringNew().call(() -> Source.<Source>findByIdOptional(id).orElseThrow(NotFoundException::new));
        SourceConnector connector = connectors.find(source.connectorType)
                .orElseThrow(() -> new InvalidRequestException(List.of("Unknown connectorType '" + source.connectorType + "'")));
        try {
            List<RawItem> items = connector.fetch(new SourceConfig(source.config), Instant.now().minus(TEST_WINDOW));
            items = items.subList(0, Math.min(TEST_ITEMS, items.size()));
            String sample = null;
            if (!items.isEmpty()) {
                RawItem first = items.get(0);
                sample = first.fullText() != null || connector.providesFullText()
                        ? first.fullText()
                        : extraction.extract(first.url()).orElse(null);
            }
            return new SourceTestResult(items, sample, null);
        } catch (Exception e) {
            return new SourceTestResult(List.of(), null, Exceptions.message(e));
        }
    }

    private void validateSource(SourceRequest r) {
        List<String> errors = new ArrayList<>();
        Optional<SourceConnector> connector = connectors.find(r.connectorType());
        if (connector.isEmpty()) {
            errors.add("Unknown connectorType '" + r.connectorType() + "'; available: " + connectors.types());
        } else {
            errors.addAll(connector.get().validate(new SourceConfig(r.config())));
        }
        throwIfInvalid(errors);
    }

    private static void throwIfInvalid(List<String> errors) {
        if (!errors.isEmpty()) throw new InvalidRequestException(errors);
    }

    static void apply(Show s, ShowRequest r) {
        s.name = r.name().trim();
        s.slug = r.slug();
        s.description = blankToNull(r.description());
        s.language = r.language().trim();
        s.voiceId = r.voiceId().trim();
        s.lengthScale = r.lengthScale() == null ? 1.0 : r.lengthScale();
        s.writerModel = r.writerModel();
        s.rankerModel = blankToNull(r.rankerModel());
        s.focusPrompt = blankToNull(r.focusPrompt());
        s.targetDurationMinutes = r.targetDurationMinutes() == null ? 20 : r.targetDurationMinutes();
        s.minItems = r.minItems() == null ? 3 : r.minItems();
        s.cron = blankToNull(r.cron());
        s.enabled = r.enabled() == null || r.enabled();
        s.retainEpisodes = r.retainEpisodes() == null ? 30 : r.retainEpisodes();
    }

    static void applySource(Source s, SourceRequest r) {
        s.connectorType = r.connectorType();
        s.config = r.config() == null ? new HashMap<>() : new HashMap<>(r.config());
        s.fetchFullText = r.fetchFullText() == null || r.fetchFullText();
        s.enabled = r.enabled() == null || r.enabled();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
