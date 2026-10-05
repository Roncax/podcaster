package org.roncax.podcaster.util;

import java.sql.SQLException;

public final class Exceptions {
    private Exceptions() {}

    public static boolean isUniqueViolation(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException sql && "23505".equals(sql.getSQLState())) return true;
            if (c.getCause() == c) break;
        }
        return false;
    }

    /** The exception's own message if it has one (keeps context such as "Model did not return valid JSON"), else the root cause's. */
    public static String message(Throwable t) {
        String msg = t.getMessage() != null && !t.getMessage().isBlank() ? t.getMessage() : rootMessage(t);
        return msg.length() > 2000 ? msg.substring(0, 2000) : msg;
    }

    public static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String msg = root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
        return msg.length() > 2000 ? msg.substring(0, 2000) : msg;
    }
}
