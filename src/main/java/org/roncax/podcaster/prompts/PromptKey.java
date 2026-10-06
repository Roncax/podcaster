package org.roncax.podcaster.prompts;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/** The prompts the application uses. Text lives in the database; this enum holds the fixed contract. */
public enum PromptKey {
    RANK("rank", "RANK", "Clusters and ranks collected news items",
            Set.of("items", "showName", "language", "focus", "contract"), Set.of("items", "contract"),
            "Return ONLY a JSON object, no prose, with this shape:\n"
                    + "{\"clusters\":[{\"headline\":\"...\",\"itemIds\":[1,2],\"importance\":7}]}"),
    SEGMENT("segment", "SEGMENT", "Writes one spoken segment for a story",
            Set.of("sources", "words", "headline", "language", "focus", "previousTail"), Set.of("sources", "words"),
            null),
    FRAMING("framing", "FRAMING", "Writes episode title, show notes, intro and outro",
            Set.of("stories", "showName", "language", "date", "contract"), Set.of("stories", "contract"),
            "{\"title\":\"...\",\"description\":\"...\",\"intro\":\"...\",\"outro\":\"...\"}"),
    JSON_REPAIR("json_repair", null, "Follow-up message when a reply is not valid JSON",
            Set.of("error"), Set.of("error"), null);

    private final String dbKey;
    private final String header;
    private final String description;
    private final Set<String> variables;
    private final Set<String> required;
    private final String contract;

    PromptKey(String dbKey, String header, String description, Set<String> variables, Set<String> required, String contract) {
        this.dbKey = dbKey;
        this.header = header;
        this.description = description;
        this.variables = variables;
        this.required = required;
        this.contract = contract;
    }

    public String dbKey() { return dbKey; }
    public String header() { return header; }
    public String description() { return description; }
    public Set<String> variables() { return variables; }
    public Set<String> required() { return required; }
    public String contract() { return contract; }

    public String seedBody() {
        try (InputStream in = PromptKey.class.getResourceAsStream("/prompts/" + dbKey + ".txt")) {
            if (in == null) throw new IllegalStateException("Missing seed prompt /prompts/" + dbKey + ".txt");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Optional<PromptKey> fromDb(String key) {
        return Arrays.stream(values()).filter(k -> k.dbKey.equals(key)).findFirst();
    }
}
