# Issue 372 AWS Floci Coverage Review

## Scope

- Issue: #372, milestone 1.3.1
- Changed modules: `aws/README.md`, `aws/README.ko.md`, `aws/s3-spring-cloud`
- Review type: 7-tier local review for AWS emulator coverage and bluetape4k code-pattern compliance

## Coverage Inventory

| Module | Classification | Evidence | Rationale |
| --- | --- | --- | --- |
| `aws/s3-spring-cloud` | Floci-backed S3 integration | `SpringCloudAwsS3Test` starts `FlociServer.Launcher.floci` through `DynamicPropertySource` and verifies `S3Template`, `S3Client`, and `ResourceLoader`. | S3 is supported by the local AWS emulator and is practical to test with Testcontainers. |
| `aws/storage-abstraction` | Floci-backed S3 integration | `S3Config.floci` uses `FlociServer.Launcher.floci`; `S3StorageServiceTest` and `S3PresignedStorageServiceTest` cover upload/download/delete/presign behavior. | Existing tests already use the bluetape4k launcher singleton. |
| `aws/ktor-dynamodb` | Floci-backed DynamoDB integration | `OrderSessionDynamoDbEmulatorTest` uses `FlociServer.Launcher.floci`, AWS Kotlin `DynamoDbClient`, and `DynamoDbKtorPlugin`. | Existing route-level test covers the supported emulator path. |
| `aws/sqs-sns-coroutines` | Floci-backed SNS/SQS integration plus local adapter unit tests | `OrderNotificationFlociIntegrationTest` uses `FlociServer.Launcher.floci`, `SnsCoroutinesTemplate`, and `SqsCoroutinesTemplate`. | Local fakes keep boundary tests small; Floci test covers real bluetape4k operations. |
| `aws/eventbridge-scheduler` | Local adapter only | `OrderWorkflowServiceTest` uses capturing `EventBridgePublisher` and `WorkflowScheduler` boundaries. | The lesson covers request mapping, idempotency, failure, and cancellation without provisioning real AWS targets. |
| `aws/cloudwatch-imds-observability` | Local adapter only | Controller/service tests use local/capturing CloudWatch, CloudWatch Logs, meter, and IMDS operations. | Default tests intentionally avoid CloudWatch and IMDS network calls; metadata access is explicit opt-in. |
| `aws/s3-vectors-access-grants` | Local adapter only | Service/controller tests use capturing `S3VectorsOperations` and `S3AccessGrantsOperations`. | The workshop behavior is deterministic local vector ranking and redacted access reports behind bluetape4k operation interfaces. |

## 7-Tier Review

| Tier | Result | Evidence |
| --- | --- | --- |
| 1. Requirements | PASS | Issue #372 DoD mapped to README inventory, PR-body inventory, and serial AWS module tests. |
| 2. Architecture | PASS | Emulator-backed services use `FlociServer.Launcher.floci`; unsupported/fake-only paths are documented with learner-facing rationale. |
| 3. Correctness | PASS | `SpringCloudAwsS3Test` now performs real S3 upload/list/read through Spring Cloud AWS and Floci. Fixed stale `testcontainers.localstack.url` placeholder. |
| 4. Concurrency/Lifecycle | PASS | Testcontainers-backed Gradle verification ran in one serial invocation with `--max-workers=1`; no parallel container test execution. |
| 5. bluetape4k Reuse | PASS | Uses `bluetape4k-testcontainers` `FlociServer`, `bluetape4k-assertions`, and existing `staticCredentialsProviderOf` / S3 extension patterns. |
| 6. Documentation | PASS | `aws/README.md` and `aws/README.ko.md` now include a local AWS coverage table with Floci/fake-only classification. |
| 7. Verification | PASS | Targeted S3 test and all AWS module tests passed; README parity/language and whitespace checks passed. |

## Findings

- P0: None.
- P1: None.
- P2: None.

## Verification

- `./gradlew :aws-s3-spring-cloud:test --tests '*SpringCloudAwsS3Test' --no-build-cache --warning-mode all --console=plain --max-workers=1`
  - PASS: 1 test executed, build successful.
- `./gradlew :aws-s3-spring-cloud:cleanTest :aws-s3-spring-cloud:test :aws-storage-abstraction:cleanTest :aws-storage-abstraction:test :aws-ktor-dynamodb:cleanTest :aws-ktor-dynamodb:test :aws-eventbridge-scheduler:cleanTest :aws-eventbridge-scheduler:test :aws-sqs-sns-coroutines:cleanTest :aws-sqs-sns-coroutines:test :aws-cloudwatch-imds-observability:cleanTest :aws-cloudwatch-imds-observability:test :aws-s3-vectors-access-grants:cleanTest :aws-s3-vectors-access-grants:test --no-build-cache --warning-mode all --console=plain --max-workers=1`
  - PASS: AWS module test counts were 1, 17, 25, 5, 8, 9, and 5; build successful in 25s.
- `node scripts/validate-readme-parity.mjs`
  - PASS: `failures=0`.
- `node scripts/validate-readme-language.mjs`
  - PASS: `offenders=0`, `totalHits=0`.
- `git diff --check`
  - PASS: no whitespace errors.

## Residual Risk

- Floci support is used only for services already practical in this workshop. EventBridge Scheduler, CloudWatch/IMDS, S3 Vectors, and S3 Access Grants remain local-boundary examples by design.
