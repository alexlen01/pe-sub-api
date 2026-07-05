# pe-sub-api

Spring Boot 4.1 / Java 25 REST API for the PE Sub Borrowing Base Platform.

## Prerequisites

- Java 25
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

## Resetting the local database

Required after migration file changes (e.g. renaming or replacing migration files):

```bash
docker compose down -v   # drop the volume — all data is lost
docker compose up -d     # fresh PostgreSQL
mvn spring-boot:run      # Flyway applies all migrations on first start
```

## BB template registry

`bb_templates` → `bb_template_tabs` → `bb_template_groups` describe how each Agent BB
workbook is laid out, so `ExtractionClientService` can pass `sheetNameHint`,
`headerRowHint`, `headerRowSpan`, and a group-header `classificationConfig` to
pe-sub-extraction. `header_row_index` is 0-based; `header_row_span` (V1_6) is the number
of physical rows a stacked column header occupies (e.g. Carlyle CP VII rows 84–85 → span 2).

`V1_6__bb_sample_templates.sql` seeds the five sampled formats from
`pe-sub-platform/public/BB_Templates.xlsx` (KKR Ascendant, Audax VII, CCP VII, AEP VII,
CP VII). These are identified by **fund/deal** in the sample, so `agent_bank` holds the
fund label as the template key until the owning facility is onboarded with its real bank.

## Facility Agent Bank Summary fields

`facilities` carries the Agent Bank Summary inputs `account_number`, `loan_amount`,
`maturity_date`, and `collateral_date` (plus the dormant `bank_status` / `bank_status_date` columns) and the Shadow BB
Borrowing Base inputs `facility_size` / `ubs_participation` (`V1_4__facility_size_participation.sql`,
stored as full-dollar `NUMERIC`). `POST /api/facilities` only sets name + agent bank; all of these
are populated afterwards via `PATCH /api/facilities/{id}` (partial update — only the fields present
in the body are applied), which the UI's Facility Edit screen calls. `PATCH /api/facilities/{id}`
now also accepts `name` and `agent_bank` (both blank-guarded — a `NOT NULL` column is never cleared);
renaming to a name already used by **another** facility returns `409 Conflict`. The Shadow BB result
figures shown alongside them on the dashboard are **not** stored on the facility; they live in
`bb_snapshots`, keyed by `facility_id` (see `POST /api/bb/run/{facilityId}` and
`GET /api/bb/snapshots/{facilityId}/latest`). For convenience the facility DTO (`GET /api/facilities`
and `/{id}`) surfaces the **latest** snapshot's `agentBB` / `ubsBB` / `bbDelta` / `ear` (in $millions,
`null` until a BB is run) via `BbSnapshotRepository.findLatestPerFacility()` — one query, no N+1 — so
the dashboard never hardcodes these and the UI needs no extra round-trip per facility.

## Deactivating & deleting a facility

A facility may only be **deactivated** or **deleted** while it holds **no LP records** — committed LP
data is never silently destroyed.

- **Deactivate** — `PATCH /api/facilities/{id}/status` with `{"status": "Inactive"}`. Transitioning to
  `Inactive` while LP records exist returns `409 Conflict`. Reactivate by patching the status back
  (e.g. `"Not Started"`); reactivation is unconditional. Other status transitions are unaffected.
- **Delete** — `DELETE /api/facilities/{id}` (handled by `FacilityService.delete`, `@Transactional`).
  Returns `204 No Content` on success, `404` if the facility does not exist, `409` if it still has
  LP records. The delete cascades to the facility's non-LP dependents (submissions and their
  extractions, match-queue entries, Shadow BB snapshots); `audit_log` rows are **preserved** with
  their `facility_id` nulled so history survives the deletion.

## Shadow BB summary (`GET /api/bb/summary-ext/{facilityId}`)

Powers the five-table Shadow BB summary panel (SHADOW_BB_ANALYSIS Tables 1–5). All monetary
fields — and the `dollars` in each breakdown row — are returned in **$millions** (the UI renders
full dollars or abbreviated `$M` by panel width). Key rules:

- **UBS advance rate** is resolved per-LP by `BbCalculationService.advanceRateFraction`: the stored
  `ubs_rate` (e.g. `"90%"`) takes precedence, falling back to a classification→rate map that covers
  **both** the legacy LP Master tiers and the UBS LP Classification labels
  (`Rated Investor` 90% · `FoF & Other > $10Bn AUM` 75% · `Unrated NAV > $1Bn` / `Corp Pension > $5Bn Assets` 65% · `Other Institutional` 50% · `Excluded` 0%).
  This drives UBS BB, the BUSA distribution (Table 3) and the UBS advance rate.
- **Total Called Capital** is calculated (`Capital Commitments − Uncalled Capital`) per LP when no
  `called_cap` is stored, rather than summing a column of blanks.
- **Money precision** — LP money is stored twice: the formatted display string (`"$12.3M"`) and a
  precise numeric column (`uncalled_capital_num`, `cap_commit_num`, `aum_num`, `agent_bb_num` on
  `lp_records` in `V1_1__schema.sql`). The engine (`BbCalculationService.moneyM`) reads the numeric
  first and only parses the rounded string when it is null — so the borrowing base is computed from
  exact dollars, not a re-parsed `$M` approximation.
- **Borrowing Base** (Table 2) derives from `facility_size` / `ubs_participation` plus the snapshot
  BB totals: UBS Participation Rate, Facility LTV (`size ÷ total uncalled`), Available Commitment
  (`MIN(size, agent BB)`), Facility Advance Rate (`agent BB ÷ total uncalled`).
- **LP Classification** (Table 5) rolls the granular labels into four canonical buckets:
  Rated / Unrated / Eligible / Excluded Investors.

