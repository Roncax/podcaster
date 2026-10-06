package org.roncax.podcaster.extraction;

import java.net.URI;
import java.util.Optional;
import org.jsoup.nodes.Document;

/** Turns an article page into plain text paragraphs separated by blank lines. */
public interface ContentExtractor {
    boolean supports(URI url);

    Optional<String> extract(Document doc);

    /** Higher runs first. Site-specific extractors use positive values, the generic one -100. */
    default int priority() { return 0; }
}
