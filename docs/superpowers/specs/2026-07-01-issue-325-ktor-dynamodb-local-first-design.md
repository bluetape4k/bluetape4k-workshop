# Issue #325 - Ktor DynamoDB Local-First Workshop Spec

- Date: 2026-07-01
- Issue: https://github.com/bluetape4k/bluetape4k-workshop/issues/325
- Work type: Type A Full Feature
- Target repository: `bluetape4k/bluetape4k-workshop`
- Target module: `aws/ktor-dynamodb`
- Gradle project: `:aws-ktor-dynamodb`

## Problem

`bluetape4k-aws 0.4.0` added Ktor DynamoDB integration, but
`bluetape4k-workshop` still has no learner-facing example that combines Ktor,
AWS SDK for Kotlin DynamoDB, and a local AWS-compatible emulator. Existing
workshop modules cover Ktor REST basics and several AWS S3-oriented examples,
but learners need a small local-first DynamoDB REST application that makes
these boundaries explicit:

- Ktor routes call a coroutine service boundary.
- The service uses `DynamoDbKtorPlugin` and `DynamoDbKtorRepository` for
  table bootstrap, create, and lookup paths.
- Optimistic updates use DynamoDB conditional expressions against a `version`
  attribute.
- Conditional failures, missing items, and validation failures become safe HTTP
  JSON responses.
- Default verification uses local emulators/test doubles only, never real AWS.

## Current Evidence

- Issue #325 is open in milestone `1.3.1`, assigned to `debop`, and requests a
  Ktor DynamoDB local-first workshop example.
- Epic #321 requires every child issue to use only the root
  `bluetape4k-dependencies` BOM, keep local deterministic verification as the
  default path, and update both `README.md` and `README.ko.md`.
- `settings.gradle.kts` auto-registers `aws/*` directories, so
  `aws/ktor-dynamodb` becomes `:aws-ktor-dynamodb` without a manual include.
- `gradle/libs.versions.toml` already imports `bluetape4k-dependencies 1.3.1`,
  but currently exposes only `bluetape4k-aws-spring-boot` under the
  `bluetape4k-aws` alias. The new module needs explicit aliases for
  `bluetape4k-aws-ktor` and `bluetape4k-aws-kotlin` without local versions,
  because their versions are governed by the root BOM. It also needs
  `aws.sdk.kotlin:dynamodb` with `version.ref = "aws-kotlin"`, because
  `bluetape4k-dependencies` exposes the AWS Kotlin version but not a DynamoDB
  service alias. `software.amazon.awssdk:auth` should be added only if the
  implementation needs Java SDK v2 auth helpers outside the AWS Kotlin path.
  Ktor JSON support should use `ktor-serialization-kotlinx-json`, matching
  existing workshop Ktor modules.
- Ktor official documentation uses `testApplication {}` and a configured test
  client with `ContentNegotiation` for isolated server tests.
- AWS SDK for Kotlin official documentation supports local endpoint overrides
  through client configuration and shows `DynamoDbClient.fromEnvironment` as
  the normal real-AWS creation path.
- `bluetape4k-aws` already provides:
  - `DynamoDbKtorPlugin` with `autoCreateTables` and application lifecycle
    startup/shutdown hooks,
  - `DynamoDbKtorPluginConfig.endpointUrl`, `region`, and
    `credentialsProvider` for local emulators,
  - `DynamoDbKtorRuntime.repository(...)`,
  - `DynamoDbKtorRepository.save`, `put`, `findById`, `scan`, and `query`,
  - DynamoDB mapper helpers such as `DynamoItemMapper`,
    `DynamoItemReader`, `partitionKeyOf`, and `stringAttrDefinitionOf`.
- `bluetape4k-aws` examples use `FlociServer.Launcher.floci` by default and
  `LocalStackServer.Launcher.getLocalStack("dynamodb")` as an opt-in emulator.
- `Examples.yml` already separates no-container smoke examples from sequential
  container-backed examples. This module belongs in the container-backed lane.
- `scripts/smoke-validate.sh stale-check` currently expects 94 Gradle projects;
  the new module increases that count to 95.

## Constraints