## Reports (`/api/reports`)

All report endpoints read from persisted BB snapshots — nothing is recomputed at report time.
Money fields are in $millions; rate fields are decimal fractions (0.874 = 87.4%).

- `GET /api/reports/collateral/{facilityId}[?snapshotId=]` — Collateral Market Value & Coverage
  (BB certificate). Uses the facility's latest snapshot unless `snapshotId` names an earlier one
  (404 if the snapshot belongs to another facility). Returns facility identity, the snapshot's
  `summary` (UBS/Agent BB, EAR, deltas, LP counts), `totalEligibleUncalledM`, and `classBreakdown`
  — one row per LP category (`cls`, `count`, `uncalledM`, `ubbM`, `rate`), legacy tiers first in
  canonical order.
- `GET /api/reports/ear/{facilityId}` — EAR trend: one `{calculatedAt, ear, agentEar, earDelta}`
  point per snapshot, oldest first. Empty array when the facility has no snapshots yet.
- `GET /api/reports/agent-banks` — UBS exposure aggregated by agent bank across every facility's
  latest snapshot: `{agentBank, facilityCount, lpCount, ubsBBM, agentBBM, deltaM}`, sorted by
  UBS BB descending. Facilities without a snapshot still count toward `facilityCount`.
- `GET /api/reports/concentration/{facilityId}` — `{breaches: [...]}` from the latest snapshot
  (types: `single-lp`, `top10`, `unrated`, `non-us`). 404 when no snapshot exists. Breaches are
  detected on every BB run using the thresholds in the `conc_limits` config key (Config screen →
  Concentration Limits), matched by row label: `Single LP max`, `Top-10 LP max`,
  `Unrated max (aggregate)`, `Non-US LP max`. A missing row or key falls back to the seeded
  defaults (15/60/50/30%). The top-10 rule emits a `warning` within 10 percentage points below
  its limit and a `breach` above it. The `Pension fund max` row has no engine rule yet and is
  display-only.
- `GET /api/reports/history` — the 50 most recent report-generation entries, newest first.
- `POST /api/reports/history` — records a generated report:
  `{report, facilityId?, snapshotLabel?, format?}` → `201` with the stored entry. `report` is
  required (400); an unknown `facilityId` is a 404; omitting it marks a portfolio-wide report.
  History lives in `report_history` (`V1_4__report_history.sql`); `facility_name` is denormalised
  so entries survive facility deletion.

## LP Master ordering & commit

`lp_records.source_seq` (`V1_3__lp_source_seq.sql`) stores each LP's position in its
originating Agent BB — the extraction row index. The LP listing (`GET /api/lps?facilityId=`)
and the Shadow BB run result (`POST /api/bb/run/{facilityId}`) return LPs in this **natural
(source-file) order**, falling back to investor name; rows without a source position
(manually-created or legacy) sort last. Commit (`commitAcceptedMatches`) and the direct
upsert (`LpMasterService.upsertAll`) both populate `source_seq`.

Match-queue entries carry the extraction's own `rowIndex` (the source-sheet row, which
starts below the header), and commit looks each accepted entry's row up by that same index.
These two must use the same index space — otherwise the first *header-offset* accepted rows
find no row at commit and are silently skipped (the cause of a 900-row file inserting 893).

## Other commands

```bash
mvn package              # build fat JAR → target/pe-sub-api-1.0.0.jar
mvn package -DskipTests  # skip tests during build
java -jar target/pe-sub-api-1.0.0.jar
```

## Authentication & authorization

The API is stateless and header/token-based (no sessions, no CSRF cookies). Identity is resolved
by a security filter that runs in one of two modes, selected by `app.security.mode`:

| Mode | Behaviour |
|---|---|
| `dev` (default) | Every request is authenticated as the configured dev identity (`app.security.dev-user`, role `ANALYST`). Keeps local standalone runs and the header-less UI working without a login flow. |
| `gateway` | Identity is taken from the SSO reverse-proxy headers `X-Auth-User` / `X-Auth-Roles`. A request without a valid user header is rejected `401`. Set this in any shared/production environment. |

Roles mirror `pe-sub-docs/RBAC_ROLES.md`: **ANALYST** (day-to-day operator + configurator) and
**ATM** (Account/Transaction Manager). Authorization highlights:

- Configuration surfaces (`PUT /api/config/**`, `/api/field-mapping/**` mutations, `/api/bb-templates/**`) require `ANALYST`.
- `POST /api/lps/ingest` is service-to-service only and requires the `SERVICE` role — it is never reachable by an operator.
- Public (no auth): `GET /api/ping`, `GET /health`, `GET /api/notifications/**`, and CORS preflight.

Audit entries now record the **authenticated principal** (previously a hardcoded operator name);
in `dev` mode that is `app.security.dev-user`. The 4-eye separation on submission completion is a
Phase-2 workflow control and is not yet enforced.

## Environment variables

| Variable | Default | Description |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/pesub` | JDBC connection URL |
| `SPRING_DATASOURCE_USERNAME` | `pesub` | DB username |
| `SPRING_DATASOURCE_PASSWORD` | `password` | DB password |
| `PORT` | `3001` | HTTP port |
| `PE_SUB_EXTRACTION_URL` | `http://localhost:3002` | pe-sub-extraction base URL |
| `LOG_PATH` | `logs` | Log output directory |
| `app.uploads.path` | `uploads` | Uploaded file storage directory |
| `APP_SECURITY_MODE` | `dev` | Authentication mode: `dev` or `gateway` |
| `APP_SECURITY_DEV_USER` | `local.analyst@ubs.dev` | Identity stamped on audit entries in `dev` mode |
