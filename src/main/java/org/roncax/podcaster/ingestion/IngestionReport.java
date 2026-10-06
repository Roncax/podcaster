package org.roncax.podcaster.ingestion;

import java.util.List;

public record IngestionReport(int sources, int newItems, List<String> errors) {
    public boolean allFailed() {
        return sources > 0 && errors.size() == sources;
    }
}
