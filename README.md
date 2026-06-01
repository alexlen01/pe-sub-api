# pe-sub-api

Spring Boot 3.3 / Java 21 REST API for the PE Sub Borrowing Base Platform.

## Stack

- Java 21 (Eclipse Temurin), Spring Boot 3.3, Maven 3.9
- Spring Data JPA (Hibernate 6), PostgreSQL 16
- Flyway — migrations applied automatically on startup from `src/main/resources/db/migration/`
- Jackson — JSONB serialisation for `bb_snapshots.result`

## Prerequisites

- Java 21
- Maven 3.9+
- Docker (for PostgreSQL)

## Getting started

```bash
# 1. Start PostgreSQL
docker compose up -d

# 2. Start the API (Flyway runs V1_1 + V1_2 on first start)
mvn spring-boot:run
```

API runs at `http://localhost:3001`. Health check: `GET /health`.

## Resetting the local database

Required after migration file changes (e.g. renaming or replacing migration files):

```bash
docker compose down -v   # drop the volume — all data is lost
docker compose up -d     # fresh PostgreSQL
mvn spring-boot:run      # Flyway applies V1_1 + V1_2 on first start
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

Copy `.env.example` and adjust as needed. For production, inject these via Azure Key Vault or Container App environment config.

## Logs

Logs are written to `C:/Users/alexl/apps/pe-sub/logs/pe-sub-api.log` with daily rolling (30-day retention, 10 MB per file).

## Project structure

```
src/main/java/com/ubs/pesubapi/
  config/       CORS and web configuration
  controller/   AuditController, BbController, ConfigController, FacilityController,
                HealthController, LpController, MatchingController,
                NotificationController, ReportController, SubmissionController
  dto/          Java records: BbResult, BbSummary, BbBreach, ComputedLp
  entity/       JPA entities: Facility, Lp, BbSnapshot, ConfigEntry, AuditLog, Submission
  entity/converter/  BbResultConverter (PGobject), JsonNodeConverter (PGobject)
  repository/   Spring Data JPA repositories
  service/      AuditLogService, BbCalculationService, ConfigService,
                MatchingService, NotificationService
src/main/resources/
  application.yml
  db/migration/V1_1__schema.sql   -- all DDL (tables + indexes)
  db/migration/V1_2__seed.sql     -- config reference data
```

## API

Full OpenAPI spec: `pe-sub-docs/openapi.yaml`.  
Solution design: `pe-sub-docs/SOLUTION_DESIGN.md`.
