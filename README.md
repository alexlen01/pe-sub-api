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
Borrowing Base inputs `facility_size` / `ubs_participation` (consolidated schema `V1_1__schema.sql`,
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

## Deleting LP rows (manual correction path)

No matter how many analyst/reviewer checks the ingestion pipeline has, an erroneous row can slip
through — both delete endpoints exist for that correction and are audited with the investor name:

- **Facility LP record** — `DELETE /api/lpRecords/{id}` (`ShadowBbService.deleteLpRecord`,
  `@Transactional`). Detaches match-queue entries pointing at the record (decision history kept;
  `lp_rates` rows cascade), deletes the row, and **recomputes the facility's ranks** over the
  remaining population in the same transaction. The current BB snapshot is untouched — totals
  refresh on the next Run / Re-run Shadow BB. Surfaced in the UI as the Delete action in the
  LP Master screen's record panel. Audit event: `LP Record Deleted`.
- **Bank-wide LP Master row** — `DELETE /api/lp-master/{id}` (`LpMasterService.delete`,
  ANALYST-gated). Facility LP records referencing the row are detached (`lp_master_id` nulled),
  never deleted. Note: if the investor name still exists in a facility's records, the next
  accepted Shadow BB cycle's write-back re-creates a master row from that facility's data — to
  fully purge an erroneous LP, delete its facility record(s) first. Audit event: `LP Master Deleted`.

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
- **Agent BB** is read from the stored `abb`/`agent_bb_num` when present (including an explicit
  `"$0"` — an agent-reported value written at ingest); otherwise the engine derives it per LP as
  `MIN(uncalled, total uncalled × agent_conc) × agent_rate` (0 for excluded LPs). This keeps
  `totalABB`, Available Commitment, and Facility Advance Rate non-zero for facilities that have
  agent rates but no agent-reported Agent BB amounts.
- **Server-authoritative run** (`POST /api/bb/run/{facilityId}`) — the commit payload carries LP
  *inputs* only; engine outputs (`abb`, `ubb`, excess concentrations, `rank`) are not accepted.
  On every run the engine computes them and writes `ubb` / `ubs_excess_conc` / `agent_excess_conc`
  display strings and ranks back onto `lp_records` in the same transaction
  (`ShadowBbService.writeBackComputedValues`); `abb`/`agent_bb_num` are never touched by a run.
  The snapshot's per-LP rows carry `ucM`, `agentExcessM`, `pctAgentBB`, `pctUbsBB`, and the
  summary carries `totalUEC`, `totalUC`, `totalConcExcess`, `reclassCount` — the UI renders these
  rather than recomputing them.
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
- `GET /api/reports/collateral/{facilityId}/pdf[?snapshotId=&watermark=]` — downloads the same
  persisted certificate as a styled PDFBox PDF (`application/pdf`) with summary and LP-category
  tables, selected snapshot metadata, watermark, and confidentiality footer.
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
  History lives in `report_history` (consolidated schema `V1_1__schema.sql`); `facility_name` is
  denormalised so entries survive facility deletion.

## Per-LP concentration limit defaults

The BB engine resolves each LP's concentration limit through a fallback chain
(`BbCalculationService.perLpConc`). **The `Excluded` bucket is evaluated first**: an
excluded LP is forced to a hard 0 ahead of any explicit override or class default, so a
stale/misconfigured Excluded default can never leak into the borrowing base. Otherwise:

1. **Explicit per-LP limit** stored in `lp_records.ubs_conc` — a dollar amount (`"$25.0M"`)
   or a percent of total uncalled capital (`"7.5%"`).
2. **Classification default** from the `cls_conc_limit_defaults` config key
   (`V1_3__config.sql`) — a map of LP classification label (both taxonomies,
   dash-insensitive) to percent of total uncalled capital. Seeded to the **upper bound**
   of each class's accepted range from `pe-sub-docs/Concentration_Limits.xls` (Rated 20.0,
   Unrated 15.0, FoF 10.0, Corp Pension 12.5, Other Institutional 7.5, Excluded 0.0).
   Exposed as `CLS_CONC_LIMIT_DEFAULTS` in `GET /api/config/eligibility` and edited via
   `PUT /api/config/eligibility?section=cls_conc_limit_defaults` (Config screen → Per-LP
   Concentration Limit Defaults, next to the BUSA Advance Rate Schedule).
