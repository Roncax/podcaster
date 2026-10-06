package org.roncax.podcaster.api;

import java.util.List;
import org.roncax.podcaster.ingestion.RawItem;

public record SourceTestResult(List<RawItem> items, String sampleText, String error) {}