- Use the root `bluetape4k-dependencies` BOM only. Do not pin bluetape4k module
  versions.
- Use AWS SDK for Kotlin DynamoDB for the DynamoDB path; do not use AWS SDK v2
  Java DynamoDB for this example's core flow.
- Use `bluetape4k-aws-ktor` integration instead of hand-rolling Ktor plugin
  lifecycle or raw client ownership.
- Use `bluetape4k-testcontainers` AWS emulator launchers. Do not instantiate
  raw `GenericContainer`.
- Default tests must run against the local emulator and must not require real
  AWS credentials, AWS account access, or live AWS resources.
- Production/main application code must not start a Testcontainer; tests supply
  emulator endpoint, region, and credentials.
- Real AWS mode must require an explicit opt-in flag such as
  `-Dbluetape4k.aws.mode=real`; the default mode is local emulator only.
- Keep Testcontainers-backed verification serial with `--max-workers=1`, and
  disable module-level JUnit parallel execution.
- Keep the example focused on DynamoDB local-first REST, conditional writes,
  and optimistic updates. Do not add S3/SQS/SNS concerns.
- README work is bilingual: update both `README.md` and `README.ko.md` where
  module lists or instructions change.
- Diagram work must use `$bluetape4k-diagram`, produce SVG and PNG assets, pass
  the current checklist, and record full-size visual inspection evidence.
- New module registration must cover AWS/root README tables, example CI
  coverage, stale-check project counts, and `./gradlew projects`.

## Goals

1. Add `aws/ktor-dynamodb` as a local-first Ktor REST application backed by
   DynamoDB.
2. Demonstrate `DynamoDbKtorPlugin` table bootstrap against a local emulator.
3. Expose a small `OrderSession` resource with create, read, bounded paginated
   list, optimistic update, and delete operations.
4. Use conditional create with `attribute_not_exists(#id)`.
5. Use optimistic update with `attribute_exists(#id) AND #version = :expected`.
6. Map duplicate create and stale-version update failures to `409 Conflict`.
7. Map missing item lookup/update/delete to `404 Not Found`.
8. Map validation failures to `400 Bad Request` without leaking emulator
   endpoints or credentials.
9. Cover create, update, duplicate conditional failure, stale-version
   conditional failure, lookup miss, delete, bounded list, pagination,
   concurrent stale updates, malformed JSON, readiness, and JSON serialization
   in tests.
10. Document local run/test commands, emulator selection, and real AWS opt-in
    notes in both README locales.
11. Add architecture and sequence diagrams that explain the Ktor route,
    service, DynamoDB plugin/runtime, repository, and emulator/table layers.
12. Register the module in CI/container validation without making normal smoke
    tests require Docker.

## Non-Goals

- Do not call real AWS in default tests or default run instructions.
- Do not add IAM policy management, production table migrations, global
  secondary indexes, streams, transactions, or enhanced-client mappers.
- Do not replace the existing `bluetape4k-aws` library example; this workshop
  is a learner-facing consumer example with more documentation and diagrams.
- Do not introduce Spring Boot, Exposed, R2DBC, Kafka, Redis, or S3 into this
  module.
- Do not create a new shared abstraction layer unless implementation shows
  meaningful duplication inside this module.
- Do not restore hard Kover thresholds or change unrelated CI policy.

## Approach Options

### Option A - `aws/ktor-dynamodb` with DynamoDB Ktor plugin

Create a new AWS workshop module that installs `DynamoDbKtorPlugin`, registers
one pay-per-request DynamoDB table, and wires Ktor routes to an
`OrderSessionService`. Tests use Floci by default and LocalStack when
`-Dbluetape4k.aws.emulator=localstack` is supplied.

Benefits:

- Matches the issue scope directly.
- Reuses the published bluetape4k Ktor DynamoDB integration.
- Keeps the default path local and deterministic.
- Teaches service/repository separation and DynamoDB conditional expressions.
- Fits the existing AWS workshop module group.

Costs:

- Docker/emulator-backed tests are slower than in-memory tests.
- CI must keep the module in the sequential container lane.

### Option B - Extend `bluetape4k-aws` example docs only

Reference the existing `bluetape4k-aws/examples/aws-ktor-dynamodb-examples`
module from the workshop README without adding a workshop module.

