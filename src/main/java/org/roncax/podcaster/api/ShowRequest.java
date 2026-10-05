package org.roncax.podcaster.api;

import jakarta.validation.constraints.*;

public record ShowRequest(
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{0,99}", message = "use lowercase letters, digits and dashes") String slug,
        String description,
        @NotBlank @Size(max = 20) String language,
        @NotBlank String voiceId,
        @DecimalMin("0.5") @DecimalMax("2.0") Double lengthScale,
        @NotBlank String writerModel,
        String rankerModel,
        String focusPrompt,
        @Min(1) @Max(120) Integer targetDurationMinutes,
        @Min(1) @Max(100) Integer minItems,
        String cron,
        Boolean enabled,
        @Min(1) @Max(1000) Integer retainEpisodes) {}
