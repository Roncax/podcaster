package org.roncax.podcaster.prompts;

import static org.junit.jupiter.api.Assertions.*;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class PerShowPromptsMigrationTest {

    @Test
    void forksShowsFromPinOrProductionAndDropsPins() throws Exception {
        try (EmbeddedPostgres pg = EmbeddedPostgres.builder().start()) {
            DataSource ds = pg.getPostgresDatabase();
            Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("5").load().migrate();
            try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
                st.execute("insert into prompts(key, description) values ('rank', 'r')");
                st.execute("insert into prompt_versions(prompt_key, version, body) values ('rank',1,'B1'),('rank',2,'B2'),('rank',3,'B3')");
                st.execute("insert into prompt_labels(prompt_key, label, version_id) select 'rank','production',id from prompt_versions where version = 2");
                st.execute("insert into prompt_labels(prompt_key, label, version_id) select 'rank','draft',id from prompt_versions where version = 3");
                st.execute("insert into shows(name, slug, language, voice_id, writer_model, feed_token) values"
                        + " ('A','a','it','v','m','tok-a'), ('B','b','it','v','m','tok-b')");
                st.execute("insert into show_prompt_overrides(show_id, prompt_key, version_id)"
                        + " select s.id, 'rank', v.id from shows s, prompt_versions v where s.slug = 'b' and v.version = 3");
                st.execute("insert into runs(show_id, trigger, stage, status, since) select id,'MANUAL','DONE','DONE',now() from shows where slug='a'");
                st.execute("insert into episodes(run_id, show_id, prompt_versions) select r.id, r.show_id, '{\"rank\":2}' from runs r");
            }

            Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();

            try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
                assertEquals("B2|Forked from default v2", one(st, "select v.body || '|' || v.note from prompt_versions v join shows s on s.id = v.show_id where s.slug = 'a' and v.version = 1"));
                assertEquals("B3|Forked from default v3", one(st, "select v.body || '|' || v.note from prompt_versions v join shows s on s.id = v.show_id where s.slug = 'b' and v.version = 1"));
                assertEquals("3", one(st, "select count(*) from prompt_versions where show_id is null"));
                assertEquals("4", one(st, "select count(*) from prompt_labels l join prompt_versions v on v.id = l.version_id where v.show_id = l.show_id and v.version = 1"));
                assertEquals("0", one(st, "select count(*) from prompt_labels where show_id is null"));
                assertNull(one(st, "select to_regclass('public.show_prompt_overrides')::text"));
                assertEquals("true", one(st, "select prompt_versions_global::text from episodes"));
            }
        }
    }

    private static String one(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next(), sql);
            return rs.getString(1);
        }
    }
}
