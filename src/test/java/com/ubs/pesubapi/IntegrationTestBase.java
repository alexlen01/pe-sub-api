package com.ubs.pesubapi;

import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

// Zonky embedded PostgreSQL: a real Postgres binary started in-process — no Docker daemon
// required — so JSONB columns and ::cast Flyway migrations behave exactly as in production.
//
// provider = ZONKY  -> spin up the bundled embedded-postgres binary for this platform.
// refresh  = NEVER (default) -> the database is bound to the shared Spring test context and
//            lives for the whole run, mirroring the previous singleton-container behaviour.
//            Test classes remain responsible for their own teardown.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY)
public abstract class IntegrationTestBase {
}
