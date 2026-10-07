package org.roncax.podcaster.generation;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Splits script parts into TTS-sized chunks on paragraph and sentence boundaries. */
public final class ScriptChunker {
    private static final Pattern PARAGRAPH = Pattern.compile("\\n\\s*\\n");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?…])\\s+");

    private ScriptChunker() {}

    public static List<TtsChunk> chunk(List<String> parts, int maxChars, Duration chunkPause, Duration segmentPause) {
        List<TtsChunk> chunks = new ArrayList<>();
        for (int partIndex = 0; partIndex < parts.size(); partIndex++) {
            String part = parts.get(partIndex);
            List<String> texts = new ArrayList<>();
            for (String paragraph : PARAGRAPH.split(part)) {
                String p = paragraph.trim();
                if (p.isEmpty()) continue;
                StringBuilder current = new StringBuilder();
                for (String sentence : SENTENCE_END.split(p)) {
                    for (String piece : splitLong(sentence.trim(), maxChars)) {
                        if (!current.isEmpty() && current.length() + 1 + piece.length() > maxChars) {
                            texts.add(current.toString());
                            current.setLength(0);
                        }
                        if (!current.isEmpty()) current.append(' ');
                        current.append(piece);
                    }
                }
                if (!current.isEmpty()) texts.add(current.toString());
            }
            for (int i = 0; i < texts.size(); i++) {
                Duration pause = i == texts.size() - 1 ? segmentPause : chunkPause;
                chunks.add(new TtsChunk(chunks.size(), texts.get(i), pause, partIndex));
            }
        }
        return chunks;
    }

    static List<String> splitLong(String sentence, int maxChars) {
        List<String> out = new ArrayList<>();
        String s = sentence;
        while (s.length() > maxChars) {
            int cut = s.lastIndexOf(", ", maxChars - 1);
            if (cut >= maxChars / 2) {
                cut += 1; // keep the comma with the first piece
            } else {
                cut = s.lastIndexOf(' ', maxChars);
                if (cut <= 0) cut = maxChars;
            }
            out.add(s.substring(0, cut).trim());
            s = s.substring(cut).trim();
        }
        if (!s.isEmpty()) out.add(s);
        return out;
    }
}
