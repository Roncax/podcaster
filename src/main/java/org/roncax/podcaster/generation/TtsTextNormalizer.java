package org.roncax.podcaster.generation;

import java.util.Locale;
import java.util.regex.Pattern;
import org.roncax.podcaster.llm.LlmText;

/** Makes LLM prose safe to read aloud: no markdown, no URLs, symbols spelled out. */
public final class TtsTextNormalizer {
    private static final Pattern MD_LINK = Pattern.compile("\\[([^\\]]+)]\\([^)]*\\)");
    private static final Pattern URL = Pattern.compile("(https?://\\S+|www\\.\\S+)");
    private static final Pattern HEADING = Pattern.compile("(?m)^\\s{0,3}#{1,6}\\s*");
    private static final Pattern BULLET = Pattern.compile("(?m)^\\s*(?:[-*•]|\\d+[.)])\\s+");
    private static final Pattern EMPHASIS = Pattern.compile("\\*{1,3}([^*\\n]+)\\*{1,3}");
    private static final Pattern EURO_FIRST = Pattern.compile("€\\s?(\\d[\\d.,]*)");
    private static final Pattern DOLLAR_FIRST = Pattern.compile("\\$\\s?(\\d[\\d.,]*)");

    private TtsTextNormalizer() {}

    public static String normalize(String text, String language) {
        if (text == null) return "";
        boolean it = language != null && language.toLowerCase(Locale.ROOT).startsWith("it");
        String s = LlmText.clean(text);
        s = MD_LINK.matcher(s).replaceAll("$1");
        s = URL.matcher(s).replaceAll("");
        s = HEADING.matcher(s).replaceAll("");
        s = BULLET.matcher(s).replaceAll("");
        s = EMPHASIS.matcher(s).replaceAll("$1");
        s = s.replace("`", "");
        s = EURO_FIRST.matcher(s).replaceAll("$1 euro");
        s = DOLLAR_FIRST.matcher(s).replaceAll(it ? "$1 dollari" : "$1 dollars");
        s = s.replace("€", " euro")
             .replace("%", it ? " per cento" : " percent")
             .replace("&", it ? " e " : " and ")
             .replace("$", it ? " dollari" : " dollars");
        s = s.replaceAll("[ \\t\\x0B\\f\\r]+", " ");
        s = s.replaceAll(" *\\n *", "\n");
        s = s.replaceAll("\\n{3,}", "\n\n");
        s = s.replaceAll("(?<!\\n)\\n(?!\\n)", " ");
        s = s.replaceAll(" {2,}", " ");
        s = s.replaceAll(" ([.,;:!?])", "$1");
        return s.trim();
    }
}
