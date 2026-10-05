package org.roncax.podcaster.generation;

import java.util.List;
import java.util.Locale;

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
}