Benefits:

- Smallest code change.

Costs:

- Fails the workshop acceptance criteria for local workshop tests,
  learner-facing README parity, and diagrams.
- Does not exercise the consumer dependency catalog in this repo.

### Option C - Ktor DynamoDB test double only

Build the Ktor routes against an in-memory fake repository and document DynamoDB
as future work.

Benefits:

- Fastest default tests.

Costs:

- Fails the issue requirement to bootstrap a DynamoDB table against a local
  emulator.
- Does not prove conditional expression behavior.

## Decision

Use Option A. The new module will be a focused Ktor + DynamoDB workshop sample
under `aws/ktor-dynamodb`, backed by local AWS-compatible emulator tests and
documented with bilingual README files plus checklist-verified diagrams.

## Architecture

### Runtime Components

- `KtorDynamoDbApplication`: Ktor entrypoint and environment-property bridge
  for manual local runs.
- `DynamoDbLocalConfig`: endpoint, region, credentials, and table name options
  supplied by tests or manual real-AWS opt-in.
- `DynamoDbKtorPlugin`: creates or receives the AWS Kotlin SDK
  `DynamoDbClient`, auto-creates the registered table, and closes plugin-owned
  clients during application shutdown.
- `OrderSessionRoutes`: Ktor route module for HTTP request/response handling.
- `OrderSessionService`: coroutine service boundary for validation,
  duplicate create, optimistic update, bounded list, conditional failure
  disambiguation, and error mapping.
- `OrderSessionDynamoRepository`: thin DynamoDB adapter using
  `DynamoDbKtorRepository` for create/lookup/list and direct DynamoDB SDK
  calls for conditional optimistic updates and conditional deletes.
- `OrderSession`, request DTOs, response DTOs, and error DTOs:
  `@Serializable`, `java.io.Serializable`, `serialVersionUID`-bearing
  learner-facing models.
- Local AWS emulator: Floci by default, LocalStack opt-in for parity checks.

### Data Model

`OrderSession` uses a single-table primary key:

| attribute | type | purpose |
| --- | --- | --- |
| `id` | string partition key | Stable order/session id supplied by caller. |
| `customerId` | string | Demonstrates ordinary scalar attributes. |
| `status` | string enum-like value | Small lifecycle state: `CREATED`, `APPROVED`, `CANCELLED`. |
| `notes` | string | Optional learner-visible detail. |
| `version` | number | Optimistic concurrency token, incremented on update. |

The example intentionally avoids sort keys and secondary indexes so learners
can focus on conditional writes before advanced DynamoDB modeling.

### Data Flow

1. A learner sends a JSON request to Ktor.
2. Ktor deserializes through `ContentNegotiation`.
3. The route delegates to `OrderSessionService`.
4. The service validates semantic fields and calls the repository.
5. The repository uses `DynamoDbKtorPlugin` runtime to access the table.
6. Create uses `PutItem` with `attribute_not_exists(#id)`.
7. Read uses the repository facade.
8. List uses a bounded paginated `Scan` with a default limit, maximum limit,
   and opaque `nextToken`; README must warn that scan is a workshop-only
   access pattern, not a production query design.
9. Update success is one `UpdateItem` command with
   `attribute_exists(#id) AND #version = :expected`. If the conditional update
   fails, the service performs a fallback `GetItem` only on that failure path:
   absent item maps to `404`, present item with a different version maps to
   `409`.
10. Delete uses conditional `DeleteItem` with `attribute_exists(#id)`. A
   conditional delete failure maps to `404`.
11. Validation failures become `BadRequest`.
12. `StatusPages` serializes safe JSON error responses.

### DynamoDB Command Budget

| operation | command budget | notes |
| --- | --- | --- |
| create success | 1 `PutItem` | Uses `attribute_not_exists(#id)`. |
| duplicate create | 1 failed `PutItem` | Maps directly to `409`. |
| read success/miss | 1 `GetItem` | Miss maps to `404`. |
| list | 1 bounded `Scan` per page | Default limit 25, maximum limit 100, opaque continuation token. |
| update success | 1 `UpdateItem` | No read-before-write on the success path. |
| update failure | <= 1 failed `UpdateItem` + 1 `GetItem` | Disambiguates missing `404` vs stale `409`. |
| delete success | 1 conditional `DeleteItem` | Uses `attribute_exists(#id)`. |
| delete miss | 1 failed conditional `DeleteItem` | Maps to `404`. |
| readiness | 0 commands per normal probe | Uses startup-captured state; optional refresh may issue at most 1 `DescribeTable` per 30 seconds. |

