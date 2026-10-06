package org.roncax.podcaster.generation;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

/** Prompt templates. The first line of every prompt is "TASK: <NAME>". */
public final class Prompts {
    private Prompts() {}

    public static String languageName(String tag) {
        String name = Locale.forLanguageTag(tag).getDisplayLanguage(Locale.ENGLISH);
        return name.isBlank() ? tag : name;
    }

    static String focusLine(String focus) {
        return focus == null || focus.isBlank() ? "" : "Editorial focus from the show owner: " + focus.trim();
    }

    public static String rank(String showName, String language, String focus, List<String> itemLines) {
        return """
                TASK: RANK
                You are the editor of the news podcast "%s".
                Below are news items collected for the next episode. Group items that report the same story into clusters, then rate each cluster's importance for the listener from 1 (minor) to 10 (major).
                %s
                Rules:
                - Use only item ids from the list; an item belongs to at most one cluster.
                - Leave out items that are not news (ads, promotions, games, horoscopes).
                - Write every headline in %s.
                Return ONLY a JSON object, no prose, with this shape:
                {"clusters":[{"headline":"...","itemIds":[1,2],"importance":7}]}

                ITEMS:
                %s
                """.formatted(showName, focusLine(focus), languageName(language), String.join("\n", itemLines));
    }

    public static String segment(String language, String headline, int words, String sources, String previousTail, String focus) {
        String transition = previousTail == null
                ? "This is the first story of the episode."
                : "The previous segment ended with: \"" + previousTail + "\"\nOpen with a short, natural transition from it.";
        return """
                TASK: SEGMENT
                You write one segment of a spoken news podcast in %s. It will be read aloud by a single narrator using text-to-speech.
                Segment topic: %s
                Target length: about %d words.
                Style:
                - Plain spoken prose only: no markdown, lists, headings, URLs, emojis or stage directions.
                - Explain the story clearly for a listener who has not read the articles; name the sources when attributing claims.
                - Write numbers, dates and abbreviations the way a narrator would say them.
                - Do not greet the listener or close the episode: this segment sits in the middle of the episode.
                %s
                %s
                SOURCES:
                %s

                Write the segment now, in %s.
                """.formatted(languageName(language), headline, words, transition, focusLine(focus), sources, languageName(language));
    }

    public static String framing(String showName, String language, LocalDate date, List<String> headlines) {
        String dateText = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(Locale.forLanguageTag(language)));
        String stories = String.join("\n", IntStream.range(0, headlines.size())
                .mapToObj(i -> (i + 1) + ". " + headlines.get(i)).toList());
        return """
                TASK: FRAMING
                You write the opening and closing of an episode of the spoken news podcast "%s", in %s, read by a single narrator using text-to-speech. Episode date: %s.
                The episode covers these stories, in order:
                %s

                Return ONLY a JSON object, no prose, with these fields:
                - "title": short episode title, at most 80 characters
                - "description": 2-3 sentences of show notes
                - "intro": spoken opening of 80-120 words that greets the listener, says the date and previews the stories
                - "outro": spoken closing of 50-80 words
                intro and outro are plain spoken prose: no markdown, URLs or emojis.
                {"title":"...","description":"...","intro":"...","outro":"..."}
                """.formatted(showName, languageName(language), dateText, stories);
    }
}
