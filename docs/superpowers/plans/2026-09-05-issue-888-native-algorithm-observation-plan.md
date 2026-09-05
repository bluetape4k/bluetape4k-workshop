# #888 native graph algorithm 실행 관찰 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 기존 PageRank 결과와 호환되면서 호출별 JVM fallback 정책·provider·reason을 안전하게 반환하고 관찰하는 blocking/suspend 예제를 만든다.

**Architecture:** 서비스가 `GraphAlgorithmProviderSelector`로 호출별 execution을 먼저 결정하고, 현재 provider가 없는 portable PageRank를 정확히 한 번 실행한 뒤 bounded projection과 점수를 한 결과로 반환한다. Backend의 공유 `lastAlgorithmExecution`은 사용하지 않으며 `NATIVE_ONLY`는 실행 전에 실패한다.

**Tech Stack:** Kotlin 2.4, Java 25, bluetape4k-graph 2.0.0, Kotlin Coroutines Flow, JUnit 5, MockK, bluetape4k assertions

---

### Task 1: 2.0.0 기준선 계약을 테스트로 분리한다

**Files:**
- Modify: `graph/abuser-detection/src/test/kotlin/io/bluetape4k/workshop/graph/abuser/AbstractAbuserDetectionTest.kt`
- Modify: `graph/abuser-detection/src/test/kotlin/io/bluetape4k/workshop/graph/abuser/AbstractAbuserDetectionSuspendTest.kt`
- Test: `graph/abuser-detection/src/test/kotlin/io/bluetape4k/workshop/graph/abuser/AbuserDetectionTinkerGraphTest.kt`
- Test: `graph/abuser-detection/src/test/kotlin/io/bluetape4k/workshop/graph/abuser/AbuserDetectionSuspendTinkerGraphTest.kt`

- [x] **Step 1: 기존 missing ID fixture를 numeric missing ID로 바꾼다**

```kotlin
val unknownId = GraphElementId("99999999")
val missingDeviceId = GraphElementId("99999998")
```

- [x] **Step 2: malformed TinkerGraph ID 계약을 backend 전용 RED/characterization 테스트로 추가한다**

```kotlin
assertFailsWith<GraphQueryException> {
    service.findAbuseCluster(GraphElementId("malformed-id"))
}
```

- [x] **Step 3: 기준선 테스트를 실행한다**

Run: `./gradlew :graph-abuser-detection:test --no-build-cache --rerun-tasks --max-workers=1`

Expected: 기존 네 실패가 사라지고 numeric missing과 malformed 입력이 구분되어 PASS한다.

- [x] **Step 4: 기준선 호환성 변경을 커밋한다**

```bash
git add graph/abuser-detection/src/test
git commit -m "2.0.0 그래프 ID 입력 계약을 예제에 맞춘다" \
  -m $'Constraint: TinkerGraph의 malformed 문자열 ID는 2.0.0에서 GraphQueryException이다.\nRejected: service에서 GraphQueryException을 누락으로 변환 | backend 입력 오류와 missing vertex를 구분한다.\nConfidence: high\nScope-risk: narrow\nDirective: missing fixture는 backend가 해석할 수 있는 ID 형식을 사용한다.\nTested: graph-abuser-detection test\nNot-tested: native provider execution'
```

### Task 2: 호출별 실행 결과 모델의 RED 테스트를 작성한다

**Files:**
- Create: `graph/abuser-detection/src/main/kotlin/io/bluetape4k/workshop/graph/abuser/model/AbuserAlgorithmExecution.kt`
- Create: `graph/abuser-detection/src/main/kotlin/io/bluetape4k/workshop/graph/abuser/model/SuspiciousUserRanking.kt`
- Create: `graph/abuser-detection/src/test/kotlin/io/bluetape4k/workshop/graph/abuser/model/AbuserAlgorithmExecutionTest.kt`

- [x] **Step 1: bounded projection의 실패 테스트를 먼저 작성한다**

```kotlin
@Test
fun `provider ID는 안전한 64자 경계만 허용한다`() {
    AbuserAlgorithmExecution(
        algorithm = GraphAlgorithmId.PAGE_RANK,
        providerId = "a".repeat(64),
        path = GraphAlgorithmExecutionPath.JVM_FALLBACK,
        fallbackReason = GraphAlgorithmFallbackReason.NO_PROVIDER,
    )
    listOf("bad\r\nid", "bad\u0000id", "bad\tid", "A-provider", "x".repeat(65)).forEach { providerId ->
        assertFailsWith<IllegalArgumentException> {
            AbuserAlgorithmExecution(
                algorithm = GraphAlgorithmId.PAGE_RANK,
                providerId = providerId,
                path = GraphAlgorithmExecutionPath.JVM_FALLBACK,
                fallbackReason = GraphAlgorithmFallbackReason.NO_PROVIDER,
            )
        }
    }
}
```

