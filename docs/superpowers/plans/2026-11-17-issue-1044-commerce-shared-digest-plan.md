# Issue #1044 `commerce/shared` 공통 계약 추출 구현 계획

> **For agentic workers:** 이 계획은 승인된 설계를 순서대로 실행한다. 각 단계는 TDD와 현재 repository pattern을 따른다.

**Goal:** 다섯 usage-billing consumer가 반복하는 payload digest와 aggregate partition key 계약을 `commerce/shared`에서 재사용하도록 바꾸고 기존 wire 결과를 유지한다.

**Architecture:** `commerce/shared`의 `integration` package에 provider-backed `PayloadDigest`와 문자열 계약을 소유하는 `AggregatePartitionKey`를 추가한다. 서비스별 envelope schema, codec registry, persistence와 domain policy는 그대로 두고 helper 호출만 교체한다.

**Tech Stack:** Kotlin 2.4, Java 25, Gradle 9.8.2, `bluetape4k-tink`/`TinkDigesters`, JUnit 5, `bluetape4k-assertions`, Spring/Kafka/Exposed consumer modules.

---

## 파일과 책임

- Create: `commerce/shared/src/main/kotlin/io/bluetape4k/workshop/commerce/shared/integration/CommerceIntegrationContracts.kt` — 두 순수 helper와 Korean KDoc.
- Create: `commerce/shared/src/test/kotlin/io/bluetape4k/workshop/commerce/shared/integration/CommerceIntegrationContractsTest.kt` — provider parity, canonical rejection, partition key regression.
- Modify: `commerce/shared/build.gradle.kts` — `bluetape4k-tink` direct implementation.
- Modify: `commerce/shared/README.md`, `README.ko.md` — API와 보안 경계 설명.
- Modify: 다섯 usage-billing `build.gradle.kts` — shared project dependency로 교체.
- Modify: 다섯 integration envelope source와 관련 decoder/command source — helper 호출로 교체.
- Delete: `commerce/usage-billing-billing-service/src/test/.../TinkDigestCompatibilityTest.kt` — shared contract test로 이동.
- Modify: 다섯 service README locale pair와 `commerce/README.md`, `README.ko.md`, composition README locale pair — implementation owner를 shared로 정렬.
- Create: `docs/review/2026-11-17-issue-1044-commerce-shared-review.md` — final integrated review evidence.
- Create: `docs/lessons/2026-11-17-issue-1044-commerce-shared-digest.md` — durable guard and outcome.

## Task 1: Shared contract RED

**Files:**

- Create test: `commerce/shared/src/test/kotlin/io/bluetape4k/workshop/commerce/shared/integration/CommerceIntegrationContractsTest.kt`

- [ ] 테스트에 다음 동작을 명시한다.

```kotlin
@Test
fun `payload digest preserves provider lowercase SHA-256 wire contract`() {
    val payload = "한글과 emoji🙂"
    PayloadDigest.of(payload) shouldBeEqualTo jdkDigest(payload)
}

@Test
fun `payload digest rejects non-canonical values`() {
    val digest = PayloadDigest.of("payload")
    PayloadDigest.matches("payload", digest.uppercase()) shouldBeEqualTo false
    PayloadDigest.matches("payload", digest.dropLast(2)) shouldBeEqualTo false
    PayloadDigest.matches("other", digest) shouldBeEqualTo false
}

@Test
fun `aggregate partition key preserves tenant aggregate id order`() {
    AggregatePartitionKey.of("tenant-a", "Invoice", "invoice-7") shouldBeEqualTo
        "tenant-a|Invoice|invoice-7"
}
```

- [ ] `./gradlew :commerce-shared:test --tests '*CommerceIntegrationContractsTest' --max-workers=1 --console=plain`을 실행해 helper가 아직 없어서 compile 실패하는 RED를 기록한다.

## Task 2: Shared contract GREEN

**Files:**

- Create: `commerce/shared/src/main/kotlin/io/bluetape4k/workshop/commerce/shared/integration/CommerceIntegrationContracts.kt`
- Modify: `commerce/shared/build.gradle.kts`

- [ ] `PayloadDigest.of`는 `TinkDigesters.SHA256.digestHex(payload)`를 반환하고 `matches`는 `matchesHex`를 반환한다.
- [ ] `AggregatePartitionKey.of`는 입력 순서를 바꾸지 않고 `"$tenantId|$aggregateType|$aggregateId"`를 반환한다.
- [ ] public helper KDoc를 Korean으로 작성하고 authentication이 아닌 checksum임을 명시한다.
- [ ] `bluetape4k-tink`만 shared main dependency에 추가한다. 새 dependency나 local raw SHA-256 구현은 추가하지 않는다.
- [ ] 같은 targeted test를 다시 실행해 GREEN을 확인한다.
- [ ] `:commerce-shared:test` 전체를 실행해 기존 voucher contract test와 새 테스트가 함께 통과하는지 확인한다.

## Task 3: Consumer migration

**Files:**

