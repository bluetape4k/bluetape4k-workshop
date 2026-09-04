# AWS Workshop

[English](README.md) | 한국어

AWS 워크숍은 S3 storage, DynamoDB persistence, event routing, scheduled workflow,
queue/topic messaging, vector search, access decision, observability를 로컬에서 먼저 검증할 수 있는 예제로
구성됩니다. Spring Cloud AWS와 `S3Template` 흐름을 작게 실행해 보고 싶다면
`s3-spring-cloud/`를 사용합니다. 로컬 파일, S3, pre-signed S3 URL 사이를 Spring
profile로 전환하는 서비스 경계를 보고 싶다면 `storage-abstraction/`을 사용합니다.
Ktor route, DynamoDB table bootstrap, conditional write, optimistic update, opt-in DynamoDB
Streams coroutine Flow consumer를 로컬 AWS 에뮬레이터로 확인하고 싶다면 `ktor-dynamodb/`를 사용합니다. 실제 AWS 자격 증명 없이
주문 workflow를 EventBridge event와 지연 Scheduler request로 매핑하는 흐름을 보고
싶다면 `eventbridge-scheduler/`를 사용합니다. SNS로 주문 알림을 publish하고, SQS
메시지를 consume하며, ack/retry/dead-letter 결과를 코루틴 코드에서 분류하는 흐름을
보고 싶다면 `sqs-sns-coroutines/`를 사용합니다. S3 Vectors upsert/query와 S3 Access
Grants read decision을 분리해서 보고 싶다면 `s3-vectors-access-grants/`를 사용합니다.
실제 AWS 자격 증명 없이 CloudWatch metrics, CloudWatch Logs, Micrometer publishing,
명시적 IMDS 경계를 배우고 싶다면 `cloudwatch-imds-observability/`를 사용합니다. partition key,
shard/sequence report, coroutine cancellation, bounded retry/backoff를 credential-free local
fake로 배우고 실제 AWS는 명시적으로 opt-in하고 싶다면 `kinesis-coroutines/`를 사용합니다.
Bedrock Converse/ConverseStream consumer 경계, cold Flow collection, 작업 단위 client
lifecycle, 명시적 real AWS opt-in을 보고 싶다면 `bedrock-converse/`를 사용합니다.
Secrets Manager와 secure Parameter Store를 하나의 provider-neutral resolver로
소비하고 startup/refresh fallback, full-replacement 설정 결과, redaction을 배우고 싶다면
`settings-boundary/`를 사용합니다.

## 아키텍처

![AWS Workshop architecture diagram](../docs/images/readme-diagrams/aws-readme-architecture-01.png)

모든 모듈은 기본 학습 경로를 local-first로 유지합니다. S3, DynamoDB, SQS/SNS 모듈은
Floci 기반 AWS 호환 인프라로 통합 테스트를 수행합니다. EventBridge Scheduler,
S3 Vectors/Access Grants, CloudWatch/IMDS는 실제 AWS 서비스나 IMDS를 호출하지 않고
request construction, failure isolation, 명시적 opt-in 경계를 배우도록 local adapter
경계를 유지합니다. Kinesis는 기본적으로 deterministic in-memory `local` adapter를 사용하고,
`real-aws` profile에서만 upstream AWS SDK v2 async client를 생성합니다.

## 모듈 가이드

| module | Gradle task path | use it for |
| --- | --- | --- |
| `s3-spring-cloud/` | `:aws-s3-spring-cloud` | Spring Cloud AWS `S3Template`, AWS SDK v2 `S3Client`, 버킷 생성, 객체 업로드, 객체 목록 조회, `ResourceLoader` 접근을 확인합니다. |
| `storage-abstraction/` | `:aws-storage-abstraction` | `local`, `s3`, `s3-presigned` profile을 가진 `StorageService`, coroutine 친화적인 blocking I/O 경계, pre-signed URL 동작을 확인합니다. |
| `ktor-dynamodb/` | `:aws-ktor-dynamodb` | Ktor REST route, Streams를 켠 `DynamoDbKtorPlugin` table bootstrap, conditional write, optimistic version update, local readiness, opt-in bounded coroutine Flow consumer를 확인합니다. |
| `eventbridge-scheduler/` | `:aws-eventbridge-scheduler` | Order workflow event envelope, EventBridge publish status, 지연 Scheduler request mapping, idempotency key, correlation id를 확인합니다. |
| `sqs-sns-coroutines/` | `:aws-sqs-sns-coroutines` | SNS publish request, SQS polling, coroutine cancellation propagation, retry visibility change, dead-letter report, Micrometer outcome metric을 확인합니다. |
| `kinesis-coroutines/` | `:aws-kinesis-coroutines` | Kinesis stream readiness, partition-key publish, shard/sequence consume, coroutine cancellation, bounded retry/backoff, local fake와 명시적 `real-aws` opt-in을 확인합니다. |
| `cloudwatch-imds-observability/` | `:aws-cloudwatch-imds-observability` | CloudWatch metric/log publish intent, Micrometer meter publishing, failure isolation, 명시적 IMDS metadata opt-in을 실제 자격 증명 없이 확인합니다. |
| `s3-vectors-access-grants/` | `:aws-s3-vectors-access-grants` | S3 Vectors 문서 upsert/query 경계, deterministic local vector ranking, redacted S3 Access Grants read-decision report를 확인합니다. |
| `bedrock-converse/` | `:aws-bedrock-converse` | Bedrock Converse/ConverseStream request mapping, cold text-delta Flow, cancellation-safe client lifecycle, 명시적 real AWS opt-in을 확인합니다. |
| `settings-boundary/` | `:aws-settings-boundary` | Provider-neutral Secrets Manager/secure Parameter Store lookup, startup/refresh fallback, full-replacement 설정 결과, redaction을 확인합니다. |

