# AWS 0.5.0 Kinesis 코루틴 워크숍 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Issue #743의 요구사항을 만족하는 Spring Boot 기반 Kinesis 코루틴 producer/consumer 워크숍 모듈을 추가한다. 기본 실행은 credential-free deterministic fake를 사용하고, `real-aws` profile에서만 upstream AWS SDK v2 `KinesisOperations` 구현을 명시적으로 활성화한다.

**Architecture:** `aws/kinesis-coroutines`에 애플리케이션·설정·서비스·local fake·관측성·shutdown 경계를 둔다. AWS SDK v2 Kinesis alias는 root AWS BOM 아래 versionless catalog alias로 추가한다. 모듈은 upstream `KinesisOperations`/`KinesisCoroutinesTemplate` 계약을 직접 재구현하지 않고, 서비스가 `KinesisOperations`를 소비하며 local profile은 같은 인터페이스를 deterministic fake로 제공한다. collector는 caller-owned cold `Flow`이고, demo runner만 app-owned `SupervisorJob` registry에 등록한다.

**Tech Stack:** Kotlin 2.4, Java 25, Spring Boot 4.0.6, bluetape4k AWS Spring Boot 1.7.0, AWS SDK v2 Kinesis, kotlinx.coroutines, Jackson 3, Micrometer, Spring Actuator, JUnit 5, MockK, Kotlin coroutine test.

---

## 파일 지도

### 추가

| 경로 | 책임 |
| --- | --- |
| `aws/kinesis-coroutines/build.gradle.kts` | Spring Boot 실행 모듈, AWS Kinesis versionless alias, 테스트 의존성 |
| `aws/kinesis-coroutines/src/main/kotlin/io/bluetape4k/workshop/aws/kinesis/KinesisCoroutinesApplication.kt` | 애플리케이션 진입점 및 configuration properties scan |
| `.../KinesisWorkshopProperties.kt` | `kinesis.workshop` profile/stream/consumer/run-demo/timeout 설정과 경계 검증 |
| `.../KinesisWorkshopModels.kt` | publish/consume 학습용 event·report DTO, `Serializable` 계약 |
| `.../KinesisStreamService.kt` | stream ensure/readiness, JSON publish, shard flow consume, cancellation 및 registry 연결 |
| `.../KinesisDemoScope.kt` | app-owned `SupervisorJob`, 등록 job registry, caller-owned collector completion registry와 passive `awaitEmpty` API |
| `.../KinesisDemoRunner.kt` | `run-demo` opt-in runner, 3개 record publish/consume, redacted summary 출력 |
| `.../LocalKinesisOperations.kt` | `local` profile deterministic in-memory `KinesisOperations` 구현 |
| `.../LocalKinesisConfiguration.kt` | local profile에서만 fake를 제공하고 real AWS bean을 대체하지 않는 구성 |
| `.../RealAwsKinesisConfiguration.kt` | `real-aws` profile fail-fast 검증 및 upstream `KinesisOperations` 주입 확인 |
| `.../KinesisWorkshopMetrics.kt` | 허용된 metric 이름·tag만 기록하는 Micrometer facade |
| `.../KinesisWorkshopHealthIndicator.kt` | stream/client/active collector 상태를 비밀값 없이 Actuator health로 노출 |
| `.../KinesisShutdownConfiguration.kt` | 10초 bounded shutdown: app job → passive collector await → futures/client close |
| `aws/kinesis-coroutines/src/main/resources/application.yml` | default `local`, credential-free, run-demo 및 actuator 기본값 |
| `aws/kinesis-coroutines/src/main/resources/application-real-aws.yml` | 명시적 AWS client enable, run-demo false, 안전한 실환경 기본값 |
| `aws/kinesis-coroutines/README.md` | 모듈 단독 실행자를 위한 profile 표·명령·출력·종료·AWS 안전 경계 |
| `aws/kinesis-coroutines/README.ko.md` | 모듈 단독 실행자를 위한 한국어 profile 표·명령·출력·종료·AWS 안전 경계 |
| `aws/kinesis-coroutines/src/test/.../KinesisWorkshopPropertiesTest.kt` | 속성 기본값·경계·payload/partition redaction 테스트 |
| `.../LocalKinesisOperationsTest.kt` | fake stream/sequence/order/cold flow/cancellation 테스트 |
| `.../KinesisStreamServiceTest.kt` | ensure/readiness/publish/consume/error/cancellation 테스트 |
| `.../KinesisCoroutinesTemplateContractTest.kt` | upstream template MockK contract 및 retry/cancellation 테스트 |
| `.../KinesisAutoConfigurationTest.kt` | profile별 bean graph, credential-free default, metrics/health/shutdown 테스트 |
| `.../KinesisDemoRunnerTest.kt` | runner 3-record flow, safe output, opt-in 조건 테스트 |
| `.../KinesisWorkshopOperationsTest.kt` | local Spring context smoke 및 profile lifecycle 테스트 |
| `aws/kinesis-coroutines/src/test/resources/application-test.yml` | test profile의 deterministic fake 및 run-demo false 설정 |

