package org.roncax.podcaster.generation;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import org.roncax.podcaster.config.PodcasterConfig;
import org.roncax.podcaster.domain.Cluster;
import org.roncax.podcaster.domain.Outline;
import org.roncax.podcaster.domain.OutlineSegment;
import org.roncax.podcaster.domain.Selection;

/** Deterministic outline: picks the most important clusters and allocates word budgets. */
@ApplicationScoped
public class OutlinePlanner {
    private final int introOutroWords;
    private final int minSegmentWords;
    private final int maxSegmentWords;

    @Inject
    public OutlinePlanner(PodcasterConfig config) {
        this(config.script().introOutroWords(), config.script().minSegmentWords(), config.script().maxSegmentWords());
    }

    public OutlinePlanner(int introOutroWords, int minSegmentWords, int maxSegmentWords) {
        this.introOutroWords = introOutroWords;
        this.minSegmentWords = minSegmentWords;
        this.maxSegmentWords = maxSegmentWords;
    }

    public Outline plan(Selection selection, int targetMinutes, double wordsPerMinute) {
        int total = (int) Math.round(targetMinutes * wordsPerMinute);
        int body = Math.max(minSegmentWords, total - introOutroWords);
        int maxCount = Math.max(1, body / minSegmentWords);
        List<Cluster> chosen = selection.clusters().stream().limit(maxCount).toList();
        double weightSum = chosen.stream().mapToInt(c -> Math.max(1, c.importance())).sum();
        List<OutlineSegment> segments = new ArrayList<>();
        int sum = 0;
        for (Cluster c : chosen) {
            int words = (int) Math.round(body * Math.max(1, c.importance()) / weightSum);
            words = Math.max(minSegmentWords, Math.min(maxSegmentWords, words));
            segments.add(new OutlineSegment(c.headline(), c.itemIds(), words));
            sum += words;
        }
        return new Outline(introOutroWords + sum, segments);
    }
}
