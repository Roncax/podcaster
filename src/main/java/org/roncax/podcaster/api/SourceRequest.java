package org.roncax.podcaster.api;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

public record SourceRequest(@NotBlank String connectorType, Map<String, String> config, Boolean fetchFullText, Boolean enabled) {}
