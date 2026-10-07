package org.roncax.podcaster.extraction;

import jakarta.enterprise.context.ApplicationScoped;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/** Internazionale splits an article over several .item_text blocks; collect them all, skip the editorial notes. */
@ApplicationScoped
public class InternazionaleContentExtractor implements ContentExtractor {

    @Override
    public boolean supports(URI url) {
        return url.getHost() != null && url.getHost().endsWith("internazionale.it");
    }

    @Override
    public int priority() { return 10; }

    @Override
    public Optional<String> extract(Document doc) {
        List<String> paragraphs = new ArrayList<>();
        for (Element p : doc.select(".article-body .item_text p")) {
            String text = p.text().trim();
            if (!text.isEmpty()) paragraphs.add(text);
        }
        return paragraphs.isEmpty() ? Optional.empty() : Optional.of(String.join("\n\n", paragraphs));
    }
}
