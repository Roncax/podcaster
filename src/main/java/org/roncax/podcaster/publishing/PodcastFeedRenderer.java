package org.roncax.podcaster.publishing;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.StringWriter;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import org.roncax.podcaster.domain.Episode;
import org.roncax.podcaster.domain.Show;

@ApplicationScoped
public class PodcastFeedRenderer {
    private static final String ITUNES = "http://www.itunes.com/dtds/podcast-1.0.dtd";

    public String render(Show show, List<Episode> episodes, String baseUrl) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        StringWriter out = new StringWriter();
        try {
            XMLStreamWriter w = XMLOutputFactory.newFactory().createXMLStreamWriter(out);
            w.writeStartDocument("UTF-8", "1.0");
            w.writeStartElement("rss");
            w.writeNamespace("itunes", ITUNES);
            w.writeAttribute("version", "2.0");
            w.writeStartElement("channel");
            element(w, "title", show.name);
            element(w, "link", base + "/feeds/" + show.slug + ".xml");
            element(w, "description", show.description == null || show.description.isBlank() ? show.name : show.description);
            element(w, "language", show.language);
            itunes(w, "author", "Podcaster");
            itunes(w, "explicit", "false");
            for (Episode e : episodes) {
                if (e.audioPath == null || e.publishedAt == null) continue;
                w.writeStartElement("item");
                element(w, "title", e.title == null ? "Episode " + e.id : e.title);
                element(w, "description", e.description == null ? "" : e.description);
                w.writeStartElement("guid");
                w.writeAttribute("isPermaLink", "false");
                w.writeCharacters("podcaster-episode-" + e.id);
                w.writeEndElement();
                element(w, "pubDate", DateTimeFormatter.RFC_1123_DATE_TIME.format(e.publishedAt.atOffset(ZoneOffset.UTC)));
                w.writeEmptyElement("enclosure");
                w.writeAttribute("url", base + "/media/" + e.audioPath);
                w.writeAttribute("length", String.valueOf(e.sizeBytes == null ? 0 : e.sizeBytes));
                w.writeAttribute("type", "audio/mpeg");
                itunes(w, "duration", String.valueOf(Math.round(e.durationSeconds == null ? 0 : e.durationSeconds)));
                w.writeEndElement();
            }
            w.writeEndElement();
            w.writeEndElement();
            w.writeEndDocument();
            w.close();
        } catch (XMLStreamException ex) {
            throw new IllegalStateException("Could not render feed", ex);
        }
        return out.toString();
    }

    private static void element(XMLStreamWriter w, String name, String text) throws XMLStreamException {
        w.writeStartElement(name);
        w.writeCharacters(text == null ? "" : text);
        w.writeEndElement();
    }

    private static void itunes(XMLStreamWriter w, String name, String text) throws XMLStreamException {
        w.writeStartElement("itunes", name, ITUNES);
        w.writeCharacters(text);
        w.writeEndElement();
    }
}
