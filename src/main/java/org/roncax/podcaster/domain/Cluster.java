package org.roncax.podcaster.domain;

import java.util.List;

public record Cluster(String headline, List<Long> itemIds, int importance) {}
