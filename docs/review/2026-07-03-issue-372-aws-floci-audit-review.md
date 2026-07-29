# Issue 372 AWS Floci Coverage Review

## 범위

- 이슈: #372, milestone 1.3.1
- 변경 module: `aws/README.md`, `aws/README.ko.md`, `aws/s3-spring-cloud`
- 리뷰 유형: AWS emulator coverage와 bluetape4k code-pattern compliance에 대한 7-tier local review

## Coverage Inventory

| Module | 분류 | 근거 | 이유 |
| --- | --- | --- | --- |
| `aws/s3-spring-cloud` | Floci-backed S3 integration | `SpringCloudAwsS3Test`가 `DynamicPropertySource`를 통해 `FlociServer.Launcher.floci`를 시작하고 `S3Template`, `S3Client`, `ResourceLoader`를 검증한다. | S3는 local AWS emulator가 지원하며 Testcontainers로 테스트하기에 현실적이다. |
| `aws/storage-abstraction` | Floci-backed S3 integration | `S3Config.floci`가 `FlociServer.Launcher.floci`를 사용한다. `S3StorageServiceTest`와 `S3PresignedStorageServiceTest`는 upload/download/delete/presign 동작을 다룬다. | 기존 테스트가 이미 bluetape4k launcher singleton을 사용한다. |
| `aws/ktor-dynamodb` | Floci-backed DynamoDB integration | `OrderSessionDynamoDbEmulatorTest`가 `FlociServer.Launcher.floci`, AWS Kotlin `DynamoDbClient`, `DynamoDbKtorPlugin`을 사용한다. | 기존 route-level test가 지원되는 emulator path를 다룬다. |
| `aws/sqs-sns-coroutines` | Floci-backed SNS/SQS integration 및 local adapter unit test | `OrderNotificationFlociIntegrationTest`가 `FlociServer.Launcher.floci`, `SnsCoroutinesTemplate`, `SqsCoroutinesTemplate`를 사용한다. | Local fake는 boundary test를 작게 유지하고, Floci test는 실제 bluetape4k operation을 다룬다. |
| `aws/eventbridge-scheduler` | Local adapter only | `OrderWorkflowServiceTest`가 capturing `EventBridgePublisher`와 `WorkflowScheduler` boundary를 사용한다. | 이 lesson은 real AWS target provisioning 없이 request mapping, idempotency, failure, cancellation을 다룬다. |
| `aws/cloudwatch-imds-observability` | Local adapter only | Controller/service test가 local/capturing CloudWatch, CloudWatch Logs, meter, IMDS operation을 사용한다. | 기본 테스트는 의도적으로 CloudWatch와 IMDS network call을 피한다. metadata access는 explicit opt-in이다. |
| `aws/s3-vectors-access-grants` | Local adapter only | Service/controller test가 capturing `S3VectorsOperations`와 `S3AccessGrantsOperations`를 사용한다. | workshop behavior는 bluetape4k operation interface 뒤의 deterministic local vector ranking과 redacted access report이다. |

## 7-Tier Review

| Tier | 결과 | 근거 |
| --- | --- | --- |
| 1. Requirements | PASS | Issue #372 DoD가 README inventory, PR-body inventory, serial AWS module test에 매핑되었다. |
| 2. Architecture | PASS | Emulator-backed service는 `FlociServer.Launcher.floci`를 사용한다. unsupported/fake-only path는 learner-facing rationale과 함께 문서화되어 있다. |
| 3. Correctness | PASS | `SpringCloudAwsS3Test`는 이제 Spring Cloud AWS와 Floci를 통해 real S3 upload/list/read를 수행한다. stale `testcontainers.localstack.url` placeholder도 수정했다. |
| 4. Concurrency/Lifecycle | PASS | Testcontainers-backed Gradle verification은 `--max-workers=1`을 사용한 단일 serial invocation으로 실행했다. parallel container test execution은 없다. |
| 5. bluetape4k Reuse | PASS | `bluetape4k-testcontainers` `FlociServer`, `bluetape4k-assertions`, 기존 `staticCredentialsProviderOf` / S3 extension pattern을 사용한다. |
| 6. Documentation | PASS | `aws/README.md`와 `aws/README.ko.md`는 Floci/fake-only classification이 포함된 local AWS coverage table을 이제 제공한다. |
| 7. Verification | PASS | Targeted S3 test와 모든 AWS module test가 통과했다. README parity/language 및 whitespace check도 통과했다. |

## 발견사항

- P0: 없음.
- P1: 없음.
- P2: 없음.

## 검증

- `./gradlew :aws-s3-spring-cloud:test --tests '*SpringCloudAwsS3Test' --no-build-cache --warning-mode all --console=plain --max-workers=1`
  - PASS: 1 test executed, build successful.
- `./gradlew :aws-s3-spring-cloud:cleanTest :aws-s3-spring-cloud:test :aws-storage-abstraction:cleanTest :aws-storage-abstraction:test :aws-ktor-dynamodb:cleanTest :aws-ktor-dynamodb:test :aws-eventbridge-scheduler:cleanTest :aws-eventbridge-scheduler:test :aws-sqs-sns-coroutines:cleanTest :aws-sqs-sns-coroutines:test :aws-cloudwatch-imds-observability:cleanTest :aws-cloudwatch-imds-observability:test :aws-s3-vectors-access-grants:cleanTest :aws-s3-vectors-access-grants:test --no-build-cache --warning-mode all --console=plain --max-workers=1`
  - PASS: AWS module test count는 1, 17, 25, 5, 8, 9, 5였고 build successful in 25s.
- `node scripts/validate-readme-parity.mjs`
  - PASS: `failures=0`.
- `node scripts/validate-readme-language.mjs`
  - PASS: `offenders=0`, `totalHits=0`.
- `git diff --check`
  - PASS: whitespace error 없음.

## 잔여 위험

- Floci support는 이 workshop에서 이미 현실적으로 사용할 수 있는 service에만 적용된다. EventBridge Scheduler, CloudWatch/IMDS, S3 Vectors, S3 Access Grants는 설계상 local-boundary example로 남는다.
