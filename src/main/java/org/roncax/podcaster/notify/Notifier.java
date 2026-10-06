package org.roncax.podcaster.notify;

import org.roncax.podcaster.domain.Run;
import org.roncax.podcaster.domain.Show;

/** Out-of-band alerts. Implementations must never throw. */
public interface Notifier {
    void runFailed(Show show, Run run, String error);

    void warning(Show show, String message);
}