3. **Facility-level dollar limit** (`facilities.conc_limit_m`, default $25M).

A companion `cls_conc_limit_bounds` config key (`V1_3__config.sql`, also from the source
workbook) holds the accepted `{ min, max }` percent range per class. It is served as
`CLS_CONC_LIMIT_BOUNDS` in `GET /api/config/eligibility` and consumed by the LP record
entry form to **warn — without blocking** — when an analyst enters a limit outside the
class norm. The BB engine does not enforce it.

Class defaults can also be fed from `pe-sub-jobs` (`POST /jobs/cls-conc-limits-ingest`,
CSV `classification,limit_pct`), which posts the parsed map to
`PATCH /api/config/cls-conc-limit-defaults` (SERVICE role). The endpoint merges fed classes
into the map — unfed classes are preserved — and persists + refreshes the in-memory cache in
one step, so no follow-up reload is needed. `POST /api/config/reload` remains as a recovery
hook for out-of-band changes to the `config` table.

## Seed ingest endpoints (pe-sub-jobs)

pe-sub-jobs holds no database connection: all of its feeds write through SERVICE-gated bulk
endpoints on this API (which owns the schema and audits the writes):

| Endpoint | Feed | Semantics |
|---|---|---|
| `POST /api/facilities/ingest` | Agent Bank Summary CSV | Upsert by facility `name`; platform-owned fields (status, conc limit, facility size) never touched |
| `POST /api/lp-master/ingest` | LP Master CSV | Upsert by `investorName`; whole profile replaced (feed is authoritative) |
| `POST /api/lpRecords/seed` | Facility-LP seeds CSV | Full per-LP column set of the LP DB Export (31 fields incl. `ubsCls` pre-derived from row UBSAR via the rate tiers); facility + LP Master resolved by name server-side, row values authoritative, LP Master profile fills only blank fields (legacy 7-field rows keep the old merge), classifications normalized; existing (facility, investor) pairs skipped, never overwritten — `lp_records` intentionally has no unique constraint on that pair (multi-sleeve), so idempotency is application-level |
| `PATCH /api/config/cls-conc-limit-defaults` | cls-conc limits CSV | jsonb-style merge into the defaults map (see above) |

Each returns `{"created": n, "updated": n, "skipped": n}`; unresolvable or blank rows are
counted as skipped rather than failing the batch.

## LP Master ordering & commit

`lp_records.source_seq` (`V1_3__lp_source_seq.sql`) stores each LP's position in its
originating Agent BB — the extraction row index. The LP listing (`GET /api/lpRecords?facilityId=`)
and the Shadow BB run result (`POST /api/bb/run/{facilityId}`) return LPs in this **natural
(source-file) order**, falling back to investor name; rows without a source position
(manually-created or legacy) sort last. Commit (`commitAcceptedMatches`) and the direct
upsert (`LpMasterService.upsertAll`) both populate `source_seq`.

Match-queue entries carry the extraction's own `rowIndex` (the source-sheet row, which
starts below the header), and commit looks each accepted entry's row up by that same index.
These two must use the same index space — otherwise the first *header-offset* accepted rows
find no row at commit and are silently skipped (the cause of a 900-row file inserting 893).

Commit also persists the commitment/concentration figures onto each LP record:
`calledCap` (Called Capital), `pctCapCommit` (% of Capital Commitments) and
`agentExcessConc` (Excess Concentration). Each reads its direct row key first, falling back
to the row's `canonicalFields` map (rows stored before the direct keys existed). These values
may be **platform-derived** rather than agent-reported: when the agent workbook has no such
column, pe-sub-extraction's `DerivedFieldCalculator` computes them from extracted
Commitment / Uncalled / Concentration Limit (see pe-sub-extraction README). Concentration (%)
and Excess Concentration (%) have no `lp_records` column; they live only in the submission's
stored extraction JSON (`canonicalFields`) for the review screens.

