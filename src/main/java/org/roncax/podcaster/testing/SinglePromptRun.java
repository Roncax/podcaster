package org.roncax.podcaster.testing;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.roncax.podcaster.api.InvalidRequestException;
import org.roncax.podcaster.domain.*;
import org.roncax.podcaster.generation.Framing;
import org.roncax.podcaster.generation.Prompts;
import org.roncax.podcaster.generation.ScriptWriter;
import org.roncax.podcaster.generation.StoryRanker;
import org.roncax.podcaster.llm.ChatModelRegistry;
import org.roncax.podcaster.llm.LlmText;
import org.roncax.podcaster.prompts.PromptKey;
import org.roncax.podcaster.prompts.PromptResolver;
import org.roncax.podcaster.prompts.PromptSet;

/**
 * Sends one prompt (rank, one segment, or framing) to the model with a show's draft or production text, using the
 * test session's items and last dry-run as inputs. One call, no JSON repair retry, nothing stored.
 */
@ApplicationScoped
public class SinglePromptRun {
    /** Prompts that can be tested on their own; json_repair only exists as a retry after a bad reply. */
    public static final List<PromptKey> KEYS = List.of(PromptKey.RANK, PromptKey.SEGMENT, PromptKey.FRAMING);

    /** {@code parsed} is the reply's JSON object, pretty-printed (rank/framing only); {@code parseError} when it isn't valid. */
    public record Result(String key, int version, String mode, Integer story, String prompt, String reply,
                         String parsed, String parseError) {}
    public record Prepared(Show show, PromptKey key, PromptResolver.Mode mode, Integer story, PromptSet prompts, String prompt) {}

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Inject ChatModelRegistry models;
    @Inject PromptDryRun dryRun;
    @Inject ScriptWriter writer;
    @Inject PromptResolver resolver;

    /** Checks inputs and renders the prompt before the step starts, so the caller gets 400/404/409 synchronously. */
    public Prepared prepare(long showId, PromptKey key, PromptResolver.Mode mode, PromptDryRun.ItemsSource items,
                            Integer story, TestSession session) {
        if (!KEYS.contains(key)) {
            throw new InvalidRequestException(List.of("Prompt '" + key.dbKey() + "' cannot be tested on its own; choose rank, segment or framing"));
        }
        if (key == PromptKey.RANK) {
            PromptDryRun.Prepared p = dryRun.prepare(showId, mode, items, session);
            String prompt = StoryRanker.prompt(p.prompts(), p.show().name, p.show().language, p.show().focusPrompt, p.candidates());
            return new Prepared(p.show(), key, mode, null, p.prompts(), prompt);
        }
        Show show = QuarkusTransaction.requiringNew().call(() -> Show.<Show>findByIdOptional(showId)
                .orElseThrow(() -> new NotFoundException("Show " + showId + " not found")));
        PromptDryRun.Result last = session.dryRun();
        if (last == null || last.outline().segments().isEmpty()) {
            throw new MissingTestInputException("The " + key.dbKey() + " prompt needs an outline. Run the prompt dry-run first");
        }
        PromptSet prompts = resolver.resolve(showId, mode);
        List<OutlineSegment> segments = last.outline().segments();
        if (key == PromptKey.FRAMING) {
            List<String> headlines = segments.stream().map(OutlineSegment::headline).toList();
            return new Prepared(show, key, mode, null, prompts,
                    Prompts.framing(prompts, show.name, show.language, LocalDate.now(), headlines));
        }
        int index = story == null ? 0 : story;
        if (index < 0 || index >= segments.size()) {
            throw new InvalidRequestException(List.of("Unknown story " + index + "; the last dry-run has " + segments.size()));
        }
        OutlineSegment seg = segments.get(index);
        Map<Long, Item> byId = session.dryRunItems().stream().collect(Collectors.toMap(i -> i.id, Function.identity(), (a, b) -> a));
        List<Item> sources = seg.itemIds().stream().map(byId::get).filter(Objects::nonNull).toList();
        // scriptParts are intro, one part per segment, outro: segment i-1 is part i.
        String previousTail = index == 0 ? null : ScriptWriter.tail(last.scriptParts().get(index));
        String prompt = Prompts.segment(prompts, show.language, seg.headline(), seg.words(), writer.sources(sources),
                previousTail, show.focusPrompt);
        return new Prepared(show, key, mode, index, prompts, prompt);
    }

    public Result execute(Prepared p) {
        String modelName = p.key() == PromptKey.RANK ? p.show().effectiveRankerModel() : p.show().writerModel;
        ChatModel model = models.get(modelName);
        String reply = model.chat(UserMessage.from(p.prompt())).aiMessage().text();
        String parsed = null;
        String parseError = null;
        Class<?> type = p.key() == PromptKey.RANK ? Selection.class : p.key() == PromptKey.FRAMING ? Framing.class : null;
        if (type != null) {
            try {
                String json = LlmText.jsonObject(reply);
                MAPPER.readValue(json, type);
                parsed = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(MAPPER.readTree(json));
            } catch (Exception e) {
                parseError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
        }
        return new Result(p.key().dbKey(), p.prompts().version(p.key()),
                p.mode().name().toLowerCase(java.util.Locale.ROOT), p.story(), p.prompt(), reply, parsed, parseError);
    }
}
