package org.roncax.podcaster.publishing;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.List;
import org.jboss.logging.Logger;
import org.roncax.podcaster.domain.Episode;
import org.roncax.podcaster.domain.Show;

@ApplicationScoped
public class RetentionService {
    private static final Logger LOG = Logger.getLogger(RetentionService.class);

    @Inject AudioStorage storage;

    public int apply(long showId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Show show = Show.findById(showId);
            if (show == null) return 0;
            List<Episode> withAudio = Episode.list(
                    "showId = ?1 and audioPath is not null order by publishedAt desc nulls last, id desc", showId);
            int removed = 0;
            for (int i = Math.max(0, show.retainEpisodes); i < withAudio.size(); i++) {
                Episode e = withAudio.get(i);
                try {
                    storage.delete(e.audioPath);
                } catch (IOException ex) {
                    LOG.warnf("Could not delete %s: %s", e.audioPath, ex.getMessage());
                }
                e.audioPath = null;
                removed++;
            }
            return removed;
        });
    }
}
