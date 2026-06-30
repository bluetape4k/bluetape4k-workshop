# Issue #318 - AWS S3 Vectors and Access Grants Workshop Spec

- Date: 2026-06-30
- Issue: https://github.com/bluetape4k/bluetape4k-workshop/issues/318
- Work type: Type A Full Feature
- Target repository: `bluetape4k/bluetape4k-workshop`
- Target module: `aws/s3-vectors-access-grants`
- Gradle project: `:aws-s3-vectors-access-grants`

## Problem

`bluetape4k-dependencies 1.3.1` promotes the `bluetape4k-aws` line that added
optional S3 Vectors and S3 Access Grants integration. The current AWS workshop
already teaches ordinary S3 object storage, pre-signed URLs, storage profiles,
and CloudWatch/IMDS observability, but it does not show two newer boundaries:

- S3 Vectors is a dedicated vector-search service surface, not ordinary S3
  object storage with embeddings hidden in object metadata.
- S3 Access Grants uses S3 Control to request scoped data access, and must be
  separated from broad S3 client permissions and policy-changing operations.

The workshop needs a learner-friendly example that explains both surfaces
without requiring live AWS resources in default tests.

## Current Evidence

- Issue #318 is open in milestone `1.3.1`, assigned to `debop`, with labels
  `documentation`, `enhancement`, `difficulty:advanced`,
  `area:architecture-extension`, and `area:storage`.
- `settings.gradle.kts` auto-registers `aws/*` directories as `:aws-*`
  modules, so `aws/s3-vectors-access-grants` becomes
  `:aws-s3-vectors-access-grants`.
- `gradle/libs.versions.toml` currently has `aws2-s3-lib` and
  `aws2-s3-transfer-manager`, but no `aws2-s3vectors-lib` or
  `aws2-s3control-lib` alias.
- `bluetape4k-aws` exposes `io.bluetape4k.aws.s3vectors.S3VectorsOperations`
  with `listVectorBuckets`, `getVectorBucket`, `listIndexes`, `getIndex`,
  `putVectors`, `getVectors`, `listVectors`, and `queryVectors`.
- `bluetape4k-aws` exposes
  `io.bluetape4k.aws.spring.s3.accessgrants.S3AccessGrantsOperations` with
  `getDataAccess`, `listCallerAccessGrants`, `listAccessGrants`,
  `listAccessGrantsInstances`, and `listAccessGrantsLocations`.
- `S3VectorsAutoConfiguration` is disabled by default and requires the optional
  AWS SDK `software.amazon.awssdk:s3vectors` service dependency.
- `S3AccessGrantsAutoConfiguration` is disabled by default and requires the
  optional AWS SDK `software.amazon.awssdk:s3control` service dependency.
- AWS SDK for Java 2.x documents S3 Vectors through the
  `software.amazon.awssdk.services.s3vectors` client/model package and Access
  Grants through S3 Control `getDataAccess` and list APIs.
- GNO surfaced related merged `bluetape4k-aws` PRs:
  - PR #291 `feat: add optional S3 Vectors support`
  - PR #290 `feat(aws-ktor): add optional S3 Access Grants integration`
- The AWS workshop lesson from issue #317 says new AWS examples should stay
  local-first, keep real AWS profiles explicit, and distinguish local fake
  adapters from real AWS managed services in diagrams.

## Constraints

- Use the root `bluetape4k-dependencies` BOM only. Do not pin a bluetape4k
  module version.
- Default tests must not require AWS credentials, live S3 Vectors, Access
  Grants setup, LocalStack, Floci, or emulator support.
- The example must not imply that S3 Vectors is ordinary S3 object storage.
- The example must not expose, log, or persist temporary credentials returned
  from Access Grants. Learners may see the grant decision and permission scope,
  but not credential material.
- Access Grants administrative create/update/delete operations are out of
  scope; the workshop uses common read/data-access and list paths only.
- README work is bilingual: `README.md` and `README.ko.md`.
- Diagram work must use `$bluetape4k-diagram`, include SVG and PNG assets, pass
  the current checklist, and record full-size visual inspection evidence.
- New module registration must cover root/AWS README tables, example CI/smoke
  coverage, stale-check project counts, and `./gradlew projects`.

## Goals

