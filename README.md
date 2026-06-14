# pe-sub-api

Spring Boot 3.5 / Java 21 REST API for the PE Sub Borrowing Base Platform.

## Stack

- Java 21 (Eclipse Temurin), Spring Boot 3.5, Maven 3.9
- Spring Data JPA (Hibernate 6), PostgreSQL 16
- Flyway — migrations applied automatically on startup from `src/main/resources/db/migration/`
- Jackson — JSONB serialisation for `bb_snapshots.result`, `extracted_lps`, `field_mappings`, `reasons`
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
java -jar target/pe-sub-api-0.1.0.jar
```

## Environment variables

| Variable | Default | Description |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/pesub` | JDBC connection URL |
| `SPRING_DATASOURCE_USERNAME` | `pesub` | DB username |
| `SPRING_DATASOURCE_PASSWORD` | `password` | DB password |
| `PORT` | `3001` | HTTP port |
| `PE_SUB_EXTRACTION_URL` | `http://localhost:3002` | pe-sub-extraction base URL |
| `LOG_PATH` | `C:/Users/alexl/apps/pe-sub/logs` | Log output directory |
| `app.uploads.path` | `C:/Users/alexl/apps/pe-sub/uploads` | Uploaded file storage directory |

## Logs

Logs are written to `$LOG_PATH/pe-sub-api.log` with daily rolling (30-day retention, 10 MB per file).

## API

Full OpenAPI spec: `pe-sub-docs/openapi.yaml`.

### Facilities — `/api/facilities`

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/facilities` | List all facilities (sorted by name) |
| `GET` | `/api/facilities/{id}` | Get a single facility |
| `POST` | `/api/facilities` | Create a facility (`name`, `agentBank`) |
| `PATCH` | `/api/facilities/{id}/status` | Update facility status |

Each facility DTO includes `lpCount` — the number of LP records persisted for that facility (computed live from `lp_records`, `0` when none). Drives the LP-count shown on the LP Master facility cards.

### LPs — `/api/lps`

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/lps` | List LPs — filter by `facilityId`, `cls`, or `search` |
| `GET` | `/api/lps/{id}` | Get a single LP |
| `PATCH` | `/api/lps/{id}` | Update LP fields: `cls`, `clsTag`, `abb`, `inc`, `rcl`, `notes` |
| `PATCH` | `/api/lps/classification` | Batch-save classification & rate edits from the Shadow BB screen onto persisted LP records |
| `POST` | `/api/lps/ingest` | Ingest extracted LP records from pe-sub-extraction |

> **One LP record per `(facility_id, investor_name)`.** A `UNIQUE` constraint (migration `V1_3`)
> enforces that an investor appears at most once per facility. The same investor may exist in
> multiple facilities (one row each); re-submitting the same Agent BB updates the existing rows
> in place rather than creating duplicates. All write paths (`ingest`, commit-on-decision,
> `bb/run` upsert) key on `(facility_id, investor_name)` and dedupe incoming rows by name.

#### `PATCH /api/lps/classification`

The "Save" action on the **LP Classification & Rate Assignment** screen. LP records are created
up front when the analyst commits match decisions (step 4 → 5), so this writes the credit officer's
edits onto **real** LP Master records — not a draft blob. Request:

```json
{
  "facilityId": 12,
  "effectiveDate": "2026-06",
  "rows": [
    { "name": "CalPERS", "cls": "Rated", "sp": "AAA", "inc": true,
      "uc": "$20.0M", "ubsAdvRatePct": 90.0, "ubsConcLimitPct": 7.5 }
  ]
}
```

Rows are matched to existing records by `(facilityId, name)`; unmatched names are ignored. LP entity
fields (`cls`, `sp`, `mdy`, `fitch`, `inc`, `uc`) update in place; `ubsAdvRatePct` / `ubsConcLimitPct`
(percentages) upsert into `lp_rates` for the period as decimal fractions. `effectiveDate` (`YYYY-MM`)
defaults to the current month. Returns `{ "updated": <count> }`.

#### `POST /api/lps/ingest`

Called internally by `LpIngestService` after pe-sub-extraction returns results. For each extracted LP row:

1. Runs fuzzy name matching (Jaro-Winkler + Levenshtein) against the facility's existing LP records.
2. **Updated** — match score ≥ auto-accept threshold (`AUTO_ACCEPT` band); writes `aum`, `capCommit`, `uc`, `agentRate`, `agentConc` on the matched LP.
3. **Queued** — `REVIEW_HIGH`/`REVIEW_LOW` band (candidate shown) or `NO_MATCH` band (potential new LP), or low-confidence extraction fields; placed in `match_queue_entries` for credit officer review with a `match_details` breakdown.
4. **Skipped** — no investor name extracted.

