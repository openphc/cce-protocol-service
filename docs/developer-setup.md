# Developer Setup — Protocol Service

## Prerequisites

| Requirement | Notes |
|---|---|
| JDK 21 | Gradle toolchain |
| PostgreSQL 16 | shared `ccedb`; this service runs its own Flyway migration against it |
| `cce-common-util` | checked out as a sibling directory — wired in as a composite build |

**No Kafka.** This service neither produces nor consumes; it has no broker dependency at all.

## Quick start

```bash
# 1. Shared infrastructure (PostgreSQL — from the collector service)
cd ../cce-collector-service && docker compose up -d postgres

# 2. Build
./gradlew build

# 3. Run — Flyway applies V1 on startup
./gradlew bootRun

# 4. Verify
curl -s localhost:8090/actuator/health
```

This service is **first** in the deployment order, so it can be brought up against an empty `ccedb`.
The other two cannot. See
[Data Dictionary §3](https://github.com/openphc/cce-common-util/blob/release-2.0.0/docs/data-dictionary.md#3-ownership).

## Configuration

| Variable | Default | Notes |
|---|---|---|
| `SERVER_PORT` | `8090` | |
| `DB_HOST` | `localhost` | |
| `DB_PORT` | `5432` | set to `5433` for the collector's shared instance |
| `DB_NAME` | `ccedb` | |
| `DB_USERNAME` / `DB_PASSWORD` | `cce_user` / `cce_pass` | |
| `DB_POOL_SIZE` | `20` | Hikari maximum |
| `DB_POOL_MIN_IDLE` | `5` | |

`ddl-auto: none` — the schema comes from Flyway, never from Hibernate. Migrations are tracked in
`flyway_schema_history_protocol`, so this ledger never sees the Matcher Service's migrations.

Some `cce.*` properties may appear live in this service without being used by it: the shared library
declares beans that read them, and the component scan is widened to `org.openphc.cce`. See
[Library Reference §7](https://github.com/openphc/cce-common-util/blob/release-2.0.0/docs/library-reference.md#what-a-consumer-gets-whether-it-asks-or-not).

## Project layout

```
org.openphc.cce.protocol
├── web/controller/   ProtocolDefinitionController, ActionDefinitionController
├── web/dto/          request envelopes and response DTOs
├── web/DtoMapper     entity → DTO
├── service/          ProtocolDefinitionService (load/retire/rebuild/delete)
│                     ActionDefinitionService (CRUD)
├── domain/repository ProtocolDefinitionRepository, TriggerIndexRepository
└── config/           ObservabilityConfig
```

Entities, the FHIR parser and the exception handler come from `cce-common-util` — this service adds
the write side and the HTTP surface. `ProtocolDefinitionRepository` and `TriggerIndexRepository` are
declared here rather than in the library because this is the only service that writes them.

## Testing

```bash
./gradlew test                    # unit tests
./gradlew build                   # tests + coverage gate
./gradlew jacocoTestReport        # build/reports/jacoco/test/html/index.html
```

90 unit tests. No integration-test source set — there is nothing to integrate against beyond the
database, and the write pipeline is exercised through mocked repositories.

The coverage gate is **0.98** instruction coverage, excluding `ProtocolServiceApplication`. It sits
just under the measured level so an uncovered addition fails the build.

Controller tests use `@WebMvcTest` with an explicit `@ContextConfiguration` naming only the
controller, `DtoMapper` and `GlobalExceptionHandler` — that keeps JPA out of the context, which
matters because the application class carries `@EnableJpaRepositories`.

## Working on the load pipeline

Changing validation means changing what is rejected at load, and definitions already stored were
accepted under the old rules. A new **rejection** is effectively a breaking change for any publisher
whose definitions are already in the database — `POST` will start failing for content that used to
work, and `rebuild-index` will fail for stored definitions.

Prefer a warning (as `concurrent-*` and dangling `relatedAction`s are handled) unless the definition
genuinely cannot function. The rejection/warning split and its reasoning are in
[Architecture §3](architecture-overview.md#rejections-versus-warnings).

## Changing the trigger index shape

`trigger_index` is derived data with a composite primary key that *is* the lookup tuple. If you change
what `buildTriggerIndexEntries` produces, stored definitions become stale rather than wrong — and
`POST /{id}/rebuild-index` exists precisely so they can be corrected without re-loading by hand.

The consuming query lives in the Matcher Service and matches on the whole tuple, so a change here is
a change to that service's hot path. Coordinate the two.
