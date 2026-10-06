package org.roncax.podcaster.domain;

import java.util.List;

public record Outline(int totalWords, List<OutlineSegment> segments) {
    public List<Long> itemIds() {
        return segments.stream().flatMap(s -> s.itemIds().stream()).distinct().toList();
    }
}
