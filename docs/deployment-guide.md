# Deployment Guide — Protocol Service

Deploy **first**. The Matcher Service's migration declares foreign keys into `protocol_definition`,
and the Step SLA Service validates its mapping against tables this service creates. Full ordering
rationale: [Architecture Overview §6](https://github.com/openphc/cce-common-util/blob/release-2.0.0/docs/architecture-overview.md#6-deployment-order).

## Requirements

| Component | Requirement |
|---|---|
| JRE | 21 |
| PostgreSQL | 16, database `ccedb`, with DDL rights for this service's user |
| Kafka | **not required** |
| Memory | 512 MB heap is ample — no event throughput, no large caches |

Sizing note: this service handles protocol loads, not clinical traffic. One replica is normally
enough, and horizontal scaling buys availability rather than throughput.

## Environment variables

| Variable | Default | Required | Notes |
|---|---|---|---|
| `SERVER_PORT` | `8090` | No | The Docker image pins `8080` internally |
| `DB_HOST` | `localhost` | **Yes** in production | |
| `DB_PORT` | `5432` | No | `5433` for the collector's shared instance |
| `DB_NAME` | `ccedb` | No | |
| `DB_USERNAME` | `cce_user` | **Yes** | Needs DDL rights — it runs Flyway |
| `DB_PASSWORD` | `cce_pass` | **Yes** | Never leave at the default |
| `DB_POOL_SIZE` | `20` | No | Can be reduced well below this |
| `DB_POOL_MIN_IDLE` | `5` | No | HikariCP minimum idle connections |
| `DB_CONNECTION_TIMEOUT` | `30000` | No | ms |
| `DB_IDLE_TIMEOUT` | `600000` | No | ms — HikariCP idle connection timeout |
| `DB_MAX_LIFETIME` | `1800000` | No | ms — HikariCP maximum connection lifetime |
| `CCE_FLYWAY_BASELINE_VERSION` | `0` | No | `0` for a new database (V1 creates the schema, V2 is a no-op). Set to `1` for the **one-time** deployment against a database that still holds the 1.x pre-split schema, so Flyway records V1 as applied and runs only V2. Return it to `0` afterwards |

## Docker

**Build from the workspace directory, not from this repository.** This service depends on
`cce-common-util` as a Gradle composite build, and Docker's `COPY` cannot reach outside its build
context — so the context has to be the directory that holds both repositories:

```bash
cd ..            # the directory containing cce-protocol-service and cce-common-util
docker build -f cce-protocol-service/Dockerfile -t cce-protocol-service:2.0.0 .
```

Building with this repository as the context fails in stage 1 with
`Included build '/.../cce-common-util' does not exist`.

```bash
docker run -d --name cce-protocol-service \
  -p 8090:8080 \
  -e DB_HOST=postgres-host -e DB_PORT=5433 \
  -e DB_USERNAME=cce_user -e DB_PASSWORD='<secret>' \
  cce-protocol-service:2.0.0
```

The image sets `SERVER_PORT=8080` to match its `EXPOSE` and healthcheck; the application's own default
outside Docker is `8090`.

If you would rather build from this repository alone, publish `cce-common-util` to a Maven repository
and replace the `includeBuild` line in `settings.gradle` with a versioned dependency. That trades the
composite build's immediate pickup of library changes for an independent image build.

## Kubernetes

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: cce-protocol-service
spec:
  replicas: 1
  selector:
    matchLabels: { app: cce-protocol-service }
  template:
    metadata:
      labels: { app: cce-protocol-service }
    spec:
      containers:
        - name: cce-protocol-service
          image: cce-protocol-service:2.0.0
          ports: [{ containerPort: 8080 }]
          env:
            - name: DB_HOST
              value: postgres.cce.svc.cluster.local
            - name: DB_PORT
              value: "5432"
            - { name: DB_USERNAME, valueFrom: { secretKeyRef: { name: cce-db, key: username } } }
            - { name: DB_PASSWORD, valueFrom: { secretKeyRef: { name: cce-db, key: password } } }
          readinessProbe:
            httpGet: { path: /actuator/health/readiness, port: 8080 }
            initialDelaySeconds: 20
          livenessProbe:
            httpGet: { path: /actuator/health/liveness, port: 8080 }
            initialDelaySeconds: 40
          resources:
            requests: { memory: 512Mi, cpu: 200m }
            limits:   { memory: 1Gi,  cpu: "1" }
```

Keep `replicas` low and **roll rather than run parallel** on upgrade: Flyway takes a lock, so
concurrent starts serialize rather than conflict, but there is no throughput reason for more than one
instance.

## Database setup

Flyway applies `V1__initial_schema.sql` on startup, creating `protocol_definition`,
`action_definition` and `trigger_index` — the three tables this service owns. History is
tracked in `flyway_schema_history_protocol`.

```sql
-- confirm what was applied
SELECT version, description, success, installed_on
FROM flyway_schema_history_protocol ORDER BY installed_rank;
```

### Existing databases: no upgrade path yet

`baseline-on-migrate` is enabled, but `baseline-version` is `0` — so on a database that already has
these tables, Flyway baselines at version 0 and then **still tries to apply V1**, which fails with
`relation "protocol_definition" already exists`. Verified against PostgreSQL 16; the service does not
start.

V1 is a greenfield migration. Pointing this service at a `ccedb` carried over from the monolithic
deployment therefore needs deliberate work, not just a setting:

- Setting `baseline-version: 1` would skip V1, but only records that V1 *was* applied — it does not
  check the columns. The monolith's schema does **not** match V1 (`step_instance` still has `state`,
  `completion_status` and `overdue_date`, has no `step_status`, and there is no
  `step_sla_state_transition` table at all), so baselining alone leaves a schema the services cannot
  run against.
- A real upgrade needs forward migrations that rename `state` to `sla_status`, add `step_status`, drop
  the removed columns, create `step_sla_state_transition`, and backfill it for in-flight steps.

Until those exist, treat this release as greenfield-only and confirm the target `ccedb` is empty.

The service user needs DDL rights on these three tables. It needs **no** rights on the Matcher
Service's tables; if it has them, that is a wider grant than the design requires.

## Health and monitoring

| Endpoint | Use |
|---|---|
| `/actuator/health/readiness` | Route traffic — fails while the database is unreachable |
| `/actuator/health/liveness` | Restart decisions |
| `/actuator/prometheus` | Scrape target |

Service metrics — `cce.protocol.definitions.active` and `cce.action.definitions.active` — are described
in [Architecture §7](architecture-overview.md#7-observability). Both are useful as alerts on an
*unexpected drop*: definitions do not normally disappear, so a fall means a retire or delete happened.

## Backup

The definitional tables are small and change rarely, which makes them cheap to back up and expensive
to lose — they are the specification every patient journey is measured against.

```bash
pg_dump -U cce_user -h postgres-host -p 5433 -d ccedb \
  -t protocol_definition -t action_definition -t trigger_index \
  > protocol_defs_$(date +%Y%m%d).sql
```

`trigger_index` is derivable from `protocol_definition` via `POST /{id}/rebuild-index`, so a restore
that loses it is recoverable. The definitions themselves are not derivable from anything.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Startup fails on Flyway checksum mismatch | `V1` was edited after being applied — never edit an applied migration; add a new one |
| Startup fails: relation already exists | The target `ccedb` is not empty. V1 is greenfield-only — see [Existing databases](#existing-databases-no-upgrade-path-yet) |
| `POST` returns `400` "already exists" | That `(url, version)` is loaded — publish a new version rather than overwriting |
| `DELETE` returns `409` | Patients are enrolled; retire instead ([API Reference](api-reference.md#delete-v1protocolprotocol-definitionsid)) |
| A newly loaded protocol is not matching events | Expected within the Matcher Service's refresh interval ([Architecture §6](architecture-overview.md#6-cache-invalidation-contract)) |
| `active` gauge dropped unexpectedly | Something was retired or deleted — check the service logs for `PROTOCOL_RETIRED` |