Domain-level code must not retry validation failures or
`ConditionalCheckFailedException`. Local-test AWS SDK retry behavior must be
objectively bounded: maximum 2 attempts, total call deadline 5 seconds, and
backoff cap 500 milliseconds.

### HTTP DTOs

| DTO | fields |
| --- | --- |
| `CreateOrderSessionRequest` | `id: String`, `customerId: String`, `status: OrderSessionStatus = CREATED`, `notes: String = ""` |
| `UpdateOrderSessionRequest` | `expectedVersion: Long`, `status: OrderSessionStatus`, `notes: String = ""` |
| `OrderSessionResponse` | `id: String`, `customerId: String`, `status: OrderSessionStatus`, `notes: String`, `version: Long` |
| `OrderSessionListResponse` | `items: List<OrderSessionResponse>`, `nextToken: String?` |
| `ErrorResponse` | `code: String`, `message: String` |
| `ReadinessResponse` | `status: "UP" | "DOWN"`, `mode: "local" | "real"`, `emulator: "floci" | "localstack" | null`, `region: String`, `tableName: String`, `tableReady: Boolean`, `checkedAt: String` as a UTC ISO-8601 instant |

`OrderSessionStatus` is allowlisted to `CREATED`, `APPROVED`, and `CANCELLED`.
Request-controlled strings must never be concatenated into DynamoDB
expressions; all attribute names are fixed aliases and all user values are
bound through `ExpressionAttributeValues`.

### HTTP API

| method | path | behavior |
| --- | --- | --- |
| `POST` | `/dynamodb/order-sessions` | Create an order session at version `1`; duplicate id returns `409`. |
| `GET` | `/dynamodb/order-sessions/{id}` | Return one order session or `404`. |
| `GET` | `/dynamodb/order-sessions?limit=25&nextToken=...` | Return one bounded scan page and an optional continuation token. |
| `PUT` | `/dynamodb/order-sessions/{id}` | Update status/notes when `expectedVersion` matches; stale version returns `409`. |
| `DELETE` | `/dynamodb/order-sessions/{id}` | Conditional delete returns `204 No Content`; missing id returns `404`. |
| `GET` | `/health/readiness` | Return `200 ReadinessResponse(status="UP")` when cached table readiness is healthy; return `503 ReadinessResponse(status="DOWN")` only when a post-start TTL refresh fails or sees the table inactive. `DynamoDbKtorPlugin` startup/table-bootstrap failure is allowed to fail application startup instead of serving readiness. Repeated probes must not issue DynamoDB commands on every request. |

## Error Handling

- Validation failures use explicit field checks and return a stable
  `ErrorResponse(code, message)`.
- AWS `ConditionalCheckFailedException` is translated to domain conflicts
  without leaking raw AWS request ids, endpoints, or credentials.
- Lookup/update/delete miss paths return `404`.
- Unexpected DynamoDB failures are logged at the service boundary with
  sanitized structured fields only: operation name, safe error class, and a
  request diagnostic id. Do not log raw AWS exception messages, request ids,
  endpoints, headers, credentials, full request/response payloads, or
  environment-derived config.
- Coroutine `CancellationException` is rethrown before broad exception mapping.
- Malformed JSON returns a safe `400` response.
- Timeout or emulator bootstrap failures return a safe `503`/`500` response
  depending on startup vs request phase and must not expose endpoint or
  credential details.

## Security Defaults

- The workshop API is unauthenticated and local-first. README files must state
  that mutating routes must not be exposed publicly without external
  authentication, authorization, network controls, and least-privilege IAM.
- Local emulator credentials are static dummy values supplied by tests or local
  scripts. No real credentials are committed, printed, logged, or embedded in
  README examples.
