# Field Service Dispatch

[한국어](README.ko.md) | English

This Spring Boot reference application demonstrates a synthetic-only Field
Service dispatcher. It keeps visits, workers, planning proposals, and committed
routes in PostgreSQL while a deterministic fixture supplies the planning
result. The default path does not contact Timefold, a map provider, or any
other external service.

## Scope and boundary

- Synthetic visits contain only a visit type, required skill, time window,
  service duration, priority, and synthetic coordinates.
- The planner applies required skill, worker availability, time-window, travel
  matrix, and started/pinned-visit constraints with deterministic tie-breaks.
- Approval changes proposal state only. Worker-route confirmation rechecks the
  current worker and visit versions and commits the complete route atomically.
- Callback and replay fixtures are local contract tests; they are not evidence
  of a live Timefold tenant or production route quality.
- The example does not model patient records, diagnosis, insurance, clinical
  advice, production credentials, or a production map provider.

## API walkthrough

Start the application with the default `demo` profile. It binds to loopback
and exposes the static console at `/field-service`.

```bash
./gradlew :optimization-field-service-dispatch:bootRun
curl -s http://127.0.0.1:8080/field-service
```

The mutation endpoints require the demo operator header and a bounded
idempotency key. The following synthetic flow creates a visit, requests a
replan, reads and approves the proposal, and confirms one worker route:

```bash
curl -s -X POST http://127.0.0.1:8080/api/field-service/visits \
  -H 'Content-Type: application/json' \
  -H 'X-Demo-Operator: true' \
  -H 'Idempotency-Key: visit-001' \
  -d '{"visitId":"visit-001","coordinateId":"coord-001","requiredSkill":"INSTALL","windowStart":"2026-08-20T09:00:00Z","windowEnd":"2026-08-20T12:00:00Z","serviceDurationSeconds":1800}'

curl -s -X POST http://127.0.0.1:8080/api/field-service/plans/replan \
  -H 'X-Demo-Operator: true' \
  -H 'Idempotency-Key: replan-001' \
  -H 'Content-Type: application/json' \
  -d '{"planId":"field-service","datasetId":"demo-dataset"}'

curl -s http://127.0.0.1:8080/api/field-service/plans/<revision>

curl -s 'http://127.0.0.1:8080/api/field-service/plans?planId=field-service&limit=20'

curl -s -X POST 'http://127.0.0.1:8080/api/field-service/plans/<revision>/approve?planId=field-service' \
  -H 'X-Demo-Operator: true' \
  -H 'Idempotency-Key: approve-001'

curl -s -X POST 'http://127.0.0.1:8080/api/field-service/dispatch/workers/<worker-id>/confirm?planId=field-service&revision=<revision>' \
  -H 'X-Demo-Operator: true' \
  -H 'Idempotency-Key: confirm-001'
```

Use the list endpoints to inspect the redacted read model:

```bash
curl -s http://127.0.0.1:8080/api/field-service/visits
curl -s http://127.0.0.1:8080/api/field-service/workers
curl -s http://127.0.0.1:8080/actuator/health
curl -s http://127.0.0.1:8080/actuator/prometheus
```

Use a `workerId` returned by the workers endpoint in the confirmation step.
The demo schema is disposable, so seed workers and visits through the fixture
or test setup before running a complete route confirmation flow.

Responses contain synthetic identifiers, numeric scores, and closed reason
codes. They do not expose credentials, raw callback payloads, provider text,
addresses, or internal SQL errors. The demo loopback/operator guard is an
example boundary, not production authentication or CSRF protection. The console
and plans read model show plan revision, approval state, assigned/unassigned
counts, numeric score, constraint reasons, and manual-pin count.

## Verification

Java 25 and Docker are required because the module uses PostgreSQL
Testcontainers. Run the focused module suite and the optimization group smoke
entry point sequentially:

```bash
./gradlew :optimization-field-service-dispatch:cleanTest \
  :optimization-field-service-dispatch:test \
  --no-build-cache --max-workers=1
./scripts/smoke-validate.sh optimization
```

If a stale test result or container setup failure is suspected, repeat the
`cleanTest --no-build-cache` command and inspect the test report before
changing code. The schema is a disposable `SchemaUtils` fixture; this example
does not add production migrations. A local rollback consists of reverting
the module and its README/workflow/smoke registrations together.

This module uses `bluetape4k-dependencies` as the only Bluetape version
authority. It does not import the `planning-contracts` implementation or pin
an individual Bluetape module version.
