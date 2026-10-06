package org.roncax.podcaster.extraction;

import jakarta.enterprise.context.ApplicationScoped;
import java.net.URI;
import java.util.Optional;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

@ApplicationScoped
public class AnsaContentExtractor implements ContentExtractor {

    @Override
    public boolean supports(URI url) {
        return url.getHost() != null && url.getHost().endsWith("ansa.it");
    }

    @Override
    public int priority() { return 10; }

    @Override
    public Optional<String> extract(Document doc) {
        Element body = doc.selectFirst("[itemprop=articleBody], div.news-txt");
        if (body == null) return Optional.empty();
        StringBuilder sb = new StringBuilder();
        for (Element p : body.select("p:not(.article-copyright)")) {
            String text = p.text().trim();
            if (text.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append("\n\n");
            sb.append(text);
        }
        return sb.isEmpty() ? Optional.empty() : Optional.of(sb.toString());
    }
}
