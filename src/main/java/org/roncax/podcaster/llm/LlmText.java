package org.roncax.podcaster.llm;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LlmText {
    private static final Pattern THINK = Pattern.compile("(?is)<think>.*?</think>");
    private static final Pattern FENCE = Pattern.compile("(?s)^```[a-zA-Z]*\\s*\\n?(.*?)\\n?```$");

    private LlmText() {}

    public static String clean(String raw) {
        if (raw == null) return "";
        String s = THINK.matcher(raw).replaceAll("");
        int close = s.lastIndexOf("</think>");
        if (close >= 0) s = s.substring(close + "</think>".length()); // template put <think> in the prompt
        if (s.contains("<think>")) throw new GenerationException("Model output was truncated inside its reasoning block");
        s = s.trim();
        Matcher m = FENCE.matcher(s);
        if (m.matches()) s = m.group(1).trim();
        return s;
    }

    public static String jsonObject(String raw) {
        String s = clean(raw);
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IllegalArgumentException("No JSON object found in model reply");
        return s.substring(start, end + 1);
    }
}
