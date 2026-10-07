package org.roncax.podcaster.extraction;

import jakarta.enterprise.context.ApplicationScoped;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Il Foglio: lead plus story paragraph blocks. Paywalled articles also carry their full text in the page
 * (hidden for non-subscribers); per the owner's choice it is used as well — fine only for a private feed.
 */
@ApplicationScoped
public class IlFoglioContentExtractor implements ContentExtractor {

    @Override
    public boolean supports(URI url) {
        return url.getHost() != null && url.getHost().endsWith("ilfoglio.it");
    }

    @Override
    public int priority() { return 10; }

    @Override
    public Optional<String> extract(Document doc) {
        List<String> parts = new ArrayList<>();
        Element lead = doc.selectFirst(".body-lead");
        if (lead != null && !lead.text().isBlank()) parts.add(lead.text().trim());
        for (Element block : doc.select(".paywall-wrapper__story-content .blockContainer.paragraph")) {
            String text = block.text().trim();
            if (!text.isEmpty()) parts.add(text);
        }
        return parts.isEmpty() ? Optional.empty() : Optional.of(String.join("\n\n", parts));
    }
}