### 수정

| 경로 | 수정 내용 |
| --- | --- |
| `gradle/libs.versions.toml` | `aws2-kinesis-lib = { module = "software.amazon.awssdk:kinesis" }` alias 추가; 버전은 BOM에 위임 |
| `aws/README.md` | 모듈 가이드·profile 표·실행·IAM/비용/정리 절차·coverage 문서 추가 |
| `aws/README.ko.md` | 동일 내용을 한국어로 동기화 |
| `.github/workflows/Examples.yml` | path filter, smoke/full task, test artifact 목록에 `aws-kinesis-coroutines` 추가 |
| `scripts/smoke-validate.sh` | AWS smoke group에 `:aws-kinesis-coroutines:test` 추가 |
| `docs/lessons-learned.md` 또는 저장소의 AWS lesson index | local-first/Kinesis cancellation·retry·ordering 학습 포인트를 기존 위치에 추가 |

### 계획/증거

| 경로 | 수정 내용 |
| --- | --- |
| `docs/superpowers/specs/2026-08-17-issue-743-kinesis-coroutines-design.md` | 승인된 설계의 구현 결과 링크·검증 증거 갱신 |
| `docs/superpowers/plans/2026-08-17-issue-743-kinesis-coroutines.md` | 이 구현 계획과 task 체크 상태 갱신 |
| `docs/review/2026-08-17-issue-743-*.md` | 계획/구현 독립 리뷰 결과와 P0~P3 상태 기록 |

## 구현 순서

### Task 1: 계획·workflow topology 고정

- [x] 최신 Issue #743, milestone `1.4.0`, assignee `debop`, labels를 다시 읽고 승인된 spec/plan 경로와 현재 branch head를 기록한다.
- [x] workflow helper의 `mutation-check`를 통과한 뒤 `implementation-main` lane을 생성·시작·startup-ack하고, write scope를 위 파일 지도에 고정한다.
- [x] 계획 문서에 변경하지 않는 범위(정확히 한 Kinesis module, AWS SDK v2, no exactly-once/global-ordering claim)를 확인한다.
- [x] 명령: `git status --short`, `git branch --show-current`, `gh issue view 743 --repo bluetape4k/bluetape4k-workshop --json number,state,milestone,assignees,labels`.
- [x] 기대 결과: branch가 `feat/issue-743-kinesis-coroutines`, worktree가 clean(계획/리뷰 증거 제외), Issue가 OPEN/`1.4.0`/`debop`이고 범위 밖 파일이 없다.

### Task 2: Gradle module과 profile 리소스의 RED 테스트 준비

