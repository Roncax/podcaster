package org.roncax.podcaster.http;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/** Decides whether a URL from an untrusted origin (e.g. a Reddit post) may be fetched server-side. */
public final class UrlGuard {
    private UrlGuard() {}

    public static boolean isHttp(URI uri) {
        if (uri == null || uri.getScheme() == null || uri.getHost() == null || uri.getHost().isBlank()) return false;
        String scheme = uri.getScheme().toLowerCase();
        return scheme.equals("http") || scheme.equals("https");
    }

    /** True only for http(s) URLs whose host resolves exclusively to public addresses. */
    public static boolean isPublicHttp(URI uri) {
        if (!isHttp(uri)) return false;
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (isNonPublic(address)) return false;
            }
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    static boolean isNonPublic(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (b.length == 16 && (b[0] & 0xfe) == 0xfc) return true;                     // fc00::/7 unique local
        return b.length == 4 && (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64;          // 100.64.0.0/10 carrier-grade NAT
    }
}
