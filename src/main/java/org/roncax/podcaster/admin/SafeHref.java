package org.roncax.podcaster.admin;

import io.quarkus.qute.TemplateExtension;
import java.net.URI;
import org.roncax.podcaster.http.UrlGuard;

/** {@code {url.safeHref}}: the URL when it is http(s), otherwise null. Feed links must never become javascript: hrefs. */
@TemplateExtension
public class SafeHref {
    private SafeHref() {}

    static String safeHref(String url) {
        return isHttp(url) ? url : null;
    }

    public static boolean isHttp(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            return UrlGuard.isHttp(URI.create(url.trim()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
