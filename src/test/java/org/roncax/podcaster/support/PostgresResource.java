package org.roncax.podcaster.support;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

/** Real PostgreSQL for tests without Docker (embedded binaries). */
public class PostgresResource implements QuarkusTestResourceLifecycleManager {
    private EmbeddedPostgres postgres;

    @Override
    public Map<String, String> start() {
        try {
            postgres = EmbeddedPostgres.builder().start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Map.of(
                "quarkus.datasource.jdbc.url", postgres.getJdbcUrl("postgres", "postgres"),
                "quarkus.datasource.username", "postgres",
                "quarkus.datasource.password", "postgres");
    }

    @Override
    public void stop() {
        if (postgres == null) return;
        try {
            postgres.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
