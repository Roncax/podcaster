package org.roncax.podcaster.support;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

public class FakeChatModel implements ChatModel {
    private final Deque<String> queued = new ArrayDeque<>();
    private Function<String, String> responder;
    public final List<ChatRequest> requests = new CopyOnWriteArrayList<>();

    public FakeChatModel respond(String... replies) {
        synchronized (queued) { queued.addAll(List.of(replies)); }
        return this;
    }

    public FakeChatModel responder(Function<String, String> responder) {
        this.responder = responder;
        return this;
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        requests.add(request);
        String text;
        synchronized (queued) { text = queued.poll(); }
        if (text == null) {
            if (responder == null) throw new IllegalStateException("FakeChatModel has no reply for: " + lastUser(request));
            text = responder.apply(lastUser(request));
        }
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }

    public String userMessage(int index) {
        return lastUser(requests.get(index));
    }

    public static String lastUser(ChatRequest request) {
        List<ChatMessage> messages = request.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage um) return um.singleText();
        }
        return "";
    }
}