- [x] **Step 2: RED를 확인한다**

Run: `./gradlew :graph-abuser-detection:test --tests '*AbuserAlgorithmExecutionTest' --no-build-cache --rerun-tasks`

Expected: 새 model이 없어 compile 실패한다.

- [x] **Step 3: 최소 model을 구현한다**

```kotlin
data class AbuserAlgorithmExecution(
    val algorithm: GraphAlgorithmId,
    val providerId: String,
    val path: GraphAlgorithmExecutionPath,
    val fallbackReason: GraphAlgorithmFallbackReason?,
) : Serializable {
    init {
        require(PROVIDER_ID.matches(providerId)) { "providerId must match ${PROVIDER_ID.pattern}" }
        when (path) {
            GraphAlgorithmExecutionPath.NATIVE -> require(fallbackReason == null)
            GraphAlgorithmExecutionPath.JVM_FALLBACK -> require(fallbackReason != null)
        }
    }
    companion object {
        private val PROVIDER_ID = Regex("[a-z0-9][a-z0-9._-]{0,63}")
        fun from(execution: GraphAlgorithmExecution) = AbuserAlgorithmExecution(
            execution.algorithm,
            execution.providerId,
            execution.path,
            execution.fallbackReason,
        )
    }
}

data class SuspiciousUserRanking(
    val scores: List<SuspiciousUserScore>,
    val execution: AbuserAlgorithmExecution,
) : Serializable
```

- [x] **Step 4: model 테스트를 통과시킨다**

Run: `./gradlew :graph-abuser-detection:test --tests '*AbuserAlgorithmExecutionTest' --no-build-cache --rerun-tasks`

Expected: safe provider, invalid provider, native/fallback invariant tests가 모두 PASS한다.

### Task 3: blocking PageRank 실행 관찰을 TDD로 추가한다

**Files:**
- Modify: `graph/abuser-detection/src/main/kotlin/io/bluetape4k/workshop/graph/abuser/service/AbuserDetectionService.kt`
- Modify: `graph/abuser-detection/src/test/kotlin/io/bluetape4k/workshop/graph/abuser/AbstractAbuserDetectionTest.kt`
- Create: `graph/abuser-detection/src/test/kotlin/io/bluetape4k/workshop/graph/abuser/AbuserDetectionExecutionPolicyTest.kt`

- [x] **Step 1: AUTO/JVM_ONLY/NATIVE_ONLY와 단일 호출 RED 테스트를 작성한다**

```kotlin
val ops = mockk<GraphOperations>()
val pageRankScores = listOf(PageRankScore(userVertex, 0.75))
every { ops.pageRank(any()) } returns pageRankScores
val observed = CopyOnWriteArrayList<GraphAlgorithmExecution>()
val service = AbuserDetectionService(
    ops,
    "test",
    GraphAlgorithmExecutionObserver { observed += it },
)

val ranking = service.rankSuspiciousUsersWithExecution(2, GraphAlgorithmProviderPolicy.JVM_ONLY)

verify(exactly = 1) { ops.pageRank(match { it.topK == 2 }) }
ranking.execution.fallbackReason shouldBeEqualTo GraphAlgorithmFallbackReason.JVM_ONLY_POLICY
observed shouldHaveSize 1
```

`NATIVE_ONLY` 테스트는 `assertFailsWith<GraphAlgorithmProviderUnavailableException>`와
`verify(exactly = 0) { ops.pageRank(any()) }`를 사용한다.

- [x] **Step 2: RED를 확인한다**

Run: `./gradlew :graph-abuser-detection:test --tests '*AbuserDetectionExecutionPolicyTest' --no-build-cache --rerun-tasks`

Expected: 새 생성자 인자와 메서드가 없어 compile 실패한다.

- [x] **Step 3: blocking API를 최소 구현한다**

```kotlin
fun rankSuspiciousUsersWithExecution(
    limit: Int = 20,
    policy: GraphAlgorithmProviderPolicy = GraphAlgorithmProviderPolicy.AUTO,
): SuspiciousUserRanking {
    limit.requirePositiveNumber("limit")
    val execution = GraphAlgorithmProviderSelector.select(GraphAlgorithmId.PAGE_RANK, policy = policy)
    val scores = rankSuspiciousUsers(limit)
    notifyExecution(execution)
    return SuspiciousUserRanking(scores, AbuserAlgorithmExecution.from(execution))
}
```

