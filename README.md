# pe-sub-api

Spring Boot 3.3 / Java 21 REST API for the PE Sub Borrowing Base Platform.

## Stack

- Java 21 (Eclipse Temurin), Spring Boot 3.5, Maven 3.9
- Spring Data JPA (Hibernate 6), PostgreSQL 16
- Flyway — migrations applied automatically on startup from `src/main/resources/db/migration/`
- Jackson — JSONB serialisation for `bb_snapshots.result`
- Server-Sent Events (SSE) for real-time notifications

## Prerequisites

- Java 21
- Maven 3.9+
- Docker (for PostgreSQL)

## Getting started

```bash
# 1. Start PostgreSQL
docker compose up -d

# 2. Start the API (Flyway applies all migrations on first start)
mvn spring-boot:run
```

API runs at `http://localhost:3001`. Health check: `GET /health`.

## Dev container

Open in VS Code and select **Reopen in Container**. The devcontainer starts PostgreSQL alongside the app container, forwards port 3001, and installs the Java + Spring Boot extensions automatically.

```bash
# Runs inside the container on creation — no manual step needed:
mvn dependency:resolve -q
```

## Resetting the local database

Required after migration file changes (e.g. renaming or replacing migration files):

```bash
docker compose down -v   # drop the volume — all data is lost
docker compose up -d     # fresh PostgreSQL
mvn spring-boot:run      # Flyway applies all migrations on first start
```

## Other commands

```bash
mvn package              # build fat JAR → target/pe-sub-api-0.1.0.jar
mvn package -DskipTests  # skip tests during build
java -jar target/pe-sub-api-0.1.0.jar  # run the JAR directly
```

## Environment variables

| Variable | Default | Description |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/pesub` | JDBC connection URL |
| `SPRING_DATASOURCE_USERNAME` | `pesub` | DB username |
| `SPRING_DATASOURCE_PASSWORD` | `password` | DB password |
| `PORT` | `3001` | HTTP port |
| `LOG_PATH` | `C:/Users/alexl/apps/pe-sub/logs` | Log output directory |
| `app.uploads.path` | `C:/Users/alexl/apps/pe-sub/uploads` | File upload directory |

Copy `.env.example` and adjust as needed. For production, inject these via Azure Key Vault or Container App environment config.

## Logs

Logs are written to `$LOG_PATH/pe-sub-api.log` with daily rolling (30-day retention, 10 MB per file).

## API

Full OpenAPI spec: `pe-sub-docs/openapi.yaml`.  
Solution design: `pe-sub-docs/SOLUTION_DESIGN.md`.

### Facilities — `/api/facilities`

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/facilities` | List all facilities (sorted by name) |
| `GET` | `/api/facilities/{id}` | Get a single facility |
| `POST` | `/api/facilities` | Create a facility (`name`, `agentBank`) |
| `PATCH` | `/api/facilities/{id}/status` | Update facility status |

### LPs — `/api/lps`

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/lps` | List LPs — filter by `facilityId`, `cls`, or `search` |
| `GET` | `/api/lps/{id}` | Get a single LP |
| `PATCH` | `/api/lps/{id}` | Update LP fields: `cls`, `clsTag`, `abb`, `inc`, `rcl`, `notes` |
| `POST` | `/api/lps/ingest` | Ingest extracted LP records from pe-sub-extraction (see below) |

#### `POST /api/lps/ingest`

Called by `pe-sub-extraction` after parsing an agent schedule file. Accepts an `IngestRequest` body containing the facility ID and the full `ExtractionResult` payload. For each extracted LP row:

1. Runs fuzzy name matching (Jaro-Winkler + Levenshtein) against the facility's existing LP records.
2. **Updated** — match score ≥ auto-accept threshold and all fields meet the 70% confidence minimum; writes `aum`, `capCommit`, `uc`, `agentRate`, `agentConc` on the matched LP.
3. **Queued** — medium-confidence match or extraction flagged low-confidence fields; no data written, returned for credit officer review.
4. **Skipped** — below review-queue threshold or no investor name extracted.

Writes an `LP Data Updated` audit event when at least one LP is updated.

### Borrowing Base — `/api/bb`

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/bb/run/{facilityId}` | Compute and persist a shadow BB snapshot |
| `GET` | `/api/bb/snapshots/{facilityId}` | All BB snapshots for a facility (ascending) |
| `GET` | `/api/bb/snapshots/{facilityId}/latest` | Most recent BB snapshot |
| `GET` | `/api/bb/summary-ext/{facilityId}` | Extended analytics summary (concentration, call rate, BB comparison) |