- [x] `aws/sqs-sns-coroutines/build.gradle.kts`와 root BOM 패턴을 복사하지 말고 필요한 alias만 확인해 `build.gradle.kts`와 `libs.versions.toml`을 먼저 추가한다(구성 파일은 TDD 예외).
- [x] main application/resource skeleton과 test resource를 추가하되 production behavior는 아직 작성하지 않는다.
- [x] `KinesisWorkshopPropertiesTest`에서 default local, `pollInterval >= 200ms`, `emptyBackoff > 0`, `batchLimit <= 1_000`, aggregate payload `<= 1MiB`, run-demo profile 기본값, redaction을 먼저 작성한다. endpoint는 미지정·loopback·허용된 `localstack`/`kinesis` host만 허용하고 user-info, 비 HTTP(S), `169.254.169.254`, 임의 RFC1918/private host는 거부하며 오류에 원 URI를 넣지 않는 표를 고정한다.
- [x] 실행: `./gradlew :aws-kinesis-coroutines:test --tests '*KinesisWorkshopPropertiesTest' --no-daemon`.
- [x] 기대 결과: 테스트가 컴파일된 뒤 새 properties/model 구현이 없어 실패한다. 실패가 compilation/configuration 오류이면 테스트가 behavior RED가 될 때까지 고친다.

### Task 3: 설정·모델·local fake를 RED→GREEN으로 구현

- [x] `KinesisWorkshopProperties.kt`와 `KinesisWorkshopModels.kt`를 구현해 Task 2의 경계 테스트를 통과시킨다. Spring binding 값은 1 이상으로 검증하고, 직접 생성 가능한 low-level flow options는 upstream 계약을 보존한다.
- [x] `LocalKinesisOperationsTest`를 먼저 작성한다: stream create/describe idempotency, deterministic shard `shardId-000000000000`, monotonically increasing sequence, partition key 보존, put/read order, cold flow, collector cancellation을 각각 한 behavior로 검증한다.
- [x] `LocalKinesisOperations.kt`/`LocalKinesisConfiguration.kt`를 구현한다. `local`에서만 fake를 제공하고, background polling job을 만들지 않으며, fake가 공유 client close를 소유하지 않게 한다.
- [x] 실행: `./gradlew :aws-kinesis-coroutines:test --tests '*KinesisWorkshopPropertiesTest' --tests '*LocalKinesisOperationsTest' --no-daemon`.
- [x] 기대 결과: properties와 local fake 테스트가 PASS하고 active job/collector registry가 종료 후 0이다.

### Task 4: stream service와 producer/consumer contract

- [x] `KinesisStreamServiceTest`를 먼저 작성한다: `describe` 후 `ResourceNotFound`에만 create, 250ms 간격 `ACTIVE` readiness 최대 30초, `DELETING`/terminal status 실패, create race 재조회, JSON payload/partition key 생성, 3 record report, cancellation 시 pending future 취소를 검증한다. readiness와 cancellation은 `runTest` virtual time 또는 주입 가능한 clock으로 wall-clock 없이 검증한다.
- [x] `KinesisStreamService.kt`를 구현한다. `KinesisOperations.createStream(streamName, 1)`을 사용하고, `JsonMapper`로 payload를 직렬화하며, 서비스가 생성하는 출력·로그·report·health에는 endpoint/credential/payload/partition key를 포함하지 않는다. upstream 원본 exception은 호출자에게 원형 전파하되 raw message를 관측성 기록에 남기지 않는다.
- [x] `recordFlow`는 cold Flow로 반환하고 `KinesisDemoScope`의 caller-owned active collector completion registry에 등록한다. registry는 caller job을 임의로 취소하지 않고 완료 신호와 `awaitEmpty`만 제공한다. caller cancellation은 `CancellationException`을 그대로 유지하며 shared client는 닫지 않는다.
- [x] 느린 collector가 `batchLimit`보다 많은 record를 소비하는 테스트를 추가한다. eager list/prefetch가 없고 최대 in-flight record/byte가 설정 상한(`batchLimit`, aggregate 1 MiB)을 넘지 않으며 collector 종료 후 active registry가 0인지 측정한다.
- [x] 실행: `./gradlew :aws-kinesis-coroutines:test --tests '*KinesisStreamServiceTest' --no-daemon`.
- [x] 기대 결과: readiness timeout/cancellation/error branch 및 publish/consume contract가 PASS한다.

### Task 5: upstream `KinesisCoroutinesTemplate` contract를 고정