#### LP name matching algorithm (Solution Design §6)

The matching engine (`MatchingService`) implements the design's §6 pipeline:

- **Normalisation (§6.2)** — case fold, punctuation strip, whitespace collapse, legal-suffix strip, abbreviation expansion, and **retirement-suffix normalisation** (`Ret. Sys.` → `retirement system`, `Ret.` → `retirement`). Each step is config-toggleable under `matching_config`.
- **Similarity scoring (§6.3)** — combined score = `JW × jwWeight + Lev × levWeight` (defaults 0.6 / 0.4), each computed on the normalised forms. A length-band pre-filter restricts full scoring to plausible candidates (the in-app equivalent of the design's `pg_trgm` pre-filter; see note below).
- **Confidence bands (§6.4)** — four configurable bands: `AUTO_ACCEPT` (≥ `autoAccept`, default 95), `REVIEW_HIGH` (≥ `reviewQueue`, default 80), `REVIEW_LOW` (≥ `noMatch`, default 50), `NO_MATCH` (< `noMatch`). Only `AUTO_ACCEPT` resolves automatically; the other three are queued — nothing is auto-rejected.
- **Match analysis output (§6.5)** — each queue entry persists a `match_details` JSONB payload (normalised agent name, winning band, ranked top-5 candidates with JW / Lev / combined scores) returned by `GET /api/matching/queue`.

> **Deviation:** the design specifies a PostgreSQL `pg_trgm` trigram pre-filter to cap the candidate set. This implementation uses an in-memory length-band pre-filter that is provably loss-free against the configured thresholds (see `MatchingServiceTest`); `pg_trgm` remains the recommended path once LP Master scales past in-memory scanning.

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
| `POST` | `/api/submissions` | Upload agent BB file (multipart: `facilityId`, `agentBank`, `periodMonth`, `file`, `notes?`) |
| `GET` | `/api/submissions` | List submissions — filter by `facilityId` |
| `GET` | `/api/submissions/{id}` | Get a single submission |
| `POST` | `/api/submissions/{id}/abort` | Abort a submission (removes extracted data, resets facility status) |
| `POST` | `/api/submissions/{id}/confirm` | Confirm extraction; auto-learns BB template for the agent bank if not already known |
| `GET` | `/api/submissions/{id}/extracted-lps` | Extracted LP rows (JSONB) |
| `GET` | `/api/submissions/{id}/field-map` | Canonical field mapping table for the extraction |
| `GET` | `/api/submissions/{id}/doc-recognition` | Document recognition metadata (format, header row, row count, etc.) |
| `GET` | `/api/submissions/{id}/unrecognized-columns` | Column headers not matched to any canonical field |
| `POST` | `/api/submissions/{id}/remap` | Map an unrecognised column to a canonical field and re-run extraction |
| `POST` | `/api/submissions/{id}/reextract` | Re-run the full extraction pipeline for a submission |

Max upload size: 50 MB. Files stored at `app.uploads.path`.

### Field Mapping — `/api/field-mapping`

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/field-mapping/alias-groups` | Canonical fields with aliases, grouped by category |
| `GET` | `/api/field-mapping/canonical-fields` | Flat list of canonical fields (`value`, `label`, `extractable`) |
| `GET` | `/api/field-mapping/blocklist` | Qualifier blocklist |
| `GET` | `/api/field-mapping/suggestions` | User mapping suggestions |
| `POST` | `/api/field-mapping/aliases` | Add an alias (`canonicalFieldId`, `text`, `tier`, `bank`) |
| `PATCH` | `/api/field-mapping/aliases/{id}` | Update alias text or bank |
| `DELETE` | `/api/field-mapping/aliases/{id}` | Remove an alias |
| `POST` | `/api/field-mapping/suggestions` | Submit a mapping suggestion |

### LP Matching — `/api/matching`

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/matching/test` | Test fuzzy LP name match (`{ "name": "..." }`) |
| `GET` | `/api/matching/queue` | Match queue items — filter by `submissionId` |
| `PATCH` | `/api/matching/queue/{id}` | Record a decision (`accept`, `reject`, `override`) and optional `masterName` |

### Configuration — `/api/config`

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/config/eligibility` | Eligibility rules and advance rate schedules (`BUSA_TIERS`, `AGENT_TIERS`, `AGENT_RATE_PARAMS`, `ELIG_RULES`, `CONC_LIMITS`, `GLOBAL_SETTINGS`) |
| `PUT` | `/api/config/eligibility` | Update one eligibility section (`?section=busa_tiers\|agent_tiers\|agent_rate_params\|elig_rules\|conc_limits\|global_settings`) |
| `GET` | `/api/config/matching` | Matching configuration — thresholds, algorithm weights, suffix rules, abbreviation dictionary |
| `PUT` | `/api/config/matching` | Update matching configuration (`?section=thresholds\|weights\|suffixes\|abbreviations`) |
| `GET` | `/api/config/wizard` | Ingest wizard step configuration |
| `GET` | `/api/config/audit` | Audit log configuration (retention period, etc.) |
| `GET` | `/api/config/reports` | Report generation configuration |

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
| `GET` | `/api/notifications/events` | SSE stream — facility status changes, BB runs, LP reclassifications, uploads |

## Database schema

Three Flyway migrations, applied in order:

| Migration | Contents |
|---|---|
| `V1_1__schema.sql` | All tables: `users`, `facilities`, `lps`, `bb_snapshots`, `config`, `submissions`, `audit_log`, `submission_extractions`, `match_queue_entries`, `fm_canonical_fields` (with `is_derived` flag), `fm_aliases`, `fm_blocklist`, `fm_suggestions`, `bb_templates` (with `tranche_count`, `has_grouping_rows`, `has_color_flags`, `summary_rows_above_header`), `bb_template_tabs`, `bb_template_groups` |
| `V1_2__seed.sql` | Field Mapping Dictionary — 29 canonical fields across 7 groups (Identity & Classification, Commitment Data, Uncalled Data, Financial Scale, Borrowing Base, Concentration, Ratings) with full alias sets; Goldman Sachs / SVB / Wells Fargo template metadata; bb_template_groups for Goldman LP Grid group-header rows |
| `V1_3__shadow_bb_state.sql` | Adds `wizard_step INTEGER DEFAULT 1` and `shadow_bb_overrides JSONB` to `submissions`; tracks ingest wizard progress and persists LP classification/rate overrides from the Run Shadow BB step |
| `V1_4__lp_rates.sql` | `lp_rates` feed table (per-LP, per-period UBS advance / concentration rates); updates `agent_tiers` config to the 5-tier scale |
| `V1_5__lp_rates_seed.sql` | Seed rows for `lp_rates` |
| `V1_6__agent_lp_classification.sql` | Splits `LP Classification` into **Agent LP Classification** (raw input, extraction_key `AGENT_LP_CLASSIFICATION`) and **UBS LP Classification** (derived). Realigns Goldman Sachs `bb_template_groups` headers to the Agent LP Classification taxonomy (Rated Included, Non-Rated Included, Designated Institutional, Designated PWM, …) |

### Agent vs UBS LP Classification

`Agent LP Classification` is the agent's own category label, extracted verbatim from the Agent BB document — either a column or **group-header rows** that separate sections of LPs. When supplied as section rows, the agent's value is filled down onto every LP beneath the header by pe-sub-extraction. The recognised header texts are configured per agent bank in `bb_template_groups` and passed to the extraction service as `classificationConfig` (built by `ClassificationConfigBuilder`). `UBS LP Classification` is the platform-computed internal advance-rate tier (Rated / Unrated >2bn / Unrated 1–2bn / Eligible / Excluded), kept separate so the agent label can be cross-checked against the UBS tier.

## Project structure

```
src/main/java/com/ubs/pesubapi/
  config/       CORS and web configuration
  controller/   AuditController, BbController, ConfigController, FacilityController,
                FieldMappingController, HealthController, LpController,
                MatchingController, NotificationController, ReportController,
                SubmissionController
  dto/          Java records: BbResult, BbSummary, BbBreach, ComputedLp, ExtractionResponse
  entity/       JPA entities: AuditLog, BbSnapshot, BbTemplate, BbTemplateTab, BbTemplateGroup,
                ConfigEntry, Facility, FmAlias, FmBlocklistEntry, FmCanonicalField,
                FmSuggestion, Lp, MatchQueueEntry, Submission, SubmissionExtraction
  entity/converter/  BbResultConverter, JsonNodeConverter, StringListConverter (all PGobject ↔ JSONB)
  repository/   Spring Data JPA repositories for all entities, including BbTemplateTabRepository
                and BbTemplateGroupRepository
  service/      AliasConfigBuilder, AuditLogService, BbCalculationService,
                ConfigService, ExtractionClientService, LpIngestService,
                MatchingService, NotificationService
src/main/resources/
  application.yml
  db/migration/V1_1__schema.sql
  db/migration/V1_2__seed.sql
```
