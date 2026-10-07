# API Reference — Protocol Service

Base URL: `http://<host>:8090`
Content type: `application/json` on request and response.

All error bodies and their status codes come from the shared `GlobalExceptionHandler` — see
[Library Reference §5](https://github.com/openphc/cce-common-util/blob/release-2.0.0/docs/library-reference.md#5-exception) for the mapping and the
body shape. Only status codes that carry service-specific meaning are called out below.

No authentication is enforced at the application layer; see
[Architecture §9](architecture-overview.md#9-security).

---

## Protocol definitions

`/v1/protocol/protocol-definitions`

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/` | Load a PlanDefinition |
| `GET` | `/` | List, paged |
| `GET` | `/{id}` | Fetch by id |
| `GET` | `/by-url?url=` | All versions of one canonical URL |
| `GET` | `/by-url-version?url=&version=` | One exact version |
| `POST` | `/{id}/retire` | Retire |
| `POST` | `/{id}/rebuild-index` | Rebuild the trigger index |
| `DELETE` | `/{id}` | Delete |

### `POST /v1/protocol/protocol-definitions`

Loads, validates and indexes a protocol. The FHIR resource is passed as a **JSON string** in a
wrapper field, not as the request body itself — the body is the envelope, the string is the resource.

```json
{ "planDefinitionJson": "{\"resourceType\":\"PlanDefinition\",\"url\":\"...\",\"version\":\"1.0.0\",\"action\":[...]}" }
```

`201 Created` with the stored definition:

```json
{
  "id": "018f2c1a-...",
  "url": "http://openphc.org/fhir/PlanDefinition/anc-high-risk",
  "version": "1.0.0",
  "canonical": "http://openphc.org/fhir/PlanDefinition/anc-high-risk|1.0.0",
  "status": "ACTIVE",
  "loadedAt": "2026-08-18T09:15:00Z",
  "definition": { "resourceType": "PlanDefinition", "...": "..." }
}
```

| Status | Cause |
|---|---|
| `400` | `planDefinitionJson` blank; `(url, version)` already loaded; any validation rejection from [Architecture §3](architecture-overview.md#3-load-pipeline) |
| `422` | Not parseable as a FHIR resource — including plain malformed JSON |

Note the `422` covers malformed JSON here, unlike the action-definition endpoints below: the FHIR
parser reads the string first, so a syntax error surfaces as a FHIR `DataFormatException` rather than
reaching a Jackson read.

`url` and `version` are read from the resource, not from the request — the definition names itself.

Validation warnings (`concurrent-*` and dangling `relatedAction`s) do **not** affect the response.
They are logged; the load succeeds.

### `GET /v1/protocol/protocol-definitions`

Standard Spring `Pageable` — `?page=0&size=20&sort=loadedAt,desc`. Returns a `Page` envelope
(`content`, `totalElements`, `totalPages`, `number`, `size`).

### `GET /v1/protocol/protocol-definitions/by-url`

Returns a plain **array**, not a page — all versions sharing one canonical URL, which is how you find
what an old instance was enrolled against. Empty array if the URL is unknown; this is not a `404`.

### `GET /v1/protocol/protocol-definitions/by-url-version`

Both parameters required. `404` if no such version exists.

### `POST /v1/protocol/protocol-definitions/{id}/retire`

Sets `status = RETIRED` and deletes the definition's trigger index rows, so it stops matching. Returns
the updated definition.

| Status | Cause |
|---|---|
| `404` | No such definition |
| `409` | Already retired |

Existing `protocol_instance` rows are untouched — patients already enrolled continue on the protocol
they enrolled against. Retirement stops *new* enrolments, and takes effect for matching within the
Matcher Service's refresh interval, not immediately
([Architecture §6](architecture-overview.md#6-cache-invalidation-contract)).

### `POST /v1/protocol/protocol-definitions/{id}/rebuild-index`

Re-derives the trigger index from the stored definition. `200` with an empty body. Idempotent.

Note it rebuilds from what is **stored**, not from a resubmitted resource — so it corrects stale
derived data, not a wrong definition.

### `DELETE /v1/protocol/protocol-definitions/{id}`

`204 No Content` on success.

| Status | Cause |
|---|---|
| `404` | No such definition |
| `409` | Patients are enrolled — enforced by the foreign key, not a pre-check ([Architecture §5](architecture-overview.md#deleting-an-enrolled-definition)) |

Retire, do not delete, for anything that has been in clinical use. Deleting discards the definition
a past journey was measured against.

---

## Action definitions

`/v1/protocol/action-definitions`

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/` | Create from an ActivityDefinition |
| `GET` | `/?status=` | List, paged, optional status filter |
| `GET` | `/{id}` | Fetch by id |
| `PUT` | `/{id}` | Replace the definition |
| `POST` | `/{id}/retire` | Retire |
| `DELETE` | `/{id}` | Delete |

### `POST` and `PUT`

Same envelope shape as protocol load, with the field named `definitionJson`:

```json
{ "definitionJson": "{\"resourceType\":\"ActivityDefinition\",\"url\":\"...\",\"version\":\"1.0\",\"kind\":\"CommunicationRequest\"}" }
```

`201` on create, `200` on update. The response carries the fields extracted from the resource:

```json
{
  "id": "018f2c1a-...",
  "canonicalUrl": "http://openphc.org/fhir/ActivityDefinition/escalation-alert",
  "version": "1.0",
  "canonical": "http://openphc.org/fhir/ActivityDefinition/escalation-alert|1.0",
  "name": "escalation-alert",
  "title": "Escalation Alert",
  "status": "ACTIVE",
  "actionType": "CommunicationRequest",
  "definition": { "resourceType": "ActivityDefinition", "...": "..." },
  "createdAt": "2026-08-18T09:15:00Z",
  "updatedAt": "2026-08-18T09:15:00Z"
}
```

`canonicalUrl`, `version`, `name`, `title` and `actionType` are extracted from the resource; `kind`
must be one of the supported `ActionDefinitionKind` values. Severity and intelligence destination are
read from FHIR extensions when present and left null when absent.

| Status | Cause |
|---|---|
| `400` | `definitionJson` blank or malformed; missing `url` or `kind`; unsupported `kind`; `(canonicalUrl, version)` already exists |
| `404` | (`PUT`) no such definition |

`PUT` to the same `(url, version)` it already has is allowed — that is how you correct a definition's
body. Changing it to a pair another row holds is a `400`.

### `GET /v1/protocol/action-definitions`

`?status=ACTIVE` or `?status=RETIRED` filters; omitted returns all. An unrecognized value is a `400`
rather than being ignored.

### `POST /{id}/retire`, `DELETE /{id}`

`200` and `204` respectively. `409` if already retired. Delete does not consult
`intelligence_event_log` — that table belongs to another service.

A retired action definition remains resolvable by canonical for rows that already reference it; it
stops being counted as available capacity
([Architecture §7](architecture-overview.md#7-observability)).

---

## Operational endpoints

| Path | Purpose |
|---|---|
| `/actuator/health` | Liveness and readiness probes |
| `/actuator/info` | Build info |
| `/actuator/metrics` | Micrometer metrics |
| `/actuator/prometheus` | Prometheus scrape |

Health detail is shown only when authorized. Service metrics are in
[Architecture §7](architecture-overview.md#7-observability).