- [x] `KinesisCoroutinesTemplateContractTest`를 먼저 작성한다. 실제 `CompletableFuture`와 MockK를 사용해 suspend bridge, pending future cancellation, `CancellationException` rethrow, `Latest` no-checkpoint 실패, `TrimHorizon`/`AtTimestamp`/`AtSequenceNumber`의 lastSeen 없는 동일 position 재시도, `AfterSequenceNumber` resume, throttle-only retry, non-throttle 원본 전파, successful getRecords episode counter reset, zero retry budget, jitter/backoff envelope를 검증한다.
- [x] retry/readiness 검증은 `runTest`와 `TestCoroutineScheduler` 또는 주입 가능한 backoff clock을 사용한다. `jitterRatio=0` 경로와 deterministic random 정책을 고정해 iterator/throttle episode별 호출 수와 누적 지연 상한을 wall-clock 없이 검증한다.
- [x] 모듈의 `KinesisOperations` 주입 경계에서 upstream `KinesisCoroutinesTemplate`를 사용하도록 `RealAwsKinesisConfiguration.kt`를 구현한다. upstream auto-configuration은 local profile에서 로드되지 않아야 하고, `real-aws`가 아니면 credentials/async client를 만들지 않는다.
- [x] `KinesisCoroutinesTemplateContractTest`는 reflection이 아닌 public contract와 real future completion을 검증하며, no global ordering/exactly-once를 주장하지 않는다. 호출자에게는 원본 exception class/cause를 보존하되, logger/report/health/metric에는 raw exception message·credential·endpoint·payload·partition key를 기록하지 않는 경계를 별도 assertion으로 고정한다.
- [x] 실행: `./gradlew :aws-kinesis-coroutines:test --tests '*KinesisCoroutinesTemplateContractTest' --no-daemon`.
- [x] 기대 결과: 모든 iterator/throttle/cancellation contract가 PASS하고 실패 시 original exception class/message가 보존된다.

### Task 6: runner, scope, observability, shutdown

- [x] `KinesisDemoRunnerTest`와 `KinesisAutoConfigurationTest`를 먼저 작성한다: local default runner 3 records, `real-aws` run-demo false, redacted output, default context에 `KinesisAsyncClient`/credential provider 없음, metrics/health allowlist, `health,info,metrics`만 web exposure되고 `env`, `configprops`, `beans`는 노출되지 않는 context assertion, context close 순서, 10초 shutdown bound를 검증한다. synthetic sentinel endpoint/credential/payload/partition 입력을 넣고 captured log, Actuator health JSON, meter name/tag, test artifact에 원문이 남지 않는지 검증한다.
- [x] local `run-demo=true` 성공 시 `KinesisDemoRunner`가 app-owned job을 완료한 뒤 `ConfigurableApplicationContext`를 정상 종료하고 exit code 0을 반환하는 contract를 먼저 고정한다. test context에서는 close hook을 주입해 자동 종료를 검증하고, `real-aws` 기본 `run-demo=false`에서는 context를 임의로 종료하지 않는다.
- [x] `KinesisDemoScope.kt`와 `KinesisDemoRunner.kt`를 구현한다. runner가 만든 job만 app-owned registry에 등록하고 일반 service 호출자의 coroutine scope는 소유하지 않는다. shutdown은 app-owned job만 취소하고 caller-owned collector에는 cancellation을 주입하지 않는다.
- [x] `KinesisWorkshopMetrics.kt`/`KinesisWorkshopHealthIndicator.kt`를 구현한다. metric 이름과 tag는 상수 allowlist로 제한하고 stream/partition/payload/endpoint/credential을 tag나 health detail에 넣지 않는다.
- [x] `KinesisShutdownConfiguration.kt`를 구현한다. 명시적 `SmartLifecycle` phase/order와 `withTimeout(10.seconds)`를 사용해 순서는 app-owned jobs cancel → caller-owned collector registry의 passive drain 대기 → collector가 소유한 pending future 취소 및 client close로 고정한다. 종료하지 않는 collector를 둔 실제 `ApplicationContext.close()` 테스트에서 10초 내 timeout/failure를 관측하고 `registry=0` 전에는 shared client close가 호출되지 않음을 검증한다. collector가 종료한 뒤에만 client close를 허용한다.
- [x] 실행: `./gradlew :aws-kinesis-coroutines:test --tests '*KinesisDemoRunnerTest' --tests '*KinesisAutoConfigurationTest' --no-daemon`.
- [x] 기대 결과: default context가 credential-free이고, shutdown 중 active collector가 먼저 종료된 뒤 client close가 수행된다.