- Real AWS opt-in uses the AWS default/environment credential provider chain
  only and requires `-Dbluetape4k.aws.mode=real`.
- DTO-only JSON deserialization is required. Do not enable Jackson default
  typing or polymorphic type handling. Prefer Ktor `kotlinx.serialization` JSON
  to match existing workshop Ktor modules.
- Request body size must be bounded by Ktor configuration or route-level checks
  where practical for this module.

## Operations

- Startup logs may include mode (`local` or `real`), emulator (`floci`,
  `localstack`, or null), region, table name, and sanitized endpoint class
  (`local-emulator` or `aws`) but not credentials or full endpoint URLs.
- Every error response includes a stable `code` and safe message. A diagnostic
  id may be returned or logged, but it must not contain credentials or raw AWS
  ids.
- Readiness captures table bootstrap state at startup and may refresh on a
  30-second TTL. Repeated probes must not call DynamoDB on every request; a
  refresh may issue at most one `DescribeTable(tableName)` and must time out
  within 2 seconds.
- Table bootstrap uses `DynamoDbKtorPlugin.autoCreateTables` with a bounded
  table-ready timeout of 30 seconds in tests and 60 seconds for manual runs.
- Local/test tables are disposable. Tests create unique table names and clean
  them up when possible. Real AWS mode docs must include cost and cleanup
  warnings.
- Schema evolution is out of scope; local examples use disposable table rebuilds
  rather than migrations.

## Build Contract

- Apply `alias(libs.plugins.kotlin.serialization)` to the module.
- Use `implementation(platform(libs.ktor.bom))`.
- Use `implementation(libs.ktor.serialization.kotlinx.json)` and
  `implementation(libs.kotlinx.serialization.json)` for DTO JSON support.
- Add `bluetape4k-aws-ktor`, `bluetape4k-aws-kotlin`, and
  `aws-kotlin-dynamodb` catalog aliases as described in Current Evidence.

## Test Strategy

- TDD red tests precede production implementation.
- Use Ktor `testApplication {}` and a JSON client with
  `ContentNegotiation`.
- Use `FlociServer.Launcher.floci` by default through
  `bluetape4k-testcontainers`.
- Support `-Dbluetape4k.aws.emulator=localstack` to run the same tests against
  `LocalStackServer.Launcher.getLocalStack("dynamodb")`.
- Generate a unique table name per test class and unique item ids per test.
  Clean up test-owned tables/items where the emulator supports it. List
  assertions must be scoped to test-owned ids.
- Disable JUnit parallel execution in module test resources.
- Bound emulator startup, table bootstrap, readiness, and route tests with
  explicit timeouts: 90 seconds for emulator startup, 30 seconds for test table
  readiness, 2 seconds for readiness refresh, and 10 seconds per route test.
  Record cold/warm `./gradlew :aws-ktor-dynamodb:test --max-workers=1` timing
  evidence in the final DoD. Escalate instead of hiding the issue if warm test
  runtime exceeds 3 minutes or cold runtime exceeds 6 minutes on the local
  machine.
- Use bounded AWS SDK retry settings for local tests: maximum 2 attempts, total
  call deadline 5 seconds, and backoff cap 500 milliseconds. Do not add
  domain-level retries for conditional failures.
- Cover:
  - create then read one order session,
  - bounded list includes created order sessions and returns/consumes
    continuation tokens,
  - duplicate create returns `409 Conflict`,
  - update with matching `expectedVersion` increments `version`,
  - update with stale `expectedVersion` returns `409 Conflict`,
  - concurrent updates with the same expected version produce exactly one
    success and the rest `409` without retries or spin loops,
  - lookup miss returns `404 Not Found`,
  - delete removes an existing item and missing delete returns `404`,
  - invalid status or blank id/customer id returns `400 Bad Request`,
  - malformed JSON returns `400 Bad Request`,
  - JSON serialization round-trips request and response DTOs,
  - readiness route sees the auto-created table.
- Run module tests serially:

```bash
./gradlew :aws-ktor-dynamodb:test --max-workers=1
```

## Documentation And Diagrams

- Add module README files:
  - `aws/ktor-dynamodb/README.md`
  - `aws/ktor-dynamodb/README.ko.md`