- Modify: `commerce/usage-billing-meter-service/src/main/.../MeterIntegrationEnvelope.kt`, `MeterCommands.kt`, `build.gradle.kts`
- Modify: `commerce/usage-billing-usage-service/src/main/.../UsageIntegrationEnvelope.kt`, `MeterPriceEvidenceConsumer.kt`, `build.gradle.kts`
- Modify: `commerce/usage-billing-billing-service/src/main/.../BillingIntegrationEnvelope.kt`, `BillingKafkaConsumer.kt`, `build.gradle.kts`
- Modify: `commerce/usage-billing-invoice-service/src/main/.../InvoiceIntegrationEnvelope.kt`, `BillingChargeConsumer.kt`, `build.gradle.kts`
- Modify: `commerce/usage-billing-query-service/src/main/.../QueryIntegrationEnvelope.kt`, `QueryKafkaConsumer.kt`, `QueryInboxPersistence.kt`, `build.gradle.kts`
- Delete: `commerce/usage-billing-billing-service/src/test/.../TinkDigestCompatibilityTest.kt`

- [ ] 각 source의 direct `TinkDigesters` import를 제거하고 `PayloadDigest`를 사용한다.
- [ ] 각 `partitionKey()`와 Query persistence의 동일한 문자열 조합을 `AggregatePartitionKey.of`로 바꾼다.
- [ ] 다섯 build file에서 `implementation(libs.bluetape4k.tink)`를 제거하고 `implementation(project(":commerce-shared"))`를 추가한다.
- [ ] consumer-specific exception, schema, event field, persistence column, idempotency policy는 바꾸지 않는다.
- [ ] `rg -n 'TinkDigesters|MessageDigest' commerce/usage-billing-*` 결과가 shared contract test 외 production consumer에 남지 않는지 확인한다.

## Task 4: Consumer RED/GREEN and docs parity

**Files:** 다섯 service test source, shared README locale pair, commerce README locale pair, composition README locale pair, 다섯 service README locale pair.

- [ ] 기존 envelope/decoder/partition tests를 먼저 실행해 migration 전 baseline을 확보한다.
- [ ] helper import와 project dependency를 적용한 뒤 다섯 module `test`를 순차 실행한다.
- [ ] composition integration test를 실행해 service 간 digest/partition wire 결과가 동일한지 확인한다.
- [ ] 모든 README에서 provider 직접 호출 설명을 `PayloadDigest` shared owner와 동일한 lowercase 64-hex, non-authentication checksum 설명으로 바꾼다.
- [ ] English/Korean paragraph, API 이름, 명령, URL의 locale parity를 검토한다.

## Task 5: Verification and static checks

- [ ] `./gradlew :commerce-shared:test :commerce-usage-billing-meter-service:test :commerce-usage-billing-usage-service:test :commerce-usage-billing-billing-service:test :commerce-usage-billing-invoice-service:test :commerce-usage-billing-query-service:test --max-workers=1 --console=plain`
- [ ] `./gradlew :commerce-usage-billing-microservices-composition-tests:integrationTest --max-workers=1 --console=plain` (Testcontainers 환경을 순차 사용)
- [ ] `./gradlew :commerce-shared:check :commerce-usage-billing-meter-service:check :commerce-usage-billing-usage-service:check :commerce-usage-billing-billing-service:check :commerce-usage-billing-invoice-service:check :commerce-usage-billing-query-service:check -x detekt --rerun-tasks --max-workers=1 --console=plain`
- [ ] `./gradlew detekt --max-workers=1 --console=plain`을 시도하고 변경 파일 finding과 pre-existing finding을 분리 기록한다.
- [ ] `./scripts/smoke-validate.sh commerce`로 자동 module discovery와 validation matrix를 확인한다.
- [ ] `git diff --check develop...HEAD`와 source/doc `rg` audit를 통과시킨다.

## Task 6: Review, lesson, and local closeout

- [ ] 변경 diff와 설계/계획을 다시 읽고 P0/P1=0을 확인한다.
- [ ] performance, stability, security, operator/Ops, developer/API, user/caller 관점의 독립 review와 main integration verdict를 기록한다. 구현된 helper는 순수 함수이며 hot-path allocation/lifecycle 변화가 없다는 근거를 남긴다.
- [ ] `docs/review/2026-11-17-issue-1044-commerce-shared-review.md`에 file/line evidence, findings, disposition, verification과 SPW-01~05를 기록한다.
- [ ] `docs/lessons/2026-11-17-issue-1044-commerce-shared-digest.md`에 provider-first reuse와 shared boundary guard를 기록한다.
- [ ] Lore 형식으로 implementation commit을 만들고, branch head와 workflow receipt를 기록한다.
- [ ] PR/merge authority가 없으면 branch와 worktree를 보존하고 delivery를 N/A/PENDING으로 남긴다.

## Rollback and rerun points

- Shared contract test RED가 예상과 다르면 구현을 시작하지 않고 API 이름/패키지를 수정한다.
- Consumer compile failure가 생기면 해당 모듈의 `implementation(project(":commerce-shared"))`와 import를 먼저 되돌려 원인을 분리한다.
- Container/integration failure는 test lifecycle과 Colima 상태를 확인한 뒤 재실행하며, 실패를 skipped로 분류하지 않는다.
- 모든 source/docs 변경은 implementation commit 전 `git diff --check`와 locale/source audit에서 되돌릴 수 있다.

계획 writer gate: SPW-01 PASS, SPW-02 PASS, SPW-03 PASS, SPW-04 PASS,
SPW-05 PASS.
