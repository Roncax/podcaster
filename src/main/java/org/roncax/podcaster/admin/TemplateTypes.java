package org.roncax.podcaster.admin;

import io.quarkus.qute.TemplateData;
import org.roncax.podcaster.api.SourceTestResult;
import org.roncax.podcaster.domain.*;
import org.roncax.podcaster.ingestion.RawItem;

/** Generates Qute value resolvers for types used inside untyped tag templates. */
@TemplateData(target = Show.class)
@TemplateData(target = Source.class)
@TemplateData(target = Run.class)
@TemplateData(target = Episode.class)
@TemplateData(target = RawItem.class)
@TemplateData(target = SourceTestResult.class)
@TemplateData(target = ShowForm.class)
public class TemplateTypes {}
