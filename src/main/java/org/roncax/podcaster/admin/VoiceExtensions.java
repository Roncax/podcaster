package org.roncax.podcaster.admin;

import io.quarkus.qute.TemplateExtension;
import java.util.Collection;
import java.util.List;
import org.roncax.podcaster.tts.RoutingTtsEngine;

/** {@code {voices.localVoices}} / {@code {voices.cloudVoices}}: split a voice list by engine for grouped selects. */
@TemplateExtension
public class VoiceExtensions {
    private VoiceExtensions() {}

    static List<String> localVoices(Collection<String> voices) {
        return voices.stream().filter(v -> !RoutingTtsEngine.isSibilia(v)).toList();
    }

    static List<String> cloudVoices(Collection<String> voices) {
        return voices.stream().filter(RoutingTtsEngine::isSibilia).toList();
    }
}