**Display precision.** Dollar display strings are full precision with thousands grouping,
never rounded or unit-abbreviated (`$12,345,678.9`, not `$12.3M`); exact values also persist
in the `*_num NUMERIC(20,2)` columns the BB engine reads first. Percent/rate strings carry
**exactly one decimal** (`75.0%`, `5.6%`) — never a bare integer percent and never more than
one decimal. The consolidated schema (`V1_1__schema.sql`) sizes the workbook-derived
`lp_records`/`lp_master`/`submission_extractions` string columns accordingly (free text 255,
money display 64, percents/ratings 50) — extracted values are stored verbatim, never truncated.

**Rank.** `lp_records.lp_rank` is computed **only** by the API (`ShadowBbService.refreshRanks`,
on each Shadow BB run): competition ranking over **every** LP record in the facility —
Excluded / not-included LPs are ranked too — ordered by uncalled capital (desc), name as the
tie-break. The UI displays the persisted rank and never derives its own.

**Troubleshooting record-level persistence failures** (e.g. `value too long for type character
varying(N)`): set `logging.level.com.ubs.pesubapi.service=DEBUG` in lower environments —
`LpMasterService`, `LpIngestService` and `MatchingService` then log each record's full payload
before it is saved, so the failing LP is identifiable from the last logged record. Higher
environments stay at INFO and log nothing per record.

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
| `dev` (default) | Every request is authenticated as the configured dev identity (`app.security.dev-user`, roles `ANALYST,SERVICE` — SERVICE included so header-less service callers like pe-sub-jobs work locally; DEV mode cannot distinguish callers). Keeps local standalone runs and the header-less UI working without a login flow. |
| `gateway` | Identity is taken from the SSO reverse-proxy headers `X-Auth-User` / `X-Auth-Roles`. A request without a valid user header is rejected `401`. Set this in any shared/production environment. |

Roles mirror `pe-sub-docs/RBAC_ROLES.md`: **ANALYST** (day-to-day operator + configurator),
**MANAGER** (Account/Transaction Manager — independent review authority) and **VIEWER** (IT /
read-only; Intra ID App Role `APP_VIEWER`). Authorization highlights:

- Configuration surfaces (`PUT /api/config/**`, `/api/field-mapping/**` mutations, template
  create/update/delete/import on `/api/bb-templates/**`) require `ANALYST`, as does the bank-wide
  LP Master delete (`DELETE /api/lp-master/{id}`). Reading any of these (`GET`) is open to any operator.
- Shadow BB review is **Manager-only**: `POST /api/submissions/{id}/accept` and `/reject` require
  `MANAGER`. A Manager may accept or reject their own submission so the workflow does not lock when
  no second reviewer is available.
- **VIEWER is read-only**: every mutating verb (`POST`/`PUT`/`PATCH`/`DELETE`) under `/api` is denied
  to it (`403`), while `GET`/download (including report exports) is allowed.
- Service-to-service endpoints require the `SERVICE` role and are never reachable by an operator (in gateway mode): `POST /api/lpRecords/ingest`, `POST /api/lpRecords/seed`, `POST /api/facilities/ingest`, `POST /api/lp-master/ingest`, `PATCH /api/config/cls-conc-limit-defaults`.
- Public (no auth): `GET /api/ping`, `GET /health`, `GET /api/notifications/**`, and CORS preflight.

Audit entries record the **authenticated principal** (previously a hardcoded operator name); in
`dev` mode that is `app.security.dev-user`. Role-gated review **is enforced**: `POST /complete`
submits a Shadow BB for review (status `Pending Review`, recording `submitted_by`);
`accept` activates the facility and writes the credit profile back to LP Master (recording
`reviewed_by`); `reject` returns it to an actionable state with a required `review_note`.

To exercise all three roles locally end-to-end, run the API in `gateway` mode
(`app.security.mode=gateway`) and use the pe-sub-ui dev role switcher, which sends the selected
role's `X-Auth-User` / `X-Auth-Roles` headers on every request.

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