## 런타임 모델

| concern | implementation |
| --- | --- |
| AWS endpoint | 에뮬레이터 기반 모듈에서는 샘플 또는 테스트가 `bluetape4k-testcontainers`의 로컬 AWS 호환 에뮬레이터를 시작합니다. |
| Credentials | 에뮬레이터가 제공하는 정적 로컬 자격 증명을 사용하며, 로컬 검증에는 실제 AWS 자격 증명이 필요하지 않습니다. |
| S3 client | AWS SDK v2 `S3Client`를 사용합니다. storage abstraction 모듈은 blocking 호출을 `Dispatchers.IO`로 감쌉니다. |
| DynamoDB client | `DynamoDbKtorPlugin`을 통해 AWS Kotlin SDK `DynamoDbClient`를 설치하고, `ktor-dynamodb/`에서 native Kotlin `DynamoDbStreamsClient`와 checkpointed `shardRecordFlow`를 선택적으로 연결합니다. |
| EventBridge and Scheduler | `eventbridge-scheduler/`에서 AWS SDK v2 `PutEventsRequestEntry` 모델과 로컬 publisher/scheduler 경계를 사용합니다. |
| SQS and SNS messaging | `sqs-sns-coroutines/`에서 bluetape4k `SqsOperations`와 `SnsOperations`를 local adapter 및 Floci 통합 테스트와 함께 사용합니다. |
| Kinesis messaging | `KinesisOperations`를 deterministic local fake와 함께 사용하며, `real-aws`에서만 AWS SDK v2 `KinesisAsyncClient`와 upstream coroutine template을 명시적으로 활성화합니다. |
| Spring integration | `s3-spring-cloud/`는 Spring Cloud AWS `S3Template`과 `ResourceLoader`를, `storage-abstraction/`은 Spring profile을 사용합니다. |
| Vector and access boundaries | `s3-vectors-access-grants/`는 기본적으로 bluetape4k `S3VectorsOperations`와 `S3AccessGrantsOperations` local adapter를 사용합니다. |
| Observability | `cloudwatch-imds-observability/`는 기본적으로 로컬 CloudWatch intent를 publish하고, 명시적으로 요청한 경우에만 IMDS metadata를 읽습니다. |
| Bedrock runtime | `bedrock-converse/`는 upstream AWS Kotlin Bedrock helper를 사용합니다. 각 작업이 client를 소유하며 명시적인 factory만 live AWS를 활성화합니다. |
| Settings boundary | `settings-boundary/`는 upstream AWS Kotlin Secrets Manager와 secure SSM helper를 사용합니다. 각 lookup이 client를 소유하며 설정 결과는 이전 secret을 재사용하지 않습니다. |

## 로컬 AWS 커버리지