### Submissions — `/api/submissions`

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/submissions` | Upload agent schedule file (multipart: `facilityId`, `agentBank`, `periodMonth`, `file`) |
| `GET` | `/api/submissions` | List submissions — filter by `facilityId` |
| `GET` | `/api/submissions/{id}` | Get a single submission |

Max upload size: 50 MB. Files are stored on disk at `app.uploads.path`.

### Field Mapping — `/api/field-mapping`

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/field-mapping/alias-groups` | Canonical fields with aliases, grouped by category |
| `GET` | `/api/field-mapping/canonical-fields` | Flat list of canonical fields (`value`, `label`) |
| `GET` | `/api/field-mapping/blocklist` | Qualifier blocklist (terms that disqualify a column header) |
| `GET` | `/api/field-mapping/suggestions` | User/AI mapping suggestions |
| `POST` | `/api/field-mapping/aliases` | Add an alias (`canonicalFieldId`, `text`, `tier`, `bank`) |
| `PATCH` | `/api/field-mapping/aliases/{id}` | Update alias text or bank |
| `DELETE` | `/api/field-mapping/aliases/{id}` | Remove an alias |
| `POST` | `/api/field-mapping/suggestions` | Submit a mapping suggestion |

### LP Matching — `/api/matching`

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/matching/test` | Test fuzzy LP name match (`{ "name": "..." }`) |

### Reports — `/api/reports`

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/reports/collateral/{facilityId}` | Collateral summary from the latest BB snapshot |
| `GET` | `/api/reports/concentration/{facilityId}` | Concentration breaches from the latest BB snapshot |

### Audit — `/api/audit`

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/audit` | Full audit log |
| `POST` | `/api/audit/login` | Record a login event |

### Notifications — `/api/notifications`

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/notifications/events` | SSE stream — broadcasts facility status changes, BB runs, LP reclassifications, and uploads in real time |

## Database schema

Three Flyway migrations, applied in order:

| Migration | Contents |
|---|---|
| `V1_1__schema.sql` | Core tables: `users`, `facilities`, `lps`, `bb_snapshots`, `config`, `submissions`, `audit_log` |
| `V1_2__seed.sql` | Reference data for `config` |
| `V1_3__field_mapping.sql` | Field mapping tables (`fm_canonical_fields`, `fm_aliases`, `fm_blocklist`, `fm_suggestions`) + full seed data |

## Project structure

```
src/main/java/com/ubs/pesubapi/
  config/       CORS and web configuration
  controller/   AuditController, BbController, ConfigController, FacilityController,
                FieldMappingController, HealthController, LpController,
                MatchingController, NotificationController, ReportController,
                SubmissionController
  dto/          Java records: BbResult, BbSummary, BbBreach, ComputedLp
  entity/       JPA entities: Facility, Lp, BbSnapshot, ConfigEntry, AuditLog,
                Submission, FmCanonicalField, FmAlias, FmBlocklistEntry, FmSuggestion
  entity/converter/  BbResultConverter, JsonNodeConverter (PGobject ↔ JSONB)
  repository/   Spring Data JPA repositories
  service/      AuditLogService, BbCalculationService, ConfigService,
                MatchingService, NotificationService
src/main/resources/
  application.yml
  db/migration/V1_1__schema.sql
  db/migration/V1_2__seed.sql
  db/migration/V1_3__field_mapping.sql
```
