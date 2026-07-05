package com.ubs.pesubapi;

import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

// Zonky embedded PostgreSQL: a real Postgres binary started in-process — no Docker daemon
// required — so JSONB columns and ::cast Flyway migrations behave exactly as in production.
//
// provider = ZONKY  -> spin up the bundled embedded-postgres binary for this platform.
// refresh  = NEVER (default) -> the database is bound to the shared Spring test context and
//            lives for the whole run, mirroring the previous singleton-container behaviour.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY)
public abstract class IntegrationTestBase {

    @Autowired private JdbcTemplate jdbc;

    // The embedded database lives for the whole run, so transactional rows created by one test
    // class stay visible to the next. Previously each test class cleaned an ad-hoc subset of
    // tables in its own @BeforeEach, which only worked if the (filesystem-dependent) execution
    // order happened to line cleanups up with leftovers — any mismatch surfaced as a facilities
    // FK violation. This single FK-safe TRUNCATE gives every test a clean slate up front.
    //
    // JUnit runs superclass @BeforeEach before the subclass's, so each test starts from empty
    // transactional tables and then seeds its own fixtures. Only facility-scoped (transactional)
    // tables are reset; Flyway-seeded reference data (config, fm_*, bb_templates*) is preserved.
    // CASCADE resolves the inter-table FK order; none of the preserved reference tables reference
    // these, so they are untouched.
    @BeforeEach
    void resetTransactionalTables() {
        jdbc.execute("""
            TRUNCATE TABLE
                match_queue_entries,
                submission_extractions,
                lp_rates,
                submissions,
                bb_snapshots,
                report_history,
                audit_log,
                lp_records,
                lp_master,
                facilities
            CASCADE
            """);
    }
}
