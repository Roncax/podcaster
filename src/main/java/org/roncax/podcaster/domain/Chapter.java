package org.roncax.podcaster.domain;

import java.util.List;

/** A chapter of an episode: intro, one per story segment, outro. */
public record Chapter(String title, double startSeconds, List<Long> itemIds) {}
