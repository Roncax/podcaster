package org.roncax.podcaster.http;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import org.junit.jupiter.api.Test;

class UrlGuardTest {
    @Test
    void allowsPublicHttpOnly() {
        assertTrue(UrlGuard.isPublicHttp(URI.create("https://8.8.8.8/news")));
        assertFalse(UrlGuard.isPublicHttp(URI.create("ftp://8.8.8.8/x")));
        assertFalse(UrlGuard.isPublicHttp(URI.create("javascript:alert(1)")));
    }

    @Test
    void rejectsPrivateLoopbackAndLinkLocal() {
        for (String u : new String[] {"http://localhost:8080/x", "http://127.0.0.1/", "http://10.0.0.5/", "http://192.168.1.1/",
                "http://172.16.0.1/", "http://169.254.169.254/latest/meta-data/", "http://[::1]/", "http://[fd00::1]/", "http://100.64.0.1/", "http://0.0.0.0/"}) {
            assertFalse(UrlGuard.isPublicHttp(URI.create(u)), u);
        }
    }
}