- Update AWS group README files:
  - `aws/README.md`
  - `aws/README.ko.md`
- Update root README files:
  - `README.md`
  - `README.ko.md`
- Add two README diagrams under `docs/images/readme-diagrams/`:
  - `aws-ktor-dynamodb-readme-architecture-01.svg/png`
  - `aws-ktor-dynamodb-readme-sequence-01.svg/png`
- Both module README locales must contain equivalent sections for overview,
  architecture, API, local run, test commands, LocalStack parity, real AWS
  opt-in, error handling, unsupported capabilities, and cleanup.
- README files must include copy-pasteable HTTP examples for create, read,
  bounded list, update, delete, duplicate create `409`, stale update `409`,
  missing item `404`, and validation/malformed JSON `400`.
- README local commands must include the emulator prerequisite and a fail-closed
  local app run command with endpoint and dummy credentials:

```bash
./gradlew :aws-ktor-dynamodb:test --max-workers=1
./gradlew :aws-ktor-dynamodb:test --max-workers=1 -Dbluetape4k.aws.emulator=localstack

# Start a local AWS-compatible emulator first, then pass its endpoint explicitly.
./gradlew :aws-ktor-dynamodb:run \
  -Dbluetape4k.aws.mode=local \
  -Dbluetape4k.aws.region=ap-northeast-2 \
  -Dbluetape4k.aws.dynamodb.table-name=workshop-order-sessions-local \
  -Dbluetape4k.aws.dynamodb.endpoint-url=http://localhost:4566 \
  -Dbluetape4k.aws.access-key-id=test \
  -Dbluetape4k.aws.secret-access-key=test

curl -fsS http://localhost:8080/health/readiness
```

- Real AWS README commands must be clearly marked advanced/optional, must not
  be used by CI, and must require explicit mode, region, table name, and AWS
  default/environment credentials:

```bash
./gradlew :aws-ktor-dynamodb:run \
  -Dbluetape4k.aws.mode=real \
  -Dbluetape4k.aws.region=ap-northeast-2 \
  -Dbluetape4k.aws.dynamodb.table-name=workshop-order-sessions
```

- Diagrams must use the current best-practices style: top-to-bottom or clear
  layer flow for architecture, numbered sequence call labels, transparent alt
  regions, aligned card text, official AWS/DynamoDB visual where an icon is
  used, connector legends when line styles differ, rounded orthogonal
  connectors, checklist validation, and full-size visual inspection.
- Diagrams must explicitly show the default local emulator boundary, optional
  real AWS boundary, bounded list/pagination, and the `400`/`404`/`409` failure
  paths.

## CI And Registration

- `settings.gradle.kts`: no manual change expected because `aws/*` modules are
  auto-included.
- `gradle/libs.versions.toml`: add only versionless aliases needed by the new
  module, reusing existing version entries and the root BOM.
- `.github/workflows/Examples.yml`: add `aws/ktor-dynamodb/**` path filters and
  add `:aws-ktor-dynamodb:test` to the existing sequential
  `container-examples` job, with artifact paths:
  - `aws/ktor-dynamodb/build/test-results/test/*.xml`
  - `aws/ktor-dynamodb/build/reports/tests/test/`
- `.github/workflows/nightly.yml`: no targeted change expected because weekly
  full runs `./gradlew test --continue --max-workers=1`; verify after project
  registration.
- `scripts/smoke-validate.sh`: add `:aws-ktor-dynamodb:test` to a Docker-backed
  group only, not `all-smoke`; update stale-check expected project count to 95.
- `./gradlew projects --console=plain` must list `:aws-ktor-dynamodb`.

## Acceptance Criteria

- `:aws-ktor-dynamodb` exists and compiles.
- All production dependencies use the root BOM or version catalog aliases; no
  bluetape4k version is pinned locally.
- Default tests do not call real AWS.
- Tests cover create, update, duplicate conditional failure, stale-version
  conditional failure, bounded list/pagination, concurrent stale updates,
  lookup miss, delete, malformed JSON, readiness, and serialization.
- README.md and README.ko.md exist for the module and explain local run/test
  commands plus guarded real AWS opt-in, cleanup, and unsupported capabilities.
