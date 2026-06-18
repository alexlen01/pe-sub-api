# pe-sub-api

Spring Boot 3.5 / Java 21 REST API for the PE Sub Borrowing Base Platform.

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
