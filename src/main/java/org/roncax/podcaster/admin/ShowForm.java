package org.roncax.podcaster.admin;

import java.util.ArrayList;
import java.util.List;
import org.jboss.resteasy.reactive.RestForm;
import org.roncax.podcaster.api.ShowRequest;
import org.roncax.podcaster.domain.Show;

/** HTML form backing bean; all fields are strings so invalid input can be re-rendered. */
public class ShowForm {
    @RestForm public String name;
    @RestForm public String slug;
    @RestForm public String description;
    @RestForm public String language;
    @RestForm public String voiceId;
    @RestForm public String lengthScale;
    @RestForm public String writerModel;
    @RestForm public String rankerModel;
    @RestForm public String focusPrompt;
    @RestForm public String targetDurationMinutes;
    @RestForm public String minItems;
    @RestForm public String cron;
    @RestForm public String enabled;
    @RestForm public String retainEpisodes;

    public static ShowForm defaults() {
        ShowForm f = new ShowForm();
        f.language = "it";
        f.lengthScale = "1.0";
        f.targetDurationMinutes = "20";
        f.minItems = "3";
        f.retainEpisodes = "30";
        f.enabled = "on";
        return f;
    }

    public static ShowForm from(Show s) {
        ShowForm f = new ShowForm();
        f.name = s.name;
        f.slug = s.slug;
        f.description = s.description;
        f.language = s.language;
        f.voiceId = s.voiceId;
        f.lengthScale = String.valueOf(s.lengthScale);
        f.writerModel = s.writerModel;
        f.rankerModel = s.rankerModel;
        f.focusPrompt = s.focusPrompt;
        f.targetDurationMinutes = String.valueOf(s.targetDurationMinutes);
        f.minItems = String.valueOf(s.minItems);
        f.cron = s.cron;
        f.enabled = s.enabled ? "on" : null;
        f.retainEpisodes = String.valueOf(s.retainEpisodes);
        return f;
    }

    /** Converts to a request, collecting number-format errors into {@code errors}. */
    public ShowRequest toRequest(List<String> errors) {
        return new ShowRequest(trim(name), trim(slug), trim(description), trim(language), trim(voiceId),
                decimal("lengthScale", lengthScale, errors), trim(writerModel), trim(rankerModel), trim(focusPrompt),
                integer("targetDurationMinutes", targetDurationMinutes, errors), integer("minItems", minItems, errors),
                trim(cron), enabled != null, integer("retainEpisodes", retainEpisodes, errors));
    }

    private static String trim(String s) { return s == null ? null : s.trim(); }

    private static Integer integer(String field, String value, List<String> errors) {
        if (value == null || value.isBlank()) return null;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            errors.add(field + " must be a number");
            return null;
        }
    }

    private static Double decimal(String field, String value, List<String> errors) {
        if (value == null || value.isBlank()) return null;
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            errors.add(field + " must be a number");
            return null;
        }
    }

    public static List<String> newErrors() { return new ArrayList<>(); }
}
