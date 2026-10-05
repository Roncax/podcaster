package org.roncax.podcaster.llm;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import java.util.ArrayList;
import java.util.List;

/** Asks a model for a JSON object and maps it to a type, with one repair attempt. */
public final class JsonChat {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    static final String REPAIR = "Your previous reply could not be parsed as JSON (%s). "
            + "Reply again with ONLY the JSON object: no prose, no code fences.";

    private JsonChat() {}

    public static <T> T ask(ChatModel model, String prompt, Class<T> type) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from(prompt));
        for (int attempt = 1; ; attempt++) {
            String reply = model.chat(messages).aiMessage().text();
            try {
                return MAPPER.readValue(LlmText.jsonObject(reply), type);
            } catch (Exception e) {
                if (attempt >= 2) {
                    throw new GenerationException("Model did not return valid JSON after 2 attempts: " + e.getMessage(), e);
                }
                messages.add(AiMessage.from(reply == null || reply.isBlank() ? "(empty reply)" : reply));
                messages.add(UserMessage.from(REPAIR.formatted(e.getMessage())));
            }
        }
    }
}
