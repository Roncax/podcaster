package org.roncax.podcaster.llm;

import dev.langchain4j.model.chat.ChatModel;
import io.quarkiverse.langchain4j.ModelName;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

/** Looks up quarkus-langchain4j named models ("slots") by name. */
@ApplicationScoped
public class ChatModelRegistry {
    private static final Pattern SLOT = Pattern.compile("^quarkus\\.langchain4j\\.([a-z0-9-]+)\\.chat-model\\.provider$");
    /** Provider id → config prefix under quarkus.langchain4j. */
    static final Map<String, String> PREFIXES = Map.of(
            "openai", "openai",
            "anthropic", "anthropic",
            "ollama", "ollama",
            "ai-gemini", "ai.gemini");

    @Inject @Any Instance<ChatModel> models;

    public SortedSet<String> availableNames() {
        Config config = ConfigProvider.getConfig();
        SortedSet<String> names = new TreeSet<>();
        for (String property : config.getPropertyNames()) {
            Matcher m = SLOT.matcher(property);
            if (!m.matches()) continue;
            String slot = m.group(1);
            String provider = config.getValue(property, String.class);
            String prefix = PREFIXES.getOrDefault(provider, provider);
            boolean enabled = config
                    .getOptionalValue("quarkus.langchain4j." + prefix + "." + slot + ".enable-integration", Boolean.class)
                    .orElse(true);
            if (enabled) names.add(slot);
        }
        return names;
    }

    public boolean isAvailable(String name) {
        return name != null && availableNames().contains(name);
    }

    public ChatModel get(String name) {
        if (!isAvailable(name)) throw new UnknownModelException(name, availableNames());
        return models.select(ModelName.Literal.of(name)).get();
    }
}
