package org.roncax.podcaster.generation;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;
import org.roncax.podcaster.prompts.PromptKey;
import org.roncax.podcaster.prompts.PromptSet;

/** Builds the variables for each prompt and renders it from the resolved, database-backed versions. */
public final class Prompts {
    private Prompts() {}

    public static String languageName(String tag) {
        String name = Locale.forLanguageTag(tag).getDisplayLanguage(Locale.ENGLISH);
        return name.isBlank() ? tag : name;
    }

    public static String rank(PromptSet prompts, String showName, String language, String focus, List<String> itemLines) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("showName", showName);
        vars.put("language", languageName(language));
        vars.put("focus", blankToNull(focus));
        vars.put("items", String.join("\n", itemLines));
        return prompts.render(PromptKey.RANK, vars);
    }

    public static String segment(PromptSet prompts, String language, String headline, int words, String sources,
                                 String previousTail, String focus) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("language", languageName(language));
        vars.put("headline", headline);
        vars.put("words", words);
        vars.put("sources", sources);
        vars.put("previousTail", previousTail);
        vars.put("focus", blankToNull(focus));
        return prompts.render(PromptKey.SEGMENT, vars);
    }

    public static String framing(PromptSet prompts, String showName, String language, LocalDate date, List<String> headlines) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("showName", showName);
        vars.put("language", languageName(language));
        vars.put("date", date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(Locale.forLanguageTag(language))));
        vars.put("stories", String.join("\n", IntStream.range(0, headlines.size())
                .mapToObj(i -> (i + 1) + ". " + headlines.get(i)).toList()));
        return prompts.render(PromptKey.FRAMING, vars);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
