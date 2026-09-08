# Issues #988~#993 ecosystem 재사용 PR train 구현 계획

> 설계 기준: `docs/superpowers/specs/2026-09-08-issues-988-993-ecosystem-reuse-design.md`

## 실행 원칙

각 이슈를 순차 처리한다. 현재 이슈의 targeted test, 정적 검사, dependency evidence, 독립 코드 리뷰, PR live read-back을 끝낸 뒤 다음 이슈로 이동한다. PR은 생성만 하고 머지하지 않는다. 모든 Gradle 검증은 `--no-daemon --no-build-cache --max-workers=1 --no-parallel`과 명시적 timeout으로 실행한다. 교체 전 baseline이 기대 계약과 다르면 provider 변경을 적용하지 않고 원인을 기록한다.

## Task 0: Ecosystem Reuse Gate 추적 구조 준비

**Files**

- Modify per PR: `docs/ecosystem-reuse-train.json`
- Modify per PR: `docs/ecosystem-reuse-inventory.md`
- Create per PR: `docs/review/2026-09-08-issue-<number>-*.md`
- Create per PR: `docs/lessons/2026-09-08-issue-<number>-*.md`
- Modify: `docs/lessons/README.md`

**Steps**

1. 각 이슈를 `parent_track=P0`인 child scope로 등록하고 `expected_base_ref`, `expected_head_ref`, `allowed_paths`, `gradle_tasks`, `test_selectors`, bounded `gradle_flags`, timeout, Docker 여부, exact `dependency_insight_commands`, review artifact를 고정한다.
2. inventory에는 실제 provider import와 fallback 판정을 이슈별로 추가하고 하나의 manifest track에만 연결한다.
3. 각 PR에서 checker unit test와 ecosystem reuse gate를 exact base/head로 실행한다.
4. coverage matrix는 capability coverage 의미가 달라질 때만 갱신한다. 이 train은 기존 module 안의 raw 구현 이전이므로 변경이 없으면 source-backed N/A를 review artifact에 남긴다.
5. Type A lesson에 BOM/versionless alias, runtime graph, rollback, stacked retarget 경험을 기록하고 `docs/lessons/README.md`에서 연결한다.

## Task 1: #989 usage-billing Tink 기반 PR

**Files**

- Modify: `gradle/libs.versions.toml`
- Modify: `commerce/usage-billing-{billing,invoice,meter,query,usage}-service/build.gradle.kts`
- Modify: 이슈 본문에 열거된 production digest 10개 파일
- Modify: 해당 module unit tests
- Modify: `commerce/usage-billing-{billing,invoice,meter,query,usage}-service/README.md`
- Modify: `commerce/usage-billing-{billing,invoice,meter,query,usage}-service/README.ko.md`
- Modify: `commerce/usage-billing-microservices-composition-tests/README.md`
- Modify: `commerce/usage-billing-microservices-composition-tests/README.ko.md`
- Preserve: `commerce/usage-billing-microservices-composition-tests/**`의 JDK digest oracle

**Steps**

1. 기존 digest golden vector, `runtimeClasspath`, `bootJar` bytes를 production API와 독립된 baseline으로 고정한다.
2. versionless `bluetape4k-tink` alias와 다섯 module `implementation`만 먼저 추가한다. 각 module의 production `runtimeClasspath`, ecosystem gate용 `testRuntimeClasspath`, Google Tink 전이 jar, `bootJar` bytes/percent delta를 비교한다. 증가가 module당 5 MiB를 넘거나 crypto dependency가 중복되면 train을 PENDING으로 멈춘다.
3. 생성 경로인 다섯 integration envelope와 `MeterCommands`는 `digestHex`를 사용한다. 검증 경로인 envelope `hasValidPayloadDigest`와 네 inbound decoder는 기존 malformed·uppercase·wrong-length rejection을 유지하는 `matchesHex`를 사용한다.
4. production helper를 교체하고 framing·비인증 checksum 용도를 KDoc 또는 주석으로 명시한다. 한·영 README에는 생성/검증 예제, MAC/signature 비범위, 무마이그레이션, 독립 JDK oracle 이유를 같은 의미로 기록한다.
5. 다섯 unit test와 composition test를 bounded flag로 실행하고 실패 report와 Testcontainers 잔여 자원을 확인한다. old producer→new consumer, new producer→old consumer, 기존 DB digest vector의 byte 호환성을 검증한다.
6. 각 module에서 `./gradlew :<project>:dependencyInsight --dependency io.github.bluetape4k:bluetape4k-tink --configuration <runtimeClasspath|testRuntimeClasspath> --no-daemon --no-build-cache --max-workers=1 --no-parallel`을 실행한다. 성능 향상을 주장하지 않으므로 microbenchmark는 수행하지 않는다.
7. `detekt`, `git diff --check`, 독립 코드 리뷰를 통과시킨 뒤 커밋·push하고 `develop` base PR을 생성한다.

