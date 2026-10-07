# CCE Protocol Service

The definitional plane of the CCE system. Loads and validates FHIR R4 **PlanDefinition** and
**ActivityDefinition** resources, derives the trigger index the Matcher Service matches against, and
manages their lifecycle.

Sole writer of `protocol_definition`, `action_definition` and `trigger_index`. It
processes no clinical events and has **no Kafka dependency**.

**Port** `8090` · **Java** 21 · **Spring Boot** 3.4.2 · **Version** 2.0.0

## Quick Start

```bash
# Shared infrastructure (PostgreSQL — from the collector service)
cd ../cce-collector-service && docker compose up -d postgres

# Build and run — Flyway applies the schema on startup
./gradlew build
./gradlew bootRun

# Health check
curl -s localhost:8090/actuator/health
```

Deploy this service **first**: the Matcher Service's migration declares foreign keys into
`protocol_definition`.

## Documentation

| Document | Contents |
|---|---|
| [Architecture & Design](docs/architecture-overview.md) | Responsibility, load pipeline, trigger index, lifecycle, cache invalidation contract, observability |
| [API Reference](docs/api-reference.md) | Every endpoint with request and response shapes and the status codes that carry meaning |
| [Developer Setup](docs/developer-setup.md) | Prerequisites, configuration, project layout, testing, and how to change the load pipeline safely |
| [Deployment Guide](docs/deployment-guide.md) | Docker and Kubernetes, database setup, health checks, backup, troubleshooting |

System-wide context lives in **cce-common-util** and is not restated here:

| For | See |
|---|---|
| Why the services are split, and how they coordinate | `cce-common-util` → [docs/architecture-overview.md](https://github.com/openphc/cce-common-util/blob/release-2.0.0/docs/architecture-overview.md) |
| Schema, columns, enums, table ownership | `cce-common-util` → [docs/data-dictionary.md](https://github.com/openphc/cce-common-util/blob/release-2.0.0/docs/data-dictionary.md) |
| `relatedAction` direction, status vocabularies, triggers, timing units | `cce-common-util` → [docs/fhir-conformance.md](https://github.com/openphc/cce-common-util/blob/release-2.0.0/docs/fhir-conformance.md) |
| The shared entities, parser and exception handler this service uses | `cce-common-util` → [docs/library-reference.md](https://github.com/openphc/cce-common-util/blob/release-2.0.0/docs/library-reference.md) |

Cross-repository links go to the `openphc` repositories on GitHub, on `release-2.0.0`, so they work
on GitHub as well as locally. The Gradle composite build does assume the repositories are checked
out as siblings.

## API

```
POST   /v1/protocol/protocol-definitions            load a PlanDefinition
GET    /v1/protocol/protocol-definitions            list (paged)
GET    /v1/protocol/protocol-definitions/{id}
GET    /v1/protocol/protocol-definitions/by-url?url=
GET    /v1/protocol/protocol-definitions/by-url-version?url=&version=
POST   /v1/protocol/protocol-definitions/{id}/retire
POST   /v1/protocol/protocol-definitions/{id}/rebuild-index
DELETE /v1/protocol/protocol-definitions/{id}

POST   /v1/protocol/action-definitions              create from an ActivityDefinition
GET    /v1/protocol/action-definitions?status=
GET    /v1/protocol/action-definitions/{id}
PUT    /v1/protocol/action-definitions/{id}
POST   /v1/protocol/action-definitions/{id}/retire
DELETE /v1/protocol/action-definitions/{id}
```

Details in the [API Reference](docs/api-reference.md).

## Testing

```bash
./gradlew test              # 90 unit tests
./gradlew build             # tests + coverage gate (0.98 instruction coverage)
./gradlew jacocoTestReport  # build/reports/jacoco/test/html/index.html
```