1. Add `aws/s3-vectors-access-grants` as a Spring Boot local-first example.
2. Model a small access-scoped document retrieval and similarity-search
   scenario for learners.
3. Use `S3VectorsOperations` for vector index discovery, upsert, and query
   intent.
4. Use `S3AccessGrantsOperations` for caller grant listing and scoped
   `getDataAccess` intent.
5. Keep default tests deterministic with fake operation implementations.
6. Clearly separate ordinary S3 object storage, pre-signed URL S3, S3 Vectors,
   and Access Grants in code, README text, and diagrams.
7. Document optional real-AWS profile prerequisites without running that profile
   in CI.
8. Register the module in CI/smoke coverage.

## Non-Goals

- Do not replace `aws/storage-abstraction` or `aws/s3-spring-cloud`.
- Do not create real vector buckets, vector indexes, Access Grants instances,
  grants, or locations in default tests.
- Do not guarantee emulator support for S3 Vectors or Access Grants.
- Do not implement RAG answer generation or an LLM integration.
- Do not wrap destructive or policy-changing S3 Control APIs in the workshop
  service.
- Do not show or store Access Grants temporary credentials in reports.

## Approach Options

### Option A - Spring Boot Local-First Consumer Module

Create a Spring Boot module that consumes the published bluetape4k facade
interfaces through application services. Local fake adapters capture request
intent and return deterministic responses. README and diagrams explain how to
swap to real AWS manually.

Benefits:

- Matches current AWS workshop shape and Spring Boot issue train.
- Keeps CI fast and credential-free.
- Teaches the application boundary, not AWS account provisioning.
- Lets diagrams show clear local fake vs AWS managed service boundaries.

Costs:

- Does not prove live AWS behavior by default; docs must state this clearly.
- Requires fake adapters with enough fidelity to teach request shape.

### Option B - Ktor Example

Use Ktor plugins and route tests for S3 Vectors and Access Grants.

Benefits:

- Aligns with some `bluetape4k-aws` Ktor Access Grants work.
- Useful for non-Spring learners.

Costs:

- Existing AWS workshop modules are Spring-oriented.
- Would mix Ktor mechanics with two already-new AWS surfaces.
- Issue acceptance is not Ktor-specific.

### Option C - Real AWS Profile First

Make the module primarily a real-AWS integration lab, with docs describing IAM,
Access Grants, vector bucket setup, and cleanup.

Benefits:

- Closest to production setup.
- Can validate actual AWS service behavior manually.

Costs:

- Too expensive and brittle for default workshop tests.
- Requires account-level setup and can leak confusing IAM concerns into the
  first learning path.

## Decision

Use Option A. The module will be a Spring Boot local-first consumer example.
It will expose a concise application service and HTTP facade for:

- registering searchable document vectors,
- querying similar documents,
- checking caller Access Grants,
- requesting scoped read access for a document URI,
- explaining which operations are fake-local vs real-AWS optional.

The optional real-AWS profile will be documented as a manual extension only.
It will not run in CI and will not be required for DoD.

## Architecture

### Runtime Components

- `S3VectorsAccessGrantsApplication`: Spring Boot entrypoint.
- `DocumentSearchController`: learner-facing HTTP endpoints.
- `DocumentSearchService`: orchestrates vector upsert/query and access-grant
  checks.
- `DocumentVectorRequest`, `DocumentSearchRequest`,
  `DocumentSearchReport`, `AccessGrantReport`, `VectorSearchMatch`: DTOs and
  report models.
- `DocumentAccessPolicy`: maps safe workshop document IDs to allowed S3 URIs
  and requested permissions.
- `AwsS3VectorsAccessProperties`: namespace, bucket/index names, account ID,
  Access Grants location ARN, local mode flag, and max input sizes.
- `LocalS3VectorsAccessConfig`: deterministic local fake beans for
  `S3VectorsOperations` and `S3AccessGrantsOperations`.
- `RealAwsS3VectorsAccessConfig`: optional profile/property boundary that uses
  bluetape4k AWS auto-configuration when the user adds credentials, region,
  vector bucket/index, and Access Grants setup.

### Data Flow

1. A learner upserts a small document vector with document metadata.
2. The service validates input size and stable document identifiers.
3. The service builds `PutVectorsRequest` through `S3VectorsOperations`.
4. A learner queries with an embedding vector.
5. The service builds `QueryVectorsRequest` and maps returned matches into a
   safe `DocumentSearchReport`.
