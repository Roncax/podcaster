package org.roncax.podcaster.generation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.roncax.podcaster.domain.Cluster;
import org.roncax.podcaster.domain.Outline;
import org.roncax.podcaster.domain.OutlineSegment;
import org.roncax.podcaster.domain.Selection;

class OutlinePlannerTest {
    OutlinePlanner planner = new OutlinePlanner(200, 250, 900);

    static Cluster c(String h, int importance, long id) { return new Cluster(h, List.of(id), importance); }

    @Test
    void fewClustersGiveShorterEpisodeWithoutPadding() {
        Outline o = planner.plan(new Selection(List.of(c("A", 9, 1), c("B", 6, 2), c("C", 3, 3))), 20, 150);
        assertEquals(List.of(900, 900, 592), o.segments().stream().map(OutlineSegment::words).toList());
        assertEquals(200 + 900 + 900 + 592, o.totalWords());
    }

    @Test
    void manyClustersAreCappedByMinimumSegmentLength() {
        List<Cluster> clusters = IntStream.range(0, 20).mapToObj(i -> c("S" + i, 5, i)).toList();
        Outline o = planner.plan(new Selection(clusters), 20, 150);
        assertEquals(11, o.segments().size());
        assertTrue(o.segments().stream().allMatch(s -> s.words() >= 250));
        assertEquals("S0", o.segments().get(0).headline());
        assertTrue(Math.abs(o.totalWords() - 3000) <= 20, "total " + o.totalWords());
    }

    @Test
    void singleClusterIsCappedAtMaximum() {
        Outline o = planner.plan(new Selection(List.of(c("Only", 10, 1))), 20, 150);
        assertEquals(1, o.segments().size());
        assertEquals(900, o.segments().get(0).words());
        assertEquals(List.of(1L), o.itemIds());
    }

    @Test
    void wordsPerMinuteScalesBudget() {
        List<Cluster> clusters = IntStream.range(0, 20).mapToObj(i -> c("S" + i, 5, i)).toList();
        Outline slow = planner.plan(new Selection(clusters), 20, 120);
        Outline fast = planner.plan(new Selection(clusters), 20, 180);
        assertTrue(fast.totalWords() > slow.totalWords());
    }

    @Test
    void skewedImportanceStaysNearTarget() {
        int[] importance = {9, 8, 7, 5, 4, 3, 3, 2, 2, 1, 1};
        List<Cluster> clusters = IntStream.range(0, importance.length).mapToObj(i -> c("S" + i, importance[i], i)).toList();
        Outline o = planner.plan(new Selection(clusters), 20, 150);
        assertTrue(Math.abs(o.totalWords() - 3000) <= 150, "total " + o.totalWords() + " should be within 5% of 3000");
        assertTrue(o.segments().stream().allMatch(s -> s.words() >= 250));
        assertTrue(o.segments().get(0).words() > o.segments().get(10).words());
    }
}
