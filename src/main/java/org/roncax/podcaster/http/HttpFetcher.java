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

    @Inject
    public HttpFetcher(PodcasterConfig config) {
        this(config.http().userAgent(), config.http().timeout(), config.http().politenessDelay(),
                config.http().attempts(), config.http().retryDelay());
    }

    public HttpFetcher(String userAgent, Duration timeout, Duration politenessDelay, int attempts, Duration retryDelay) {
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