6. A learner selects a search match for document retrieval.
7. The service checks whether the selected match maps to an allowed document
   URI before requesting data access.
8. The service builds `ListCallerAccessGrantsRequest` and, when allowed,
   `GetDataAccessRequest` with `Permission.READ`.
9. The report shows target URI, permission, grant status, and redacted access
   status, but never temporary credential values.

### Failure Handling

- S3 Vectors failures become vector operation status in the response.
- Access Grants failures become grant operation status in the response.
- Partial failures are explicit: vector query success does not imply access
  authorization success.
- `CancellationException` is rethrown from suspend service methods before
  broad exception handling.
- Caller input is validated with bluetape4k validation helpers.
- Credential fields from `GetDataAccessResponse` are never exposed in DTOs,
  logs, diagrams, or README examples.

## Test Strategy

- TDD red tests precede production implementation.
- Service tests use deterministic fake operations:
  - upsert builds expected vector bucket/index and vector key,
  - query builds expected `QueryVectorsRequest` and maps matches,
  - selected match retrieval is gated by a successful access-grant decision,
  - grant listing builds expected account and S3 prefix scope,
  - `getDataAccess` builds `Permission.READ` and returns redacted status,
  - access denial prevents scoped data-access request,
  - S3 Vectors failure does not expose AWS internals,
  - Access Grants failure does not expose credential material,
  - cancellation is rethrown.
- Controller tests verify JSON shape, validation failures, local profile wiring,
  no credential field leakage, and no credential material in captured logs.
- Spring context smoke tests verify local fake beans wire without AWS
  credentials.
- No Testcontainers tests are planned.

## Documentation and Diagrams

Create or update:

- `aws/s3-vectors-access-grants/README.md`
- `aws/s3-vectors-access-grants/README.ko.md`
- `aws/README.md`
- `aws/README.ko.md`
- root `README.md`
- root `README.ko.md`

Add diagrams under `docs/images/readme-diagrams/`:

- `aws-s3-vectors-access-grants-readme-architecture-01.svg/png`
- `aws-s3-vectors-access-grants-readme-sequence-01.svg/png`

Diagram requirements:

- Use official AWS icons only for real AWS managed service cards such as S3
  Vectors, S3 Control, and S3.
- Keep local fake operation beans text-only or clearly local, not AWS-service
  branded.
- Do not show temporary access key, secret key, session token, credential JSON,
  or credential field names in diagram labels, README examples, or screenshots.
- Architecture diagram should show layers and connector semantics with a
  legend if solid/dashed lines differ.
- Sequence diagram must use the current best-practices style: participant
  headers, lifelines, activation bars, numbered pill labels above call lines,
  transparent `alt`/`else` bodies, branch-specific muted colors, and matching
  line/arrowhead colors.

## Acceptance Criteria

- Root BOM only; no explicit bluetape4k versions.
- `aws2-s3vectors-lib` and `aws2-s3control-lib` aliases added without local
  AWS SDK version drift.
- `:aws-s3-vectors-access-grants` appears in `./gradlew projects`.
- Default tests pass without credentials, containers, or live AWS.
- README pair explains prerequisites, boundaries, local fake mode, optional
  real-AWS mode, and differences from existing S3 examples.
- Diagram assets pass `$bluetape4k-diagram` checklist and full-size PNG visual
  inspection.
- CI/smoke workflow coverage includes the new module.

## External References

- AWS SDK for Java 2.x S3 Vectors API:
  https://sdk.amazonaws.com/java/api/latest/software/amazon/awssdk/services/s3vectors/S3VectorsClient.html
- AWS SDK for Java 2.x S3 Vectors query model:
  https://sdk.amazonaws.com/java/api/latest/software/amazon/awssdk/services/s3vectors/model/QueryVectorsRequest.html
- AWS SDK for Java 2.x S3 Control API:
  https://sdk.amazonaws.com/java/api/latest/software/amazon/awssdk/services/s3control/S3ControlClient.html
- AWS SDK for Java 2.x Access Grants data access model:
  https://sdk.amazonaws.com/java/api/latest/software/amazon/awssdk/services/s3control/model/GetDataAccessRequest.html
