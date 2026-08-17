# Issue #743: AWS 0.5.0 Kinesis coroutine operations reference 설계

## 문제와 목표

현재 workshop에는 SQS/SNS와 EventBridge 예제는 있지만 Kinesis producer/consumer
참조 구현이 없다. Issue #743의 목표는 AWS 0.5.0이 제공하는 Spring Boot Kinesis
자동 구성과 coroutine operations를 실제 학습 흐름으로 연결하는 것이다.

이번 변경은 `aws/kinesis-coroutines` 모듈을 추가한다. 하나의 고정된 shard를
기준으로 producer가 partition key와 JSON event를 기록하고, consumer가
`KinesisOperations.recordFlow`를 통해 sequence 순서를 관찰한다. 기본 실행과
기본 테스트는 AWS credential 없이 동작해야 하며, 실제 AWS 호출은 `real-aws`
profile을 명시적으로 선택한 경우에만 활성화된다.

### 성공 조건

- stream 생성, record publish, shard consumer 종료까지의 최소 lifecycle을 실행한다.
- 동일 partition key의 sequence 순서를 테스트에서 관찰한다.
- `Flow` 수집자가 `take` 또는 job cancellation으로 중단될 때 polling 작업과
  collector가 quiescent 상태가 된다.
- transient Kinesis 오류는 AWS 0.5.0 `KinesisOperations.recordFlow`와
  `KinesisCoroutinesTemplate`의 retry 경계에서 제한된 횟수만 재시도하고,
  한도를 넘으면 원래 오류를 전파한다.
- AWS polling은 shard당 `pollInterval >= 200ms`, 빈 응답 backoff는
  양수, record batch와 payload 보유량은 고정된 상한을 지킨다.
- 기본 test에는 AWS credential이 필요하지 않다.
- 기본 Spring context에는 `KinesisAsyncClient`와 기본 credential provider가
  생성되지 않으며, `real-aws`만 이 경계를 opt-in으로 연다.
- endpoint, payload, partition key, credential이 로그·report·test artifact에
  비밀값 또는 raw payload로 노출되지 않는다.
- module registry, AWS smoke/full workflow, validation/stale-check, 한·영 README가
  같은 변경에 포함된다.

Exactly-once, 여러 shard 사이의 global ordering, consumer group/lease/checkpoint
조정은 주장하거나 구현하지 않는다.

## 현재 근거와 계약

| 근거 | 확인한 사실 | 설계에 반영한 결정 |
| --- | --- | --- |
| Issue #743 live state | Issue가 OPEN이며 milestone `1.4.0`, assignee `debop`, AWS 0.5.0 Kinesis reference와 credential-free test를 요구한다. | 새 모듈과 등록·문서·검증 surface를 하나의 Type-A 변경으로 묶는다. |
| `aws/sqs-sns-coroutines` | Spring Boot 모듈, `libs.bluetape4k.aws`, in-memory fallback, MockK/JUnit test, Floci 통합 패턴을 사용한다. | 같은 Spring Boot 모듈 구조와 conditional local fallback을 재사용하되, Kinesis는 기본 AWS auto-configuration을 명시적으로 끈다. |
| `settings.gradle.kts` | `includeModules("aws", false, true)`가 `aws/*` 디렉터리를 자동 등록한다. | 수동 `include`를 추가하지 않고 디렉터리와 Gradle module name을 검증한다. |
| `build.gradle.kts:238` | root가 AWS SDK v2 BOM을 import한다. | AWS Kinesis alias는 versionless로만 추가한다. |
| upstream `bluetape4k-aws` tag `0.5.0` | `KinesisOperations`, `KinesisCoroutinesTemplate`, `KinesisRecordFlowRequest`, `KinesisStartingPosition`, `KinesisRecordFlowOptions`가 Spring Boot artifact에 있다. | workshop 코드는 이 public contract를 직접 사용하고 내부 SDK wrapper를 복제하지 않는다. |
| upstream Kinesis contract | `KinesisOperations.recordFlow(KinesisRecordFlowRequest)`는 single-shard cold `Flow`이며 `CancellationException`을 재전파하고, iterator expiration과 retryable `KinesisException`을 제한된 횟수만 재시도한다. | cancellation을 catch-and-convert하지 않고, retry assertion은 `KinesisCoroutinesTemplate`의 실제 Spring public contract를 대상으로 한다. |