## Task 2: #990 Kafka failover stacked PR

**Files**

- Modify: `messaging/kafka-multi-broker-failover/build.gradle.kts`
- Modify: `messaging/kafka-multi-broker-failover/src/main/kotlin/**/KafkaFailoverCodec.kt`
- Modify: codec test와 golden evidence

**Steps**

1. encoded bytes, lowercase 64자 fingerprint, malformed/duplicate/trailing/size 거부 회귀 테스트를 먼저 고정한다.
2. codec이 소유한 canonical field order와 parser를 보존하고 digest primitive만 교체한다.
3. module test, `detekt`, exact `testRuntimeClasspath` `dependencyInsight`, diff check, 독립 리뷰를 통과시킨다.
4. #989 head를 base로 커밋·push하고 stacked PR을 생성한다.

## Task 3: #991 AWS Modulith stacked PR

**Files**

- Modify: `aws/sqs-sns-coroutines/build.gradle.kts`
- Modify: `aws/sqs-sns-coroutines/src/main/kotlin/**/ModulithExternalizationExample.kt`
- Modify: 관련 unit test와 module 문서 또는 KDoc

**Steps**

1. 이메일·Unicode 입력의 기존 16자 golden vector와 header 원문 비노출을 고정한다.
2. `digestHex(correlationSource).take(16)`으로 교체한다.
3. 64-bit truncation은 대량 고유키나 인증에 쓰지 않는 관측용 식별자라는 collision budget을 기록한다.
4. module test, `detekt`, exact `testRuntimeClasspath` `dependencyInsight`, diff check, 독립 리뷰를 통과시킨다.
5. #990 head를 base로 커밋·push하고 stacked PR을 생성한다.

## Task 4: #992 Reservation stacked PR

**Files**

- Modify: `commerce/reservation-control-plane/build.gradle.kts`
- Modify: `commerce/reservation-control-plane/src/main/kotlin/**/IdempotencyFingerprint.kt`
- Modify: `commerce/reservation-control-plane/src/test/kotlin/**/IdempotencyFingerprintTest.kt`
- Preserve: `ReservationCredentialService`, `ReservationCommandExecutionGate`, credential configuration과 HMAC tests

**Steps**

1. unkeyed `IdempotencyFingerprint.request()`와 test/fixture `key()`의 low-entropy 입력은 offline enumeration 위험이 있음을 KDoc로 고정한다. production raw key와 owner/operator는 HMAC 경계가 보호한다는 차이를 함께 기록한다.
2. unkeyed digest는 요청 동등성 판정과 저장 호환성 목적으로만 유지한다.
3. 기존 `domain + NUL + ordered fields` 문자열을 그대로 `digestHex`에 전달한다.
4. 기존·Unicode·빈 field golden vector를 고정한다. 별도 assertion으로 `keyDigest == credentials.idempotencyDigest(...)`, `requestFingerprint == IdempotencyFingerprint.request(...)`, 원문 idempotency key·owner·`customer-visible-secret`의 DB/log 비노출을 검증한다.
5. module test, `detekt`, exact `testRuntimeClasspath` `dependencyInsight`, diff check, 독립 리뷰를 통과시킨다.
6. #991 head를 base로 커밋·push하고 stacked PR을 생성한다.

## Task 5: #988 Jackson3 독립 PR

**Files**

- Modify: `ktor/rest-coroutines/src/main/kotlin/**/Jackson3Support.kt`
- Modify: `messaging/kafka-outbox-fallback/src/main/kotlin/**/KafkaConfig.kt`
- Modify: `messaging/transactional-outbox/src/main/kotlin/**/KafkaConfig.kt`
- Modify: 세 module의 JSON golden/HTTP boundary tests
- Modify only if unused: 세 module `build.gradle.kts`의 직접 Jackson Kotlin module dependency

