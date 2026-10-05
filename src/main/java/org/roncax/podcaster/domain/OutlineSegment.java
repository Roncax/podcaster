package org.roncax.podcaster.domain;

import java.util.List;

public record OutlineSegment(String headline, List<Long> itemIds, int words) {}
