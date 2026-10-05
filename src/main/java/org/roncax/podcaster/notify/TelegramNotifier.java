package org.roncax.podcaster.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.jboss.logging.Logger;
import org.roncax.podcaster.config.PodcasterConfig;
import org.roncax.podcaster.domain.Run;
import org.roncax.podcaster.domain.Show;

@ApplicationScoped
public class TelegramNotifier implements Notifier {
    private static final Logger LOG = Logger.getLogger(TelegramNotifier.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_TEXT = 4000;

    @Inject PodcasterConfig config;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    void onStart(@Observes StartupEvent event) {
        if (!enabled()) LOG.info("Telegram notifications disabled (TELEGRAM_BOT_TOKEN / TELEGRAM_CHAT_ID not set)");
    }

    boolean enabled() {
        return config.telegram().botToken().isPresent() && config.telegram().chatId().isPresent();
    }

    @Override
    public void runFailed(Show show, Run run, String error) {
        send("❌ Podcaster: run #" + run.id + " of \"" + show.name + "\" failed at " + run.stage + "\n"
                + error + "\n" + config.baseUrl() + "/admin/shows/" + show.id);
    }

    @Override
    public void warning(Show show, String message) {
        send("⚠️ Podcaster: \"" + show.name + "\"\n" + message);
    }

    private void send(String text) {
        if (!enabled()) return;
        try {
            String body = MAPPER.writeValueAsString(Map.of(
                    "chat_id", config.telegram().chatId().get(),
                    "text", text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text));
            HttpRequest request = HttpRequest.newBuilder(URI.create(
                            config.telegram().apiUrl() + "/bot" + config.telegram().botToken().get() + "/sendMessage"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) LOG.warnf("Telegram returned HTTP %d: %s", response.statusCode(), response.body());
        } catch (Exception e) {
            LOG.warnf("Telegram notification failed: %s", e.getMessage());
        }
    }
}