| module | coverage mode | rationale |
| --- | --- | --- |
| `s3-spring-cloud/` | Floci 기반 S3 통합 테스트 | Spring Boot 테스트가 `FlociServer.Launcher.floci`를 시작하고, 같은 S3 호환 endpoint에서 `S3Template`, `S3Client`, `ResourceLoader`를 검증합니다. |
| `storage-abstraction/` | Floci 기반 S3 통합 테스트 | `s3`, `s3-presigned` profile이 `S3Config.floci`를 사용하며 upload, download, delete, pre-signed URL 동작을 검증합니다. |
| `ktor-dynamodb/` | Floci 기반 DynamoDB + Streams 통합 테스트 | Ktor 테스트가 `FlociServer.Launcher.floci`, AWS Kotlin `DynamoDbClient`, Streams-enabled `DynamoDbKtorPlugin` table bootstrap, bounded Flow consume, checkpoint resume, duplicate report를 함께 검증합니다. |
| `sqs-sns-coroutines/` | Floci 기반 SNS/SQS 통합 테스트와 local adapter | Unit test는 local fake 경계를 작게 유지하고, 통합 테스트는 Floci에서 `SnsCoroutinesTemplate` publish와 `SqsCoroutinesTemplate` consume을 검증합니다. |
| `kinesis-coroutines/` | deterministic local adapter | 기본 테스트는 AWS credential resolution이나 network endpoint를 사용하지 않습니다. exactly-once/global ordering을 주장하지 않고 cancellation, retry/backoff, partition key, shard sequence report를 학습합니다. |
| `eventbridge-scheduler/` | local adapter only | 이 lesson은 실제 AWS target provisioning 없이 EventBridge entry, Scheduler request mapping, idempotency, failure/cancellation 경계를 배우는 데 집중합니다. |
| `cloudwatch-imds-observability/` | local adapter only | metadata 접근을 명시적이고 안전하게 유지하기 위해 기본 테스트에서는 CloudWatch와 IMDS network call을 피합니다. |
| `s3-vectors-access-grants/` | local adapter only | S3 Vectors와 S3 Access Grants는 bluetape4k operation interface 뒤에 두고, deterministic local ranking과 redacted access report를 테스트 대상 동작으로 둡니다. |
| `bedrock-converse/` | credential-free fake | 기본 테스트는 AWS client를 만들거나 network call을 수행하지 않습니다. fake client로 request mapping, cold Flow collection, cancellation, close 순서를 검증합니다. |
| `settings-boundary/` | credential-free fake | 기본 테스트는 Secrets Manager와 secure Parameter Store의 성공/누락/권한 오류 분류, refresh replacement, redaction, cancellation을 credential과 network 없이 검증합니다. |

## 실행

```bash
./gradlew :aws-s3-spring-cloud:test
./gradlew :aws-storage-abstraction:test
./gradlew :aws-ktor-dynamodb:test --max-workers=1
./gradlew :aws-eventbridge-scheduler:test
./gradlew :aws-sqs-sns-coroutines:test
./gradlew :aws-kinesis-coroutines:test
./gradlew :aws-cloudwatch-imds-observability:test
./gradlew :aws-s3-vectors-access-grants:test
./gradlew :aws-bedrock-converse:test
./gradlew :aws-settings-boundary:test
```

backend별 동작을 비교하려면 profile 기반 storage 샘플을 직접 실행합니다.

```bash
./gradlew :aws-storage-abstraction:bootRun --args='--spring.profiles.active=local'
./gradlew :aws-storage-abstraction:bootRun --args='--spring.profiles.active=s3'
./gradlew :aws-storage-abstraction:bootRun --args='--spring.profiles.active=s3-presigned'
./gradlew :aws-ktor-dynamodb:run \
  -Dbluetape4k.aws.mode=local \
  -Dbluetape4k.aws.dynamodb.endpoint-url=http://localhost:4566 \
  -Dbluetape4k.aws.access-key-id=test \
  -Dbluetape4k.aws.secret-access-key=test
./gradlew :aws-cloudwatch-imds-observability:bootRun
./gradlew :aws-eventbridge-scheduler:bootRun
./gradlew :aws-sqs-sns-coroutines:bootRun
./gradlew :aws-kinesis-coroutines:bootRun
./gradlew :aws-s3-vectors-access-grants:bootRun
./gradlew :aws-bedrock-converse:run
./gradlew :aws-settings-boundary:run
```

Kinesis sample은 credential-free `local` profile을 기본으로 사용하고 deterministic record 3개를
publish/consume한 뒤 정상 종료합니다. 실제 AWS는 비용이 발생할 수 있는 명시적 opt-in입니다.

local 실행이 성공하면 비밀값을 제거한
`Kinesis demo completed: publishedCount=3, consumedCount=3, sequenceCount=3` 요약을 출력하고
exit code `0`으로 종료합니다.

```bash
AWS_REGION=ap-northeast-2 \
  ./gradlew :aws-kinesis-coroutines:bootRun \
  --args='--spring.profiles.active=real-aws --kinesis.workshop.run-demo=true'
```

실행 전 표준 AWS credential provider를 구성하고 고유한 stream, partition key, shard를 사용합니다.
lesson에 필요한 최소 IAM action은 `CreateStream`, `DescribeStream`, `PutRecord`, `GetShardIterator`,
`GetRecords`입니다. sample은 stream을 자동 삭제하지 않습니다. lesson 후 다음 명령으로 직접
삭제하여 비용이 남지 않게 정리합니다.

```bash
aws kinesis delete-stream --stream-name "$KINESIS_WORKSHOP_STREAM_NAME"
```

credential, endpoint, payload, partition key는 log/report에 기록하지 않습니다. ordering은
partition key/shard 범위에 한정되며 exactly-once/global ordering을 주장하지 않습니다.

## 전제 조건

| item | requirement |
| --- | --- |
| JDK | Java 25. |
| Docker | 에뮬레이터 기반 테스트와 S3 샘플 실행에 필요합니다. |
| AWS account | 로컬 워크숍 경로에는 필요하지 않습니다. 실제 Kinesis, EventBridge/Scheduler, CloudWatch/IMDS, S3 Vectors/Access Grants 동작은 수동 opt-in입니다. |