**Steps**

1. NDJSON newline과 Unicode/null/collection/time output, outbox 저장·재전송 payload를 교체 전 baseline으로 고정한다.
2. Spring HTTP unknown property, trailing JSON value, trailing comma, duplicate key, malformed JSON, null/coercion과 unsafe default typing 부재를 고정한다. 교체 후 결과가 하나라도 달라지면 Spring bean 교체를 중단한다.
3. Ktor는 immutable singleton, Spring bean은 isolated factory로 교체한다.
4. 남은 직접 import와 resolved graph를 확인한 뒤에만 직접 Jackson Kotlin module dependency를 제거한다.
5. `.github/workflows/Examples.yml` 또는 동등한 required check에 세 module test task를 모두 등록한다. `scripts/smoke-validate.sh`, nightly/validation matrix도 해당 실행 경계에 맞추고 local receipt만으로 누락을 대체하지 않는다.
6. 세 module test, `detekt`, dependency graph, diff check, 독립 리뷰를 통과시킨다.
7. #992 head에서 branch를 만들고 커밋·push한 뒤 #992 branch base의 stacked PR을 생성한다.

## Task 6: #993 leader coroutine scope 독립 PR

**Files**

- Modify: `leader/leader-election/build.gradle.kts`
- Modify: `leader/leader-election/src/main/kotlin/**/LeaderEventListenerService.kt`
- Modify: `leader/leader-election/src/test/kotlin/**`
- Modify: `leader/job-safety-lab/build.gradle.kts`
- Modify: `leader/job-safety-lab/src/main/kotlin/**/JobSafetyAuditScope.kt`
- Review/preserve: `leader/job-safety-lab/src/main/kotlin/**/JobSafetyAuditConfiguration.kt`
- Modify: 관련 lifecycle tests

**Steps**

1. 실제 child `Job` cancellation, `CancellationException` 보존, 반복·동시 close, listener 제거 실패 후 scope close 시도, 종료 후 미수집, 초기화 부분 실패 정리, coordinator scope-last 순서를 테스트로 고정한다. 현재 생성자 구조에서 초기화 부분 실패를 재현할 수 없으면 source-backed N/A를 남긴다.
2. 두 module에 production `bluetape4k-coroutines` dependency를 추가한다.
3. `LeaderEventListenerService`는 `private val scope: DefaultCoroutineScope = DefaultCoroutineScope()`를 소유하고 종료 시 `scope.close()`를 호출한다. `JobSafetyAuditScope`는 `DefaultCoroutineScope`를 상속하며 `isActive`는 context `Job`에서 계산한다.
4. `JobSafetyAuditConfiguration`의 scope bean `destroyMethod=""`와 coordinator `destroyMethod="close"`를 보존하고 context test로 scope-last close를 검증한다.
5. 두 module test, `detekt`, exact `testRuntimeClasspath` dependency graph, diff check, 독립 리뷰를 통과시킨다.
6. #988 head에서 branch를 만들고 커밋·push한 뒤 #988 branch base의 stacked PR을 생성한다.

## Task 7: PR train 최종 검증과 merge hold

1. 각 PR의 title, body, labels, milestone, assignee, linked issue, base/head를 live read-back한다.
2. 각 PR exact 40-hex head의 CI, review, unresolved thread, mergeability를 확인한다.
3. #990~#993은 직전 PR dependency와 이후 retarget 순서를 PR 본문에 명시한다.
4. smoke lane, stale-check, ecosystem reuse guard와 manifest consistency가 변경 범위에 적용되는지 확인하고 실행 또는 source-backed N/A를 기록한다.
5. parent head가 바뀌면 child branch를 최신 parent에 rebase하고 merge-base, changed-file 목록, exact-head CI를 재검증한다. 일괄 머지는 앞 PR부터 수행하고 다음 PR을 `origin/develop` 기준으로 retarget한 뒤 같은 절차를 반복한다.
6. 여섯 PR과 open issue inventory를 보고하고 머지는 별도 fresh 승인까지 보류한다.

## 검토 결과

6개 관점의 독립 plan 검토 결과와 수정 반영은 `docs/review/2026-09-08-issues-988-993-plan-review.md`에 기록한다.
