package org.roncax.podcaster.extraction;

import jakarta.enterprise.context.ApplicationScoped;
import java.net.URI;
import java.util.Optional;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

@ApplicationScoped
public class DefaultContentExtractor implements ContentExtractor {
    private static final int MIN_PARAGRAPH_CHARS = 40;
    private static final int MIN_TOTAL_CHARS = 200;

    @Override
    public boolean supports(URI url) { return true; }

    @Override
    public int priority() { return -100; }

    @Override
    public Optional<String> extract(Document original) {
        Document doc = original.clone();
        doc.select("script,style,noscript,nav,header,footer,aside,form,figure,iframe").remove();
        Element best = null;
        int bestScore = 0;
        for (Element el : doc.getAllElements()) {
            int score = 0;
            for (Element child : el.children()) {
                if (child.nameIs("p")) {
                    int len = child.text().length();
                    if (len >= MIN_PARAGRAPH_CHARS) score += len;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = el;
            }
        }
        if (best == null || bestScore < MIN_TOTAL_CHARS) return Optional.empty();
        StringBuilder sb = new StringBuilder();
        for (Element child : best.children()) {
            if (child.nameIs("p") && !child.text().isBlank()) {
                if (!sb.isEmpty()) sb.append("\n\n");
                sb.append(child.text().trim());
            }
        }
        return Optional.of(sb.toString());
    }
}
