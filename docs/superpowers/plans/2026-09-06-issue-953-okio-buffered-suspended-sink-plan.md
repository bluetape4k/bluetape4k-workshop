# Issue #953 Okio BufferedSuspendedSink Public API Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the local buffered sink duplicate and prove the stable bluetape4k-okio 2.0.0 public API contract.

**Architecture:** Keep the existing local SuspendedSink/SuspendedSource adapters, but obtain every buffered sink through the provider public `SuspendedSink.buffered()` extension. Lock the provider boundary with public-API-only behavior tests and register the module in hosted Examples path/smoke coverage.

**Tech Stack:** Kotlin 2.4.0, Java 25, kotlinx-coroutines-test, Okio, bluetape4k-okio 2.0.0, JUnit 5, GitHub Actions.

---

### Task 1: 공개 API 회귀를 RED로 고정

**Files:**
- Modify: `io/okio-examples/src/test/kotlin/io/bluetape4k/okio/coroutines/BufferedSuspendedSinkTest.kt`
- Modify: `io/okio-examples/src/test/kotlin/io/bluetape4k/okio/coroutines/SuspendInteropTest.kt`

- [ ] **Step 1: internal 구현 직접 생성을 public extension으로 바꾼다**

```kotlin
val bufferedSink: BufferedSuspendedSink = fakeSink.buffered()
```

- [ ] **Step 2: `runTest` 가상 시간으로 delayed no-progress fixture를 추가한다**

```kotlin
private class NoProgressSuspendedSource: SuspendedSource {
    var readCount = 0
    override suspend fun read(sink: Buffer, byteCount: Long): Long {
        readCount++
        delay(10)
        return 0L
    }
    override suspend fun close() = Unit
    override fun timeout() = Timeout.NONE
}
```

- [ ] **Step 3: 현재 local duplicate에서 RED를 확인한다**

```kotlin
val writeSource = NoProgressSuspendedSource()
val writeError = assertFailsWith<IOException> {
    withTimeout(250) { fakeSink.buffered().write(writeSource, 1L) }
}
writeError.message shouldBeEqualTo "Unable to write from SuspendedSource: no progress."
writeSource.readCount shouldBeEqualTo 8

val writeAllSource = NoProgressSuspendedSource()
val writeAllError = assertFailsWith<IOException> {
    withTimeout(250) { fakeSink.buffered().writeAll(writeAllSource) }
}
writeAllError.message shouldBeEqualTo "Unable to writeAll from SuspendedSource: no progress."
writeAllSource.readCount shouldBeEqualTo 8
```

Run: `./gradlew :okio-examples:test --tests '*BufferedSuspendedSinkTest*no progress*' --rerun-tasks`

Expected: both tests FAIL because the local duplicate reaches `TimeoutCancellationException` instead of each bounded
`IOException` contract.

### Task 2: local duplicate를 제거하고 provider API로 GREEN 전환

**Files:**
- Delete: `io/okio-examples/src/main/kotlin/io/bluetape4k/okio/coroutines/BufferedSuspendedSink.kt`
- Delete: `io/okio-examples/src/main/kotlin/io/bluetape4k/okio/coroutines/RealBufferedSuspendedSink.kt`
- Modify: `io/okio-examples/src/test/kotlin/io/bluetape4k/okio/coroutines/BufferedSuspendedSinkTest.kt`
- Modify: `io/okio-examples/src/test/kotlin/io/bluetape4k/okio/coroutines/SuspendInteropTest.kt`

- [ ] **Step 1: 두 local duplicate 파일을 삭제한다**

```text
BufferedSuspendedSink.kt
RealBufferedSuspendedSink.kt
```

- [ ] **Step 2: 모든 테스트가 public factory만 사용하게 한다**

```kotlin
val bufferedSink = fakeSink.buffered()
```

Local `SuspendedSink`/`SuspendedSource`가 provider와 동일 FQCN을 유지하므로 전체 source API 호환을 주장하지 않는다.
이번 migration이 호출하는 `write`, `flush`, `close`, `timeout`의 JVM method descriptor와 provider 구현 linkage만
compile 및 socket/file-channel 실행으로 증명한다.

- [ ] **Step 3: public API contract를 완성한다**

```kotlin
val failure = assertFailsWith<IOException> { bufferedSink.close() }
failure shouldBeSameInstanceAs writeFailure
fakeSink.closeCount shouldBeEqualTo 1
fakeSink.writeCount shouldBeEqualTo 1
failure.suppressed.size shouldBeEqualTo 0
bufferedSink.close()
fakeSink.closeCount shouldBeEqualTo 1
fakeSink.writeCount shouldBeEqualTo 1
```

Exact payload test는 ByteString, ByteArray range, UTF-8 full/range/code point, byte/short/int/long endian,
decimal/hex, Buffer, fixed SuspendedSource와 writeAll을 모두 expected Okio Buffer와 byte-for-byte 비교한다.

- [ ] **Step 4: GREEN과 adapter ABI를 확인한다**

Run: `./gradlew :okio-examples:test --rerun-tasks`

Expected: all unit, socket and file-channel tests PASS with no direct `RealBufferedSuspendedSink` reference.

### Task 3: README와 회귀 gate를 등록