`notifyExecution`은 `CancellationException`을 재전파한다. 다른 observer
`Exception`은 raw Throwable, provider ID, exception message를 넘기지 않고
`"Graph algorithm execution observer failed"`라는 안정 문자열만 경고로 남긴다.
해당 observer가 예외를 던져도 blocking PageRank 결과가 반환되고 observer가 정확히
한 번 호출됐는지 테스트한다.

- [x] **Step 4: targeted blocking 테스트를 통과시킨다**

Run: `./gradlew :graph-abuser-detection:test --tests '*AbuserDetectionExecutionPolicyTest' --tests '*AbuserDetectionTinkerGraphTest' --no-build-cache --rerun-tasks`

Expected: PageRank 단일 호출, policy reason, observer 1회, 기존 점수 parity가 PASS한다.

### Task 4: suspend 실행·취소 경계를 TDD로 추가한다

**Files:**
- Modify: `graph/abuser-detection/src/main/kotlin/io/bluetape4k/workshop/graph/abuser/service/AbuserDetectionSuspendService.kt`
- Modify: `graph/abuser-detection/src/test/kotlin/io/bluetape4k/workshop/graph/abuser/AbstractAbuserDetectionSuspendTest.kt`
- Create: `graph/abuser-detection/src/test/kotlin/io/bluetape4k/workshop/graph/abuser/AbuserDetectionSuspendExecutionPolicyTest.kt`

- [x] **Step 1: suspend policy parity와 cancellation RED 테스트를 작성한다**

```kotlin
val firstEmission = CompletableDeferred<Unit>()
every { ops.pageRank(any()) } returns flow {
    firstEmission.complete(Unit)
    emit(firstScore)
    awaitCancellation()
}
val job = launch { service.rankSuspiciousUsersWithExecution() }
firstEmission.await()
job.cancelAndJoin()
observed.shouldBeEmpty()
job.isCancelled.shouldBeTrue()
```

정상 경로는 `verify(exactly = 1) { ops.pageRank(match { it.topK == limit }) }`로
Flow factory 호출과 `topK`를 검증한다. 반환 결과 수·정렬과 blocking execution
parity도 assertion한다. observer가 `CancellationException`을 던지는 테스트는
`assertFailsWith<CancellationException>`으로 재전파를 직접 증명하고, 일반
`Exception`은 결과를 실패시키지 않는지 별도로 검증한다.

- [x] **Step 2: RED를 확인한다**

Run: `./gradlew :graph-abuser-detection:test --tests '*AbuserDetectionSuspendExecutionPolicyTest' --no-build-cache --rerun-tasks`

Expected: 새 suspend API가 없어 compile 실패한다.

- [x] **Step 3: suspend API를 최소 구현한다**

```kotlin
suspend fun rankSuspiciousUsersWithExecution(
    limit: Int = 20,
    policy: GraphAlgorithmProviderPolicy = GraphAlgorithmProviderPolicy.AUTO,
): SuspiciousUserRanking {
    limit.requirePositiveNumber("limit")
    val execution = GraphAlgorithmProviderSelector.select(GraphAlgorithmId.PAGE_RANK, policy = policy)
    val scores = rankSuspiciousUsers(limit).toList()
    currentCoroutineContext().ensureActive()
    notifyExecution(execution)
    return SuspiciousUserRanking(scores, AbuserAlgorithmExecution.from(execution))
}
```

- [x] **Step 4: suspend targeted 테스트를 통과시킨다**

Run: `./gradlew :graph-abuser-detection:test --tests '*AbuserDetectionSuspendExecutionPolicyTest' --tests '*AbuserDetectionSuspendTinkerGraphTest' --no-build-cache --rerun-tasks`

Expected: parity, cancellation 전파, observer 미호출과 단일 PageRank collection이 PASS한다.

- [x] **Step 5: concurrency attribution을 검증한다**

Run: blocking은 `MultithreadingTester`와 `CyclicBarrier`, suspend는
`coroutineScope { List(20) { async { ... } }.awaitAll() }`을 사용한다. 짝수 요청은
`AUTO`, 홀수 요청은 `JVM_ONLY`로 실행해 서로 다른 fallback reason을 만들고, barrier
뒤 각 반환 결과가 자신의 policy와 일치하는지 검증한다. Backend
`lastAlgorithmExecution`은 stub하지 않으며 observer event 수는 성공 호출 수와 같아야
한다. callback 직전 취소 fixture는 취소가 먼저 관찰되면 callback 0회, callback이 먼저
시작되면 최대 1회이며 어느 경우에도 취소된 coroutine이 결과를 반환하지 않음을
검증한다.

