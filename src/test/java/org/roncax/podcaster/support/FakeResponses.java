package org.roncax.podcaster.support;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Canned LLM replies keyed on the "TASK: X" first line of each prompt. */
public final class FakeResponses {
    private static final Pattern ID = Pattern.compile("\\[id=(\\d+)]");

    private FakeResponses() {}

    public static List<Long> ids(String prompt) {
        List<Long> ids = new ArrayList<>();
        Matcher m = ID.matcher(prompt);
        while (m.find()) ids.add(Long.parseLong(m.group(1)));
        return ids;
    }

    public static String pipeline(String prompt) {
        if (prompt.startsWith("TASK: RANK")) {
            return "{\"clusters\":[{\"headline\":\"Top story\",\"itemIds\":" + ids(prompt) + ",\"importance\":8}]}";
        }
        if (prompt.startsWith("TASK: SEGMENT")) return "This is the segment text. It has two sentences.";
        if (prompt.startsWith("TASK: FRAMING")) {
            return "{\"title\":\"Test episode\",\"description\":\"Notes.\",\"intro\":\"Welcome to the show.\",\"outro\":\"Thanks for listening.\"}";
        }
        throw new IllegalStateException("Unexpected prompt: " + prompt.lines().findFirst().orElse(""));
    }
}