### Task 7: 통합 smoke와 profile lifecycle

- [x] `KinesisWorkshopOperationsTest`를 먼저 작성한다: local Spring context에서 ensure→publish 3건→consume 보고서를 확인하고 context close 후 active jobs/collectors가 0인지 확인한다. Docker/real AWS 없이 실행한다. Task 7의 전체 module test 결과를 module-test authoritative evidence로 기록한다.
- [x] `aws/kinesis-coroutines/src/test/resources/application-test.yml`을 사용해 테스트가 machine credential과 network endpoint를 요구하지 않도록 한다.
- [x] 실행: `./gradlew :aws-kinesis-coroutines:test --no-daemon --max-workers=1`.
- [x] 기대 결과: module 전체 테스트가 PASS하고 AWS SDK credential resolution이나 외부 네트워크 접근 로그가 없다.

### Task 8: registry·workflow·README·lesson parity

- [x] 모듈 추가에 맞춰 `aws/README.md`, `aws/README.ko.md`, `aws/kinesis-coroutines/README.md`, `aws/kinesis-coroutines/README.ko.md` 네 문서에 module guide, profile table, local/real-aws 명령, 예상 output/정상 종료(exit code 0), IAM 최소 권한, 비용·stream 삭제 경고, explicit cleanup 명령, cancellation/retry/ordering lesson을 같은 사실로 반영한다. endpoint allow/deny와 raw exception redaction 경계도 learner safety 표에 넣는다.
- [x] `.github/workflows/Examples.yml`의 AWS path filters, H2/default smoke task와 full/container task, smoke/full test artifact upload 목록을 모두 갱신한다. `scripts/smoke-validate.sh`의 `all-smoke`, `observability`, `aws`, `stale-check` 각 alias에서 필요한 module/task/artifact parity를 명시적으로 확인하고 AWS·observability 그룹에 `:aws-kinesis-coroutines:test`를 추가한다.
- [x] stale-check/validation matrix가 별도 registry 파일을 요구하는지 `rg -n 'sqs-sns-coroutines|eventbridge-scheduler|validation matrix|stale'`로 확인하고, Gradle project count, README module-link check, required module file check, stale reference check가 Kinesis module을 빠뜨리지 않는지 같은 커밋에서 검증한다.
- [x] 실제 local lifecycle을 검증한다: `./gradlew :aws-kinesis-coroutines:bootRun --no-daemon`을 bounded timeout harness로 실행하고, 3개 sequence/count summary, exit code 0, 잔류 `bootRun`/JVM process 없음, active job/collector 0을 확인한다. `real-aws` 명령은 `AWS_REGION`과 표준 credential provider, 고유 stream/partition/shard, `--spring.profiles.active=real-aws --kinesis.workshop.run-demo=true`를 포함한 copy-paste 절차만 문서와 dry-run assertion으로 검증한다.
- [x] 네 README의 profile 표·실행 명령·expected output·termination·IAM/cost/cleanup/claim tokens가 동일한지 `diff`/`rg` 기반 parity assertion으로 검증한다. `git diff --check`, `bash scripts/smoke-validate.sh aws`를 실행하며, Task 7의 standalone module test를 다시 별도로 실행하지 않고 smoke script 내부의 module invocation을 AWS group contract를 증명하는 단일 group run으로 기록한다.
- [x] 기대 결과: 문서/registry/workflow 경로가 모듈과 일치하고, AWS smoke group 전체가 PASS한다. Docker 미가동으로 실패하면 Colima/docker 상태를 확인하고 원인을 기록하며 skip을 성공으로 취급하지 않는다. group 실행 시간과 worker 수를 evidence에 남긴다.

### Task 9: 전체 검증 및 독립 리뷰

