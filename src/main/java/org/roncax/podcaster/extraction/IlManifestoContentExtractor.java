package org.roncax.podcaster.extraction;

import jakarta.enterprise.context.ApplicationScoped;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * il manifesto: the page holds only the public deck (og:description) and abstract (first .prose block);
 * the full article is not in the HTML. Avoids the registration/subscription boxes the generic extractor picks.
 */
@ApplicationScoped
public class IlManifestoContentExtractor implements ContentExtractor {

    @Override
    public boolean supports(URI url) {
        return url.getHost() != null && url.getHost().endsWith("ilmanifesto.it");
    }

    @Override
    public int priority() { return 10; }

    @Override
    public Optional<String> extract(Document doc) {
        List<String> parts = new ArrayList<>();
        String deck = doc.select("meta[property=og:description]").attr("content").trim();
        if (!deck.isEmpty()) parts.add(deck);
        Element prose = doc.selectFirst("div.prose");
        if (prose != null) {
            String abstractText = prose.text().trim();
            if (!abstractText.isEmpty() && !abstractText.equals(deck)) parts.add(abstractText);
        }
        return parts.isEmpty() ? Optional.empty() : Optional.of(String.join("\n\n", parts));
    }
}
