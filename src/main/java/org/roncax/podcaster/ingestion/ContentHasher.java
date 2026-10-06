package org.roncax.podcaster.ingestion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

public final class ContentHasher {
    private static final int TEXT_PREFIX = 2000;

    private ContentHasher() {}

    public static String hash(String title, String text) {
        String body = text == null ? "" : text;
        if (body.length() > TEXT_PREFIX) body = body.substring(0, TEXT_PREFIX);
        String normalized = normalize(title) + "\n" + normalize(body);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
}