### Task 5: 공개 문서와 검증 표면을 갱신한다

**Files:**
- Modify: `graph/abuser-detection/README.md`
- Modify: `graph/abuser-detection/README.ko.md`
- Modify: `graph/abuser-detection/build.gradle.kts`
- Modify: `README.md`
- Modify: `README.ko.md`
- Modify: `docs/coverage-matrix.md`
- Modify: `.github/workflows/Examples.yml`
- Modify: `scripts/smoke-validate.sh`
- Modify: `docs/ecosystem-reuse-train.json`
- Create: `docs/lessons/2026-09-05-issue-888-native-algorithm-observation.md`
- Modify: `docs/lessons/README.md`

- [ ] **Step 1: README 양국어에 같은 계약을 기록한다**

`AUTO=NO_PROVIDER`, `JVM_ONLY=JVM_ONLY_POLICY`, `NATIVE_ONLY=실행 전 실패`, 현재
native SDK/executor 미포함, 기존 점수 API 호환, provider ID 제한과 suspend cancellation
경계를 동등하게 설명한다. `build.gradle.kts`의 오래된 `0.4.1` 설명은 BOM `2.0.0`
기준으로 고친다. 새 public model과 서비스 API에는 실제 계약과 같은 한국어 KDoc을
작성한다.

- [ ] **Step 2: module validation matrix를 확장한다**

`.github/workflows/Examples.yml`의 graph path, smoke test, integration test와 artifact
목록에 `graph/abuser-detection`을 추가한다. `scripts/smoke-validate.sh`에는 새 model,
두 API, policy, lesson/review, `2.0.0` 및 `2.1.0-SNAPSHOT` 금지 guard를 추가한다.

- [ ] **Step 3: coverage와 durable evidence를 기록한다**

`docs/coverage-matrix.md`의 graph 행에 #888과 provider 관찰을 추가하고 lesson에는
upstream API, baseline ID drift, 설계 결정, 검증 결과를 기록한다.
`docs/ecosystem-reuse-train.json`에는 base `develop`, head
`feat/issue-888-native-algorithm-observation`, issue `[888]`, 실제 allowed paths와
implementation review를 등록한다.

### Task 6: 전체 검증과 implementation review를 완료한다

**Files:**
- Create: `docs/superpowers/specs/2026-09-05-issue-888-native-algorithm-observation-implementation-review.md`
- Modify: `docs/superpowers/plans/2026-09-05-issue-888-native-algorithm-observation-plan.md`

- [ ] **Step 1: fresh module 검증을 실행한다**

```bash
./gradlew :graph-abuser-detection:clean :graph-abuser-detection:test --no-build-cache --rerun-tasks --max-workers=1
./gradlew :graph-abuser-detection:integrationTest --no-build-cache --rerun-tasks --max-workers=1
./gradlew detekt --no-build-cache --rerun-tasks --max-workers=1
```

Container 환경이 가용하지 않으면 Colima/Docker 상태를 먼저 분류하고 integration
gap을 숨기지 않는다.

- [ ] **Step 2: 문서·workflow·dependency 검증을 실행한다**

```bash
node scripts/validate-readme-language.mjs
node scripts/validate-readme-parity.mjs graph/abuser-detection
node ~/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs \
  graph/abuser-detection/README.ko.md \
  docs/coverage-matrix.md \
  docs/lessons/2026-09-05-issue-888-native-algorithm-observation.md \
  docs/superpowers/specs/2026-09-05-issue-888-native-algorithm-observation-implementation-review.md
bash scripts/smoke-validate.sh stale-check
python3 .github/scripts/test_check_ecosystem_reuse.py -v
actionlint .github/workflows/Examples.yml
./gradlew :graph-abuser-detection:dependencyInsight --dependency io.github.bluetape4k.graph:bluetape4k-graph-core --configuration testRuntimeClasspath
git diff --check
```

- [ ] **Step 3: Type A verifier와 six-lens implementation review를 수렴시킨다**

Spec 요구사항·계획 단계·changed paths·test evidence를 traceability 표로 만들고,
performance/stability/security/ops/API/user review에서 P0=0, P1=0을 확인한다. P2/P3는
수정하거나 근거와 후속 issue를 기록한다.