외부 API 근거는 [`bluetape4k-aws` 0.5.0 Kinesis source](https://github.com/bluetape4k/bluetape4k-aws/tree/0.5.0/aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/kinesis)와
[0.5.0 release](https://github.com/bluetape4k/bluetape4k-aws/releases/tag/0.5.0)로 고정한다.

## 선택지와 결정

### A. Spring Boot `KinesisOperations` (선택)

기존 `sqs-sns-coroutines`와 동일하게 Spring Boot application, Jackson 3 mapper,
`@ConfigurationProperties`, `@ConditionalOnMissingBean` local adapter를 둔다. 활성
Spring profile을 backend 선택의 SSOT로 삼는다. 기본 profile(`local`)은
`bluetape4k.aws.enabled=false`를 명시해 upstream `KinesisAutoConfiguration`과
기본 `DefaultCredentialsProvider`를 비활성화하고 `LocalKinesisOperations`만
등록한다. `real-aws` profile에서만 `bluetape4k.aws.enabled=true`를 명시해
`KinesisCoroutinesTemplate` 자동 구성을 주입하며, profile과 설정이 불일치하면
startup에서 실패하고 local fake로 조용히 fallback하지 않는다.

장점은 기존 학습 경계·Gradle alias·README·workflow 패턴을 재사용하고, upstream
Spring Boot auto-configuration의 region/endpoint와 consumer 옵션을 그대로
설명할 수 있다는 점이다. 단점은 fake가 Kinesis response 모델을 만들어야 한다는
점이지만, fake의 범위를 단일 stream/단일 shard로 제한해 AWS 전체 semantics를
흉내 내지 않는다.

### B. Ktor + AWS SDK Kotlin `KinesisRecordFlow`

Ktor route와 `aws-kotlin`의 `KinesisRecordFlow`를 직접 조합한다. Kotlin SDK의
Flow와 Ktor lifecycle을 보여줄 수 있지만, 현재 이 issue의 reference 대상인
Spring Boot auto-configuration과 별도 credential/endpoint 설정을 추가해야 한다.
기존 SQS/SNS coroutine 예제와 학습 경로도 분리된다.

### C. Java SDK v2 extension만 사용하는 얇은 예제

`KinesisAsyncClient`와 coroutine extension만 직접 호출한다. 의존성 표면은
작지만 구성 바인딩, stream defaults, bounded record Flow를 다시 구현하게 되어
upstream 0.5.0 Spring contract의 검증 가치가 줄어든다.

**결정:** A를 선택한다. B와 C는 이번 issue에서는 제외하고, 필요하면 별도 issue로
분리한다.

## 구성과 컴포넌트

### Gradle과 외부 경계

- 새 디렉터리: `aws/kinesis-coroutines` → Gradle project `:aws-kinesis-coroutines`.
- `libs.versions.toml`에 `aws2-kinesis-lib = { module = "software.amazon.awssdk:kinesis" }`
  alias를 추가한다. 버전은 선언하지 않고 root AWS BOM을 단일 source of truth로
  사용한다.
- `libs.bluetape4k.aws`, core/coroutines/jackson/logging, Spring Boot web/actuator,
  `aws2.kinesis.lib`를 기존 AWS Spring 모듈처럼 사용한다.
- `local`과 `real-aws`의 선택은 profile만 사용한다. `real-aws`는 endpoint override
  없이 region/credential provider를 사용하는 것이 기본이며, endpoint를 명시할
  때는 `http`/`https`의 loopback 또는 고정된 Docker service host(`localstack`,
  `kinesis`)만 허용하고 URI user-info와 link-local/private 임의 host를 거부한다.
- default `bootRun`은 local adapter를 사용하고 `management.endpoints.web.exposure.include`
  기본값은 `health,info,metrics`로 제한한다. `real-aws` profile 선택과 credential
  공급은 README에서 정확한 실행 명령과 필수값을 포함한 opt-in 절차로만 설명한다.

profile과 실행 계약은 다음 표를 single source of truth로 삼아 한·영 README에
동일하게 복사한다.

| profile | 필수 설정 | 예상 bean/동작 | 실행 명령 |
| --- | --- | --- | --- |
| `local` (기본) | 없음(기본 stream/partition/shard 사용), `run-demo=true` | `LocalKinesisOperations`, credential resolution 없음 | `./gradlew :aws-kinesis-coroutines:bootRun` |
| `real-aws` | `AWS_REGION`, 표준 credential provider, 고유 `kinesis.workshop.stream-name`, `partition-key`, `shard-id`, `run-demo=false`(기본) | upstream `KinesisCoroutinesTemplate`, 설정 오류 또는 AWS 연결 실패 시 startup/demo 실패, local fallback 없음 | 실제 변경을 명시적으로 승인한 경우에만 `./gradlew :aws-kinesis-coroutines:bootRun --args='--spring.profiles.active=real-aws --kinesis.workshop.run-demo=true'` |

`real-aws` demo는 다음 최소 IAM action만 요구한다: `kinesis:CreateStream`,
`kinesis:DescribeStream`, `kinesis:PutRecord`, `kinesis:GetShardIterator`,
`kinesis:GetRecords`. stream은 자동 삭제하지 않으며 과금과 실제 AWS 변경을
README에서 경고한다. 고유 stream 이름을 사용하고, 명시적 cleanup은
`aws kinesis delete-stream --stream-name "$KINESIS_WORKSHOP_STREAM_NAME"`로
수행한다(삭제 권한은 기본 demo role에 포함하지 않는다).

### 애플리케이션 컴포넌트

1. `KinesisCoroutinesApplication`: Spring Boot 진입점과 workshop properties 등록.
2. `KinesisWorkshopProperties`: stream name, partition key, shard id, consumer
   batch/poll/backoff 값을 immutable configuration으로 보유하고 blank/범위 값을
   조기 거부한다. endpoint URI, `batchLimit <= 1_000`,
   `pollInterval >= 200ms`, `emptyBackoff > 0`, aggregate payload `<= 1MiB`를
   고정 검증한다. upstream `bluetape4k.aws.kinesis.*` consumer properties와
   workshop sample의 입력을 분리한다.
3. `KinesisEvent`와 publish/consume report model: event id, partition key, ordinal,
   payload, sequence number를 표현한다. data class는 `Serializable`과
   `serialVersionUID`를 갖는다.
4. `KinesisStreamService`: `KinesisOperations.createStream(streamName, 1)`을
   `describeStream`/`ResourceNotFoundException` 확인과 함께 idempotent `ensureStream`
   흐름으로 감싸고, record publish·single shard consume를 위임하며 `JsonMapper`로
   event를 encode/decode한다. `Flow`를
   eager list로 바꾸지 않아 collector backpressure를 보존한다. detached job을
   만들지 않는다. Spring context가 외부 `KinesisAsyncClient`를 소유하고 shutdown
   시 close한다. collector cancellation은 pending future와 polling job만 취소하고
   공유 client를 닫지 않는다. `CancellationException`은 다시 던지고, transient
   retry는 upstream `recordFlow`가 소유한다.
   각 collector의 시작/완료를 lightweight active-collector registry로 추적하되
   job을 소유하거나 취소하지 않는다. context shutdown은 registry가 0이 될 때까지
   caller-owned collector의 drain/cancel을 기다린 뒤에만 shared client close를
   허용한다.
   `ensureStream`은 `describeStream` 후 없는 경우에만 `createStream(streamName, 1)`을
   호출하고, 이미 존재하는 stream은 재실행 시 재사용한다. create 응답 뒤에는 최대
   30초 동안 250ms 간격으로 `describeStream`을 polling해 `ACTIVE` 상태를 확인하며,
   timeout/cancellation은 원본 오류로 전파한다. local fake는 즉시 `ACTIVE`다.
5. `KinesisDemoRunner`: `ApplicationRunner`로 stream create → 세 record publish →
   같은 shard `take(3)` consume을 실행한다. `kinesis.workshop.run-demo=true`일
   때만 동작하고, 출력에는 sequence와 개수만 포함해 payload/partition key를
   노출하지 않는다. 기본 `bootRun`은 local profile에서 이 runner를 실행하고
   정상 종료한다.
6. `KinesisDemoScope`: `SupervisorJob` 기반의 app-owned `CoroutineScope`와
   demo `Job` registry를 bean으로 제공한다. `KinesisDemoRunner`만 이 scope에
   job을 등록하며, `KinesisStreamService`의 일반 `Flow` collector는 caller-owned
   job으로 남겨 detached job을 만들지 않는다.
7. `LocalKinesisConfig`와 `LocalKinesisOperations`: `local` profile에서 실제
   `KinesisOperations` bean이 없을 때 등록한다. 하나의 configured stream과
   `shardId-000000000000` shard를
   사용하며, append-only sequence와 iterator index로 deterministic fake를 만든다.
   `getRecords`는 요청 limit만큼만 emit해 prefetch를 하지 않는다.
7. `KinesisWorkshopMetrics`와 `KinesisWorkshopHealthIndicator`: 허용된 metric
   이름은 `kinesis.workshop.publish`, `.consume`, `.retry`, `.failure`이고 tag는
   `backend`, `operation`, `outcome`만 사용한다. health/readiness는 local에서는
   `UP`, real-aws demo 전에는 `UNKNOWN`, terminal AWS/iterator failure 뒤에는
   `DOWN`으로 보고하며 stream name, endpoint, credential, payload를 detail/tag에
   넣지 않는다.
9. `KinesisShutdownConfiguration`: `spring.lifecycle.timeout-per-shutdown-phase=10s`를
   적용한다. shutdown은 app-owned demo scope/job 취소 → caller-owned collector가
   drain/cancel되어 active registry가 0이 될 때까지 대기 → pending future 취소 →
   Spring이 owned client를 close하는 순서를 따른다. caller-owned collector를
   임의로 취소하지 않으며, 10초 안에 0이 되지 않으면 client close 전에 safe
   failure metric/log와 shutdown failure를 남겨 use-after-close를 방지한다.

### 데이터 흐름

```text
KinesisEvent
    -> JsonMapper.writeValueAsString
    -> SdkBytes + KinesisPutRecordRequest(partitionKey)
    -> KinesisOperations.putRecord
    -> sequence number

KinesisOperations.recordFlow(single shard)
    -> cold Flow<Record>
    -> collector-controlled map/decode
    -> KinesisEvent + observed sequence
```

Local fake와 upstream template 모두 동일한 service interface를 사용하므로 기본
테스트가 외부 credential에 묶이지 않는다. local fake는 background thread/job을
만들지 않으며, 외부 template의 pending SDK future는 collector cancellation에
맞춰 취소되어야 한다. `ensureStream`은 `describeStream` 후 없는 경우에만
`createStream(streamName, 1)`을 호출하고, 이미 존재하는 stream은 재실행 시
재사용한다. 실제 AWS/Kinesis endpoint 검증은 이 issue에서 기본 DoD가 아니며,
필요하면 별도 opt-in integration task로 실행한다.

## 실패·수명주기 계약

1. **blank 또는 범위를 벗어난 구성:** stream name, partition key, shard id가
   blank이거나 batch limit이 범위를 벗어나면 `IllegalArgumentException`으로
   application/test 초기 단계에서 거부한다.
2. **transient Kinesis 오류:** iterator expiration과 throttle retry는 서로 다른
   예산(`maxIteratorRetries`, `maxThrottleRetries`)으로 제한한다. throttle 재시도는
   `isThrottlingException`인 경우만 허용하고, 일반 `KinesisException`은 원본 예외로
   즉시 전파한다. 이미 emit한 마지막 sequence가 있으면 iterator expiration 뒤
   `AfterSequenceNumber(lastSeen)`으로 재개한다. `Latest` position에서 lastSeen이
   없으면 조용한 유실을 피하기 위해 실패하고, `TrimHorizon`·timestamp·명시적
   sequence position은 같은 position으로 iterator를 재생성한다. 직접
   `KinesisRecordFlowOptions`를 주입하는 contract test에서는
   각 예산 0을 허용해 즉시 실패하는 경계를 검증하고, Spring `KinesisProperties`
   binding과 workshop properties에서는 upstream의 `>= 1` 제약을 그대로 거부한다.
   한도 초과 원본 오류도 테스트한다.
3. **collector cancellation:** service는 broad exception 처리로 cancellation을
   report로 바꾸지 않는다. collector가 cancel되면 cold Flow와 fake/template
   polling이 종료되고, pending `getRecords` future가 취소되며 추가 API 호출이
   0건이어야 한다. 테스트는 `cancelAndJoin` 또는 `take` 이후 active job 0과
   공유 client가 여전히 사용 가능함을 확인하고, 별도 context shutdown 테스트에서
   owned client close와 cleanup을 검증한다.
4. **empty poll/backpressure:** empty response는 configured empty backoff를
   사용하고, local fake는 다음 record를 미리 읽지 않는다. AWS 경로의
   `pollInterval`은 최소 200ms, `emptyBackoff`는 양수이며 연속 실패 episode별
   backoff·호출 횟수는 옵션 상한을 넘지 않는다. 성공적인 `getRecords` 뒤 upstream
   counter가 reset되는 동작을 보존한다. `take(n)`이 n개 뒤에
   즉시 종료되는지와 slow collector가 한 batch 상한 이상을 보유하지 않는지
   테스트한다.
5. **비지원 ordering 주장:** 같은 shard에서 같은 partition key의 append 순서는
   테스트로 관찰하지만, 서로 다른 shard 또는 producer 간 global ordering은
   보장하지 않는다고 README와 KDoc에 명시한다.
6. **외부 endpoint 장애와 비밀값:** 기본 fake에는 AWS endpoint가 필요 없고,
   upstream client 예외는 원형을 보존해 호출자에게 전달한다. credential을 코드나
   test resource에 저장하지 않는다. endpoint의 user-info·access key·secret key·
   session token·raw payload·partition key는 로그, HTTP report, actuator, test
   artifact에 기록하지 않으며, 허용된 구조화 필드만 남긴다.
7. **profile과 운영 가시성:** `local` profile에서 upstream bean/credential
   resolution을 만들지 않는다. `real-aws`의 필수값 누락·bean 생성 실패·endpoint
   연결 실패는 startup 또는 demo를 실패시키며 local fake로 전환하지 않는다.
   health/readiness와 metric은 위에서 고정한 상태·이름·tag allowlist만 사용한다.
8. **graceful shutdown:** context stop은 `KinesisDemoScope`의 app-owned job을
   취소하고 active-collector registry가 0이 될 때까지 caller-owned 일반 collector의
   drain/cancel을 기다린 뒤 pending future와 owned client를 정리한다. caller-owned
   collector를 임의로 취소하지 않으며, 10초 timeout 초과는 client close 전에
   failure metric과 안전한 구조화 로그로 관찰하고 use-after-close를 방지한다.

## 테스트 설계

### Credential-free unit/contract tests

- `KinesisWorkshopPropertiesTest`: 기본 profile의 기본값, endpoint URI,
  blank/negative/batch limit, `pollInterval >= 200ms`, `emptyBackoff > 0`, aggregate
  payload `<= 1MiB` validation과 real-aws 필수값 누락/불일치 fail-fast를 검증한다.
- `LocalKinesisOperationsTest`: partition key가 보존되고 같은 shard의
  sequence가 append 순서로 반환되는지, `limit`이 prefetch 없이 적용되는지 검증.
- `KinesisStreamServiceTest`: `describeStream` 후 `createStream(streamName, 1)`을 호출하는
  `ensureStream` 매핑, JSON payload와 partition key mapping, publish report, `take`
  기반 backpressure, batch/payload 상한, cancellation 이후 active job 0과 공유
  client 미종료를 검증한다. `CREATING→ACTIVE`, 30초 timeout, polling 중
  `CancellationException` 재전파와 추가 describe 0건, `DELETING` 등 terminal status
  즉시 실패를 deterministic fake clock으로 검증한다.
- `KinesisCoroutinesTemplateContractTest`: MockK `KinesisAsyncClient`로
  `ProvisionedThroughputExceededException` 한 번 뒤 성공하는 throttle retry,
  non-throttling 예외 전파, `Latest` no-last 실패와 `TrimHorizon`/timestamp/
  explicit-sequence no-last 재시도, `ExpiredIteratorException`의 last-sequence
  재개와 retry=0 경계, 성공 후 독립 retry episode reset, capped backoff/jitter=0,
  backoff 중 cancellation을 검증한다.
  pending `CompletableFuture`가 취소되고 추가 API 호출이 0건인지 실제 SDK
  response builder와 함께 검증해 테스트 double이 coroutine 경계를 우회하지 않게
  한다.
- `KinesisAutoConfigurationTest`: 기본 profile context에는
  `KinesisAsyncClient`/기본 credential provider가 없고 local fake가 등록되며,
  `real-aws` profile에서만 upstream template이 선택되고 custom
  `KinesisOperations`가 있으면 fake가 back off하는지 검증한다. context shutdown
  뒤 owned client close와 actuator exposure, secret-like payload/credential/
  endpoint user-info의 로그·report·test artifact 비노출도 고정한다.
- `KinesisDemoRunnerTest`: local application-level runner가 create → publish →
  consume을 실제로 완료하고 sequence/count만 출력하며, `run-demo=false`에서는
  호출하지 않는지 검증한다. `KinesisDemoScope`에 등록된 app-owned job만 shutdown
  시 취소되고 일반 caller-owned collector는 유지되는지 검증한다. active caller
  collector가 남아 있으면 shutdown이 client close를 먼저 호출하지 않고, collector
  완료 뒤에만 close하는 순서를 concurrent test로 고정한다.
- `KinesisWorkshopOperationsTest`: health/readiness 상태 전이, metric 이름과
  `backend/operation/outcome` tag allowlist, retry exhaustion·iterator failure·
  cancellation·shutdown timeout 관측과 active-collector registry drain/timeout
  경계를 검증한다.
- 모든 retry/cancellation 테스트는 `kotlinx-coroutines-test` virtual time을
  사용하고 `jitterRatio=0` 또는 주입 가능한 deterministic backoff를 사용해
  wall-clock/flaky 검증을 피한다.

### 통합 범위

Floci/Kinesis capability가 현재 fixture에서 안정적으로 확인되면 opt-in
`KinesisFlociIntegrationTest`를 추가한다. capability가 없거나 Docker가 없는
기본 환경에서는 이 테스트를 실행하지 않는다. 기본 DoD는 deterministic fake와
MockK contract test로 충족하며, live AWS smoke는 추가하지 않는다.

## 등록·문서·검증 surface

- `settings.gradle.kts` 자동 include 결과와 `./gradlew projects`에서
  `:aws-kinesis-coroutines`를 확인한다.
- `.github/workflows/Examples.yml`의 push/PR path filters, smoke/full Gradle task,
  test-result artifact 경로를 새 모듈에 맞게 갱신한다.
- `scripts/smoke-validate.sh`의 `all-smoke`, `observability`/`aws` 관련 목록과
  `stale-check` 결과를 갱신한다.
- `aws/README.md`, `aws/README.ko.md`에 동일한 module guide, local-first 실행,
  Kinesis ordering/cancellation/retry 경계를 추가한다. 두 README와
  `aws/kinesis-coroutines` 안의 README에 profile 표, 정확한 local/real-aws 실행
  명령, 예상 sequence/count 출력, 종료 방법을 동일하게 맞춘다. 또한 real-aws의
  최소 IAM actions, 비용·실제 변경 경고, 고유 stream 이름, 비자동 삭제 정책,
  명시적 cleanup 명령을 세 README에서 동일하게 유지한다.
- 코드의 public KDoc은 한국어로 작성하고, source/API/README의 이름과 config key를
  traceability 표로 검증한다.

## 수용 기준과 DoD

| ID | 수용 기준 | 검증 증거 |
| --- | --- | --- |
| AC-01 | application runner가 stream create → publish → consume lifecycle을 실제로 동작시킨다. | `KinesisDemoRunnerTest`와 local `bootRun` smoke의 sequence/count 출력 |
| AC-02 | partition key와 같은 shard sequence order가 관찰된다. | local operations/service assertions |
| AC-03 | `take`/cancellation 뒤 collector와 pending future가 quiescent하고 공유 client는 유지되며, 별도 shutdown에서 owned client가 close된다. | pending-future cancellation, active-job-0, context-shutdown cleanup contract tests |
| AC-04 | iterator/throttle retry가 분리된 예산과 backoff 상한을 지키고 원래 오류가 보존된다. | MockK async-client contract tests with virtual time |
| AC-05 | credential-free 기본 context에는 AWS client/credential resolution이 없고 기본 test가 통과한다. | default-profile context test, `:aws-kinesis-coroutines:test`, credential 검색 |
| AC-06 | versionless SDK alias가 root AWS BOM을 따른다. | catalog/build dependency report |
| AC-07 | registry, workflow, smoke/stale validation, profile 표·실행 명령·출력·종료와 real-aws IAM/비용/stream cleanup 안전 안내를 포함한 한·영 README가 일치한다. | path/task/link/parity/README command-table and safety-guidance checks |
| AC-08 | exactly-once/global ordering을 주장하지 않는다. | KDoc/README/spec review |
| AC-09 | endpoint·payload·partition key·credential이 로그·report·test artifact에 노출되지 않는다. | URI validation, actuator/log/report redaction regression tests |

### 완료 조건

- 승인된 spec과 plan이 feature branch에 commit되어 있다.
- targeted test, module test, `git diff --check`, `./gradlew projects`, smoke/stale
  검사가 fresh PASS다.
- Type-A review에서 P0/P1이 0이고, lesson과 PR body의 `## DoD Status`가 실제
  증거를 반영한다.
- PR은 Issue #743의 milestone/assignee/labels를 미러링하고, merge는 별도 fresh
  approval 뒤에만 실행한다.

## 제외 및 rollback

- Ktor variant, multi-shard coordination, exactly-once/idempotent sink,
  checkpoint store, live AWS credential smoke는 이번 변경에서 제외한다.
- upstream API 또는 local fake의 compile/test contract가 맞지 않으면 feature
  worktree에서 해당 모듈과 문서만 되돌리고 Issue #743을 미완료 상태로 유지한다.
- dependency alias가 BOM에서 해석되지 않으면 alias를 추가하지 않은 채 구현을
  진행하지 않고, `bluetape4k-dependencies` 또는 AWS BOM source를 먼저 확인한다.