- [x] 구현과 문서가 끝난 뒤 `$verification-before-completion`과 `$requesting-code-review` 규칙에 따라 claim별 검증 명령과 결과를 기록한다.
- [x] targeted module test, AWS smoke, `./gradlew detekt`, 필요한 compile/build를 순서대로 실행하고 실패 시 해당 task로 되돌아간다.
- [x] performance/stability/security/operations/developer/API/user lens를 독립 lane으로 재검토한다. 각 lane은 P0~P3 finding과 변경 경로를 보고하고, P0/P1이 있으면 수정 후 재검토한다.
- [x] `docs/review/2026-08-17-issue-743-implementation-review.md`에 각 lens, 테스트 증거, residual risk, P0~P3 count, 최종 verdict를 기록한다.
- [x] 기대 결과: P0/P1/P2/P3 = 0, `git diff --check` PASS, implementation lane과 모든 review lane이 완료된다.

### Task 10: Issue/PR DoD, merge gate, sync, cleanup

- [ ] Issue #743에 구현 결과·테스트·README/workflow parity를 한국어로 업데이트하고 milestone `1.4.0`, assignee `debop`, 기존 labels를 유지하는지 read-back한다.
- [ ] PR을 `feat/issue-743-kinesis-coroutines` → `develop`으로 생성하고 assignee `debop`, Issue milestone `1.4.0`, Issue labels를 mirror한다. PR body 마지막 heading은 정확히 `## DoD Status`로 두고 reconciled check total, evidence table, final status, unchecked items를 포함한다.
- [ ] PR 생성 후 exact head/base, metadata, linked issue, CI, reviews/threads, mergeability, DoD heading을 다시 읽는다. fresh merge approval 전에는 merge하지 않는다.
- [ ] 별도 명시적 merge 승인을 받은 뒤 exact head를 재확인하고 merge한다. merge SHA와 canonical `develop` sync를 확인한 다음에만 branch/worktree cleanup을 수행한다.
- [ ] cleanup은 `git worktree list`, merged head, dirty status를 확인해 target worktree/branch만 삭제하고 canonical checkout과 unrelated worktree는 건드리지 않는다.
- [ ] 기대 결과: PR DoD가 `DONE`, required checks가 모두 PASS/N/A로 정산되고, merge SHA가 canonical `develop`에 있으며, canonical checkout clean이다.

## TDD 및 검증 규칙

- 각 production Kotlin behavior는 반드시 `RED → 실패 원인 확인 → 최소 GREEN → 전체 관련 테스트 → REFACTOR` 순서로 구현한다. 테스트가 먼저 실패하지 않은 경우 production 코드를 유지하지 않는다.
- 구성 파일·README·workflow·version catalog는 TDD 예외지만, 변경 직후 module compile/smoke와 diff 검사를 실행한다.
- 실제 유효 AWS credential과 외부 endpoint를 테스트에 넣지 않는다. redaction/allowlist 회귀에는 synthetic sentinel 값만 사용한다. `real-aws` 검증은 bean graph/fail-fast contract만 다루며 실제 AWS 호출은 별도 수동 opt-in으로 남긴다.
- `KinesisRecordFlowOptions`의 upstream default와 Spring binding validation을 혼동하지 않는다. direct API는 zero retry budget을 허용할 수 있고, bound properties는 운영 안전 범위를 강제한다.
- 모든 public output과 문서는 한국어로 작성하되 Kotlin API, Gradle task, commands, identifiers, URLs, exact exception names는 그대로 보존한다.

## 완료 기준

- AC-01~AC-09와 Issue #743 DoD가 모두 검증되며, exactly-once/global ordering을 주장하지 않는다.
- 기본 `bootRun`과 module test가 credential-free deterministic fake로 동작한다.
- `real-aws` profile은 명시적 opt-in, credential/region/stream/partition/shard 안전 경고, 최소 IAM 및 explicit cleanup 절차를 제공한다.
- lifecycle은 caller-owned collector와 app-owned runner job을 구분하고, cancellation·retry·shutdown 순서가 테스트로 고정된다.
- README 한·영, validation/stale registry, workflow path/task/artifact, smoke script가 module과 parity를 이룬다.
- 독립 리뷰에서 P0/P1/P2/P3가 모두 0이고, 최종 DoD에는 `Required checks: X/Y; N/A: N; Blocked: N`가 포함된다.