**Files:**
- Modify: `io/okio-examples/README.md`
- Modify: `io/okio-examples/README.ko.md`
- Modify: `docs/coverage-matrix.md`
- Create: `docs/lessons/2026-09-06-issue-953-okio-buffered-suspended-sink.md`
- Modify: `docs/lessons/README.md`
- Modify: `scripts/smoke-validate.sh`

- [ ] **Step 1: 양 언어 README에 stable consumer contract를 기록한다**

```text
The root bluetape4k-dependencies BOM resolves bluetape4k-okio to stable 2.0.0.
Call buffered() on a SuspendedSink and let the caller close the owned source/sink scope.
Repeated zero-byte reads fail with a bounded IOException.
```

- [ ] **Step 2: coverage와 lesson을 추가한다**

Async/Reactive matrix에 `bluetape4k-okio`, `io/okio-examples`, #953, public buffered sink와 bounded
no-progress/close boundary를 한 행으로 기록한다.

- [ ] **Step 3: stale-check guard를 추가한다**

Guard는 두 duplicate 파일이 없고 `fakeSink.buffered()`, `withTimeout`, `write from SuspendedSource`,
`writeAll from SuspendedSource`, read count `8`, 두 번째 close no-op, 최초 오류와 underlying close 시도,
exact payload sentinel, README/lesson/review/manifest의 #953와 2.0.0이 존재하며 scope에 2.1.0 snapshot이
없음을 검증한다.

Run: `./scripts/smoke-validate.sh stale-check`

Expected at this stage: guard is implemented, but the final PASS run is deferred until Task 5 creates the manifest and
review artifacts.

### Task 4: hosted Examples coverage를 연결

**Files:**
- Modify: `.github/workflows/Examples.yml`

- [ ] **Step 1: push/pull request path trigger를 추가한다**

```yaml
- 'io/okio-examples/**'
```

- [ ] **Step 2: credential-free smoke task와 설명을 추가한다**

```yaml
# - okio-examples: stable 2.0.0 public buffered coroutine sink contract
:okio-examples:test
```

- [ ] **Step 3: smoke 결과 artifact를 등록한다**

```yaml
io/okio-examples/build/test-results/test/*.xml
io/okio-examples/build/reports/tests/test/
```

- [ ] **Step 4: workflow syntax와 change classifier를 검증한다**

Run: `actionlint .github/workflows/Examples.yml`

Expected: exit 0, and a diff containing only `io/okio-examples/**` classifies `examples=true`.

### Task 5: manifest, review와 최종 검증

**Files:**
- Modify: `docs/ecosystem-reuse-train.json`
- Create: `docs/review/2026-09-06-issue-953-okio-buffered-suspended-sink-plan-review.md`
- Create: `docs/superpowers/specs/2026-09-06-issue-953-okio-buffered-suspended-sink-implementation-review.md`

- [ ] **Step 1: stacked follow-up scope를 등록한다**

```json
{
  "scope_id": "issue-953-okio-buffered-suspended-sink",
  "expected_head_ref": "refactor/issue-953-okio-buffered-suspended-sink",
  "expected_base_ref": "refactor/issue-940-cache-redis-virtualthreads",
  "issue_numbers": [953]
}
```

- [ ] **Step 2: stale-check final PASS를 확인한다**

Run: `./scripts/smoke-validate.sh stale-check`

Expected: `Okio BufferedSuspendedSink public API and lesson are registered.`

- [ ] **Step 3: clean/local governance를 검증한다**

Run:

```bash
./gradlew :okio-examples:cleanTest :okio-examples:test --no-build-cache --no-daemon --max-workers=1
./scripts/smoke-validate.sh serialization
./scripts/smoke-validate.sh all-smoke
./gradlew detekt
python3 .github/scripts/test_check_ecosystem_reuse.py -v
python3 .github/scripts/check-assertion-governance.py
node scripts/validate-readme-language.mjs
node scripts/validate-readme-parity.mjs io/okio-examples/README.md io/okio-examples/README.ko.md
actionlint .github/workflows/Examples.yml
git diff --check
```

Expected: module, serialization, all-smoke, detekt, 113 ecosystem tests, assertion governance,
README parity/language, actionlint and diff check all PASS.

- [ ] **Step 4: 독립 구현 리뷰와 exact scope를 통과한다**

Architecture/API와 test/operations reviewer가 각각 P0 0건, P1 0건이어야 한다. 구현 commit 뒤 checker에
base `e9e68a4c1289486cfef9c01044a6c5811938c037`, exact head SHA, base/head branch name을 전달해 PASS를 확인한다.

- [ ] **Step 5: Korean stacked PR을 생성한다**

Base: `refactor/issue-940-cache-redis-virtualthreads`

Title: `[2.0.0] okio-examples를 공개 BufferedSuspendedSink API로 이전`

PR body는 `Closes #953`, local/hosted 검증, stable 2.0.0, corrected close boundary와 DoD checklist를 포함한다.

## Self-review

- 설계의 삭제 범위, public API, no-progress, close failure, socket/file-channel, CI, 문서와 manifest가 각 Task에 매핑된다.
- placeholder와 2.1.0 snapshot 도입이 없다.
- public type/factory 이름과 base/head ref는 stable artifact 및 현재 stacked head와 일치한다.
