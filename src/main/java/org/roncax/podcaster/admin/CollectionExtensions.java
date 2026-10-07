package org.roncax.podcaster.admin;

import io.quarkus.qute.TemplateExtension;
import java.util.Collection;

/** {@code {models.lacks(form.writerModel)}}: true for a non-blank value missing from the collection. */
@TemplateExtension
public class CollectionExtensions {
    private CollectionExtensions() {}

    static boolean lacks(Collection<?> values, String value) {
        return value != null && !value.isBlank() && !values.contains(value);
    }
}
