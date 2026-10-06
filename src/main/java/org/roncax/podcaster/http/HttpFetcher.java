package org.roncax.podcaster.http;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.roncax.podcaster.config.PodcasterConfig;
import org.roncax.podcaster.util.Retries;
import org.roncax.podcaster.util.RetryableException;

@ApplicationScoped
public class HttpFetcher {
    private final HttpClient client;
    private final String userAgent;
    private final Duration timeout;
    private final Duration politenessDelay;
    private final int attempts;
    private final Duration retryDelay;
    private final Map<String, Instant> nextAllowed = new ConcurrentHashMap<>();
    private final HttpClient noRedirectClient;
    private final java.util.function.Predicate<URI> untrustedPolicy;
    private static final int MAX_REDIRECTS = 5;

    @Inject
    public HttpFetcher(PodcasterConfig config) {
        this(config.http().userAgent(), config.http().timeout(), config.http().politenessDelay(),
                config.http().attempts(), config.http().retryDelay(),
                config.http().allowPrivateHosts() ? UrlGuard::isHttp : UrlGuard::isPublicHttp);
    }

    public HttpFetcher(String userAgent, Duration timeout, Duration politenessDelay, int attempts, Duration retryDelay) {
        this(userAgent, timeout, politenessDelay, attempts, retryDelay, UrlGuard::isPublicHttp);
    }

    public HttpFetcher(String userAgent, Duration timeout, Duration politenessDelay, int attempts, Duration retryDelay,
                       java.util.function.Predicate<URI> untrustedPolicy) {
        this.untrustedPolicy = untrustedPolicy;
        this.noRedirectClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(timeout).build();
        this.userAgent = userAgent;
        this.timeout = timeout;
        this.politenessDelay = politenessDelay;
        this.attempts = attempts;
        this.retryDelay = retryDelay;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(timeout)
                .build();
    }

    public byte[] get(String url) throws FetchException {
        return get(url, attempts);
    }

    /** Fetches a URL from an untrusted origin: single attempt, every redirect hop checked against the policy. */
    public byte[] getUntrusted(String url) throws FetchException {
        return getUntrusted(url, untrustedPolicy);
    }

    public byte[] getUntrusted(String url, java.util.function.Predicate<URI> policy) throws FetchException {
        URI current;
        try {
            current = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new BlockedUrlException("Invalid URL: " + url);
        }
        try {
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                if (!policy.test(current)) throw new BlockedUrlException("Refusing to fetch non-public URL " + current);
                awaitPoliteness(current.getHost());
                HttpRequest request = HttpRequest.newBuilder(current).timeout(timeout)
                        .header("User-Agent", userAgent).header("Accept-Language", "it-IT,it;q=0.9,en;q=0.8").GET().build();
                HttpResponse<byte[]> response = noRedirectClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                int status = response.statusCode();
                if (status >= 300 && status < 400) {
                    String location = response.headers().firstValue("Location")
                            .orElseThrow(() -> new FetchException("Redirect without Location from " + url));
                    current = current.resolve(location.trim());
                    continue;
                }
                if (status >= 400) throw new FetchException("HTTP " + status + " from " + current);
                return response.body();
            }
        } catch (FetchException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FetchException("Interrupted fetching " + url, e);
        } catch (Exception e) {
            throw new FetchException("Failed fetching " + url + ": " + e.getMessage(), e);
        }
        throw new FetchException("Too many redirects for " + url);
    }

    public byte[] get(String url, int attempts) throws FetchException {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new FetchException("Invalid URL: " + url, e);
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("User-Agent", userAgent)
                .header("Accept-Language", "it-IT,it;q=0.9,en;q=0.8")
                .GET()
                .build();
        try {
            return Retries.withBackoff(attempts, retryDelay, () -> {
                awaitPoliteness(uri.getHost());
                HttpResponse<byte[]> response;
                try {
                    response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                } catch (IOException e) {
                    throw new RetryableException("I/O error fetching " + url + ": " + e.getMessage(), e);
                }
                int status = response.statusCode();
                if (status == 429 || status >= 500) throw new RetryableException("HTTP " + status + " from " + url);
                if (status >= 400) throw new FetchException("HTTP " + status + " from " + url);
                return response.body();
            });
        } catch (FetchException e) {
            throw e;
        } catch (RetryableException e) {
            throw new FetchException(e.getMessage(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FetchException("Interrupted fetching " + url, e);
        } catch (Exception e) {
            throw new FetchException("Failed fetching " + url + ": " + e.getMessage(), e);
        }
    }

    private void awaitPoliteness(String host) throws InterruptedException {
        if (host == null || politenessDelay.isZero()) return;
        Instant wait;
        synchronized (nextAllowed) {
            Instant now = Instant.now();
            Instant allowed = nextAllowed.getOrDefault(host, now);
            Instant slot = allowed.isAfter(now) ? allowed : now;
            nextAllowed.put(host, slot.plus(politenessDelay));
            wait = slot;
        }
        long millis = Duration.between(Instant.now(), wait).toMillis();
        if (millis > 0) Thread.sleep(millis);
    }
}