- Root and AWS README locale pairs mention the new module consistently.
- Architecture and sequence diagrams have SVG and PNG outputs and pass the full
  `$bluetape4k-diagram` checklist plus full-size visual inspection.
- CI/container registration is updated for the new Testcontainers-backed
  module.
- Real AWS mode is guarded by `-Dbluetape4k.aws.mode=real`, is absent from
  tests/CI, and uses environment/default credentials only.
- Successful update uses one `UpdateItem`; update failure uses at most one
  failed `UpdateItem` plus one `GetItem`; list is bounded and paginated.
- `./gradlew :aws-ktor-dynamodb:test --max-workers=1` passes.
- `./gradlew projects --console=plain` passes and includes the module.
- `git diff --check` passes.

## Risks And Mitigations

| risk | mitigation |
| --- | --- |
| Emulator startup instability | Use shared `bluetape4k-testcontainers` launchers, unique table names, and serial tests. |
| Conditional failure ambiguity between missing and stale versions | Do not pre-read on successful updates; disambiguate only after a failed conditional update using a fallback `GetItem`. |
| AWS SDK model API drift | Compile against current catalog and use existing `bluetape4k-aws` examples as source evidence. |
| README diagram quality regressions | Run the full diagram checklist and inspect full-size PNG outputs before PR. |
| CI runtime growth | Keep the module out of no-container smoke; add it only to the sequential container-backed lane. |

## Step 2-R 7-Tier Spec Review Log

Initial review results:

| Tier | P0 | P1 | Key findings | Resolution |
| --- | --- | --- | --- | --- |
| Performance | 0 | 2 | Read-before-update made success path two commands; list was unbounded scan. | Added DynamoDB command budget, one-command update success, failure-only `GetItem` disambiguation, bounded paginated list. |
| Stability | 0 | 2 | Test state bleed and timeout/deadline behavior were underspecified. | Added unique table/items, cleanup, disabled parallel execution, bounded timeouts, bounded retry requirements. |
| Security | 0 | 0 | Auth boundary, expression injection, log redaction, and JSON safety needed stronger requirements. | Added Security Defaults, expression binding rules, safe logging, DTO-only serialization, credential handling. |
| Operator | 0 | 0 | Observability, CI lane, resource ownership, and real AWS runbook were too loose. | Added Operations section, exact container lane, artifact paths, local/LocalStack/real-AWS commands, cleanup rules. |
| Developer/API | 0 | 2 | Conditional update/delete contract was not atomic; dependency and JSON stack were unclear. | Added conditional-failure disambiguation, conditional delete, `aws-kotlin` version-ref alias rule, `kotlinx.serialization` DTO rule. |
| User/Caller | 0 | 2 | Local run path and real AWS opt-in were underspecified. | Added exact README command set, explicit real AWS guard, cost/cleanup warnings, parity/API examples requirements. |
| Main integration | 0 | 0 | P1 findings overlapped and were all resolvable in spec. | Applied edits above; rerun affected lanes before closing Step 2-R. |

Convergence requirement: Step 2-R closes only when affected rerun lanes report
P0 = 0 and P1 = 0.

Final convergence:

| Tier | Final status | Evidence |
| --- | --- | --- |
| Performance | PASS | Rerun confirmed no read-before-update contradiction, readiness TTL/command budget, and concrete runtime thresholds. |
| Stability | PASS | Rerun confirmed retry bounds, timeouts, state isolation, and lifecycle cleanup requirements. |
| Security | PASS | Rerun confirmed P0/P1/P2 = 0; remaining request-size wording became an implementation detail for the plan. |
| Operator | PASS | Rerun confirmed P0/P1 = 0; mode/emulator terminology was normalized after the review. |
| Developer/API | PASS | Rerun confirmed readiness contract, build contract, DTO shape, and Gradle alias realism. |
| User/Caller | PASS | Rerun confirmed local commands, real AWS guardrails, README parity, and learner examples. |
| Main integration | PASS | Local `git diff --check -- docs/superpowers/specs/2026-07-01-issue-325-ktor-dynamodb-local-first-design.md` passed. |

Step 2-R gate: PASS with P0 = 0 and P1 = 0.
