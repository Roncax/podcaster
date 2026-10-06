package org.roncax.podcaster.runs;

import org.roncax.podcaster.domain.Run;
import org.roncax.podcaster.domain.RunStage;

/** One step of a run. Stages persist their output so a retry can resume at the failed stage. */
public interface Stage {
    RunStage stage();

    StageResult execute(Run run) throws Exception;
}