- [ ] **Step 4: 구현을 Lore commit으로 고정한다**

```bash
git add \
  graph/abuser-detection/build.gradle.kts \
  graph/abuser-detection/README.md \
  graph/abuser-detection/README.ko.md \
  graph/abuser-detection/src/main/kotlin/io/bluetape4k/workshop/graph/abuser/model/AbuserAlgorithmExecution.kt \
  graph/abuser-detection/src/main/kotlin/io/bluetape4k/workshop/graph/abuser/model/SuspiciousUserRanking.kt \
  graph/abuser-detection/src/main/kotlin/io/bluetape4k/workshop/graph/abuser/service/AbuserDetectionService.kt \
  graph/abuser-detection/src/main/kotlin/io/bluetape4k/workshop/graph/abuser/service/AbuserDetectionSuspendService.kt \
  graph/abuser-detection/src/test/kotlin/io/bluetape4k/workshop/graph/abuser/AbstractAbuserDetectionTest.kt \
  graph/abuser-detection/src/test/kotlin/io/bluetape4k/workshop/graph/abuser/AbstractAbuserDetectionSuspendTest.kt \
  graph/abuser-detection/src/test/kotlin/io/bluetape4k/workshop/graph/abuser/AbuserDetectionExecutionPolicyTest.kt \
  graph/abuser-detection/src/test/kotlin/io/bluetape4k/workshop/graph/abuser/AbuserDetectionSuspendExecutionPolicyTest.kt \
  graph/abuser-detection/src/test/kotlin/io/bluetape4k/workshop/graph/abuser/model/AbuserAlgorithmExecutionTest.kt \
  README.md README.ko.md docs/coverage-matrix.md \
  docs/ecosystem-reuse-train.json \
  docs/lessons/README.md \
  docs/lessons/2026-09-05-issue-888-native-algorithm-observation.md \
  .github/workflows/Examples.yml scripts/smoke-validate.sh \
  docs/superpowers/plans/2026-09-05-issue-888-native-algorithm-observation-plan.md \
  docs/superpowers/specs/2026-09-05-issue-888-native-algorithm-observation-implementation-review.md
git diff --cached --name-only
git commit -m "그래프 PageRank fallback을 호출별로 관찰한다" \
  -m $'Constraint: 현재 2.0.0 consumer에는 native executor가 없다.\nRejected: backend lastAlgorithmExecution 조회 | 호출 correlation과 policy reason이 불안정하다.\nConfidence: high\nScope-risk: moderate\nDirective: native provider를 추가할 때 selector와 실제 executor를 같은 호출 결과로 결속한다.\nTested: module test, integrationTest, detekt, docs and workflow guards\nNot-tested: external GDS/MAGE native execution'
```

### Task 7: PR과 exact-head CI를 검증한다

**Files:**
- Create via GitHub: PR `feat/issue-888-native-algorithm-observation` → `develop`

- [ ] **Step 1: branch를 push하고 `[2.0.0]` PR을 생성한다**

PR 본문은 `Closes #888`, design/plan/review 링크, 변경·검증·DoD checklist를 한국어로
기록한다. Issue/PR milestone은 `2.0.0`, assignee는 `debop`으로 맞춘다.

- [ ] **Step 2: live exact head와 hosted checks를 확인한다**

`gh pr view --json headRefOid,baseRefName,headRefName,body,mergeable,mergeStateStatus,statusCheckRollup,reviews,comments`로
push SHA와 PR SHA가 같고 필수 checks가 모두 성공했는지 검증한다.

- [ ] **Step 3: merge hold를 확인한다**

PR은 OPEN 상태로 유지한다. #889는 #888 PR이 merge-ready이고 workflow component
evidence가 기록된 뒤에만 `origin/develop`에서 새 독립 worktree로 시작한다.

## 중단과 복구

- published 2.0.0에서 provider API가 compile되지 않으면 workshop shim을 만들지 않고
  `dependencyInsight`와 compiler error를 증거로 범위를 중단한다.
- `NATIVE_ONLY`가 PageRank를 호출하면 P1로 처리하고 PR 생성 전에 고친다.
- observer exception 또는 cancellation이 성공 result를 잘못 만들면 suspend/blocking
  affected tests를 다시 RED로 만든 뒤 최소 수정한다.
- root의 기존 `docs/coverage-matrix.md` dirty 변경과 다른 worktree는 복사·reset·정리하지
  않는다.
