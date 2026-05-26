# Issue #81 Messaging/Redis Advanced Implementation Plan

Date: 2026-05-26
Repository: `bluetape4k-workshop`
Branch: `feat/issue-81-messaging-advanced`
Spec: `docs/superpowers/specs/2026-05-26-issue-81-messaging-advanced-design.md`
Issue: https://github.com/bluetape4k/bluetape4k-workshop/issues/81

## Gate State

- Step 2 spec exists and passed Claude Step 2-R.
- Step 2-R artifact: `.omx/artifacts/claude-step-2r-issue-81-spec-5min-final-20260526090610.md`
- Step 3-R must pass with `P0=0` and `P1=0` before implementation.
- Commit this spec and plan before Step 4 implementation.
- Do not stage or commit `.codegraph/`.

## Implementation Sequence

### Task 1: Dependency and API verification

Complexity: small
Risk: build compatibility

1. Confirm `gradle/libs.versions.toml` has:
   - `bluetape4k-dependencies-version = "1.1.3"`
   - `bluetape4k-dependencies` with `version.ref = "bluetape4k-dependencies-version"`
   - `bluetape4k-lettuce` without explicit version
   - `bluetape4k-redisson` without explicit version
2. Add `bluetape4k-kafka4 = { module = "io.github.bluetape4k:bluetape4k-kafka4" }` next to the existing `bluetape4k-kafka` alias if absent.
3. Confirm root build imports `platform(rootLibs.bluetape4k.dependencies)` and Spring Boot 4 BOM.
4. Confirm the resolved `bluetape4k-kafka4` artifact exposes `KafkaOperations<K, V>.suspendSend(...)` compatible with `KafkaTemplate<String, Any>`.
5. Confirm the resolved `bluetape4k-lettuce` artifact provides the selected low-level APIs: `LettuceClients`, `LettuceLongCodec` or `RedisFuture.awaitSuspending()`.
6. Confirm the resolved `bluetape4k-redisson` artifact provides the selected APIs: `redissonClient` or `redissonClientForHighConcurrency`, `RedissonCodecs`, `localCachedMap` or `mapCache`, `streamAddArgsOf`, and coroutine `RFuture` helpers.

Validation:

- Run dependency insight or targeted compile after dependency edits if source inspection is not enough.
- If any selected BT API is missing from the resolved artifact, stop and update this plan/spec before implementation rather than hand-rolling a replacement.

### Task 2: Kafka send path uses `bluetape4k-kafka4`

Complexity: small
Risk: coroutine/send semantics

1. In `messaging/kafka/build.gradle.kts`, replace the commented Spring Kafka 3 alias with `implementation(libs.bluetape4k.kafka4)`.
2. In `GreetingController.kt`, replace both raw `KafkaTemplate.send(...)` calls with `kafkaTemplate.suspendSend(...)`.
3. Import the verified `bluetape4k-kafka4` extension package.
4. Keep controller signatures suspend-aware and do not add `runBlocking`.
5. Do not alter topic names, payload types, or listener behavior.

Validation:

- `./gradlew :messaging-kafka:compileKotlin :messaging-kafka:compileTestKotlin`
- If compile fails due to extension receiver mismatch, revert only the send-path call change, keep the dependency change for review, and update the spec/plan with the resolved API gap.

### Task 3: Kafka request/reply boundary documents `bluetape4k-kafka4`

Complexity: small
Risk: documentation accuracy

1. In `messaging/kafka-reply/build.gradle.kts`, replace the commented Spring Kafka 3 alias with `implementation(libs.bluetape4k.kafka4)`.
2. Keep `PingController` on Spring Kafka `ReplyingKafkaTemplate.sendAndReceive(...)`.
3. Do not invent a Bluetape4k request/reply wrapper unless Task 1 verifies one in the resolved artifact.
4. Update `messaging/kafka-reply/README.md` with a short `Bluetape4k boundary` section:
   - `bluetape4k-kafka4` is present for Spring Kafka 4 coroutine/send helpers.
   - True request/reply remains on `ReplyingKafkaTemplate` because no verified BT request/reply abstraction exists.
   - Existing `onSuccess`, `onFailure`, and coroutine `await()` usage remains part of the BT/coroutine ergonomics story.

Validation:

- `./gradlew :messaging-kafka-reply:compileKotlin :messaging-kafka-reply:compileTestKotlin`
- README review for no false claim that BT replaces `ReplyingKafkaTemplate`.

### Task 4: Redis cluster demo gets executable `bluetape4k-lettuce` usage

Complexity: medium
Risk: Testcontainers and client lifecycle

1. Add `implementation(libs.bluetape4k.lettuce)` to `redis/cluster-demo/build.gradle.kts`.
2. Keep the existing Spring Data Redis cluster path and `RedisClusterServer.Launcher.LettuceLib.clientResources(redisCluster)` unchanged.
3. Add `redis/cluster-demo/src/test/kotlin/io/bluetape4k/workshop/redis/cluster/basic/Bluetape4kLettuceUsageTest.kt` extending `AbstractRedisClusterTest` to exercise a BT Lettuce low-level path against a Testcontainers Redis endpoint.
4. Use verified BT Lettuce APIs, preferring:
   - `LettuceClients` for client/command creation
   - `LettuceLongCodec` for typed numeric values, or `RedisFuture.awaitSuspending()` for coroutine future bridging
5. Client lifecycle must be explicit:
   - create the client in the test scope
   - close stateful connection after use
   - shut down client resources if the BT helper does not own them
6. Keep Testcontainers-backed execution serial; do not introduce parallel test assumptions.
7. Update `redis/cluster-demo/README.md` and `README.ko.md` if present/needed to explain the boundary:
   - Spring Data Redis remains the Redis Cluster proof.
   - `bluetape4k-lettuce` demonstrates typed codecs/client helpers/coroutine support for a scoped low-level path.

Validation:

- `./gradlew :redis-cluster-demo:test`
- The new or changed test must execute the BT Lettuce path, not just compile it.

### Task 5: Redisson examples explicitly foreground `bluetape4k-redisson`

Complexity: medium
Risk: broad test module runtime

1. Add `implementation(libs.bluetape4k.redisson)` to `redis/redisson-examples/build.gradle.kts`.
2. Prefer focused, low-churn changes in existing tests:
   - update `AbstractRedissonTest` only if `redissonClient {}` or `redissonClientForHighConcurrency(...)` is compatible with the current shared lifecycle and preserves `ShutdownQueue.register { shutdown() }`.
   - update `collections/LocalCachedMapExamples.kt` to use the verified BT `localCachedMap(...)` or `mapCache(...)` helper instead of raw `redisson.getLocalCachedMap(options)` when the helper supports the same options.
   - update `collections/StreamExamples.kt` to use the verified BT `streamAddArgsOf(...)` helper for append arguments.
   - keep `RedissonCodecs` usage visible through `AbstractRedissonTest` or the touched examples.
3. Keep raw Redisson APIs where the example is teaching raw Redisson object behavior.
4. Ensure client lifecycle remains explicit in `AbstractRedissonTest` or the touched test:
   - create once per test class/scope
   - close/shutdown in the existing lifecycle hook
5. Update `redis/redisson-examples/README.md` and `README.ko.md` if present/needed with concrete used-feature rows for `bluetape4k-redisson`.

Validation:

- Prefer `./gradlew :redis-redisson-examples:test`.
- If the full module is too broad or slow, run a narrower Gradle test slice that includes the changed BT-backed example and record the exact command.
- Compile-only proof is not enough for the new active `bluetape4k-redisson` usage.

### Task 6: Distributed lock cleanup and README parity

Complexity: small
Risk: documentation/test assertion drift

1. Replace `kotlin.test.assertFailsWith` with the approved `io.bluetape4k.assertions.assertFailsWith` import in:
   - `redis/distributed-lock/src/test/kotlin/io/bluetape4k/workshop/lock/DistributedLockTest.kt`
   - `redis/distributed-lock/src/test/kotlin/io/bluetape4k/workshop/lock/FencedStaleHolderTest.kt`
   - `redis/distributed-lock/src/test/kotlin/io/bluetape4k/workshop/lock/LockFailureTest.kt`
2. Preserve `SuspendFencedLockTest.kt` comment semantics:
   - `totalUnits = rounds * blockCount`
   - `workers` controls concurrency only
   - `workers(20).rounds(20)` with one block means 20 attempts
3. Update `redis/distributed-lock/README.md`:
   - replace `SuspendedJobTester.workers(N).rounds(R) launches N workers each running R rounds of the add { } block` with: `SuspendedJobTester.workers(N).rounds(R) spawns N concurrent workers that cooperatively execute R rounds of each add { } block. totalUnits = R x blockCount.`
   - replace `workers x rounds = total attempts` with `rounds x blockCount = total attempts`
   - change the Before/After example from `.rounds(1) // ... 20 total attempts` to `.rounds(20) // 20 total attempts, exactly 10 succeed`
   - state `workers(20).rounds(20) -> 20 total attempts -> exactly 10 succeed`
4. Apply the same semantic correction in `redis/distributed-lock/README.ko.md`.
   - replace `N개의 워커가 각각 R라운드의 add { } 블록을 실행합니다` with: `N개의 동시 워커가 협력하여 각 add { } 블록을 R라운드 실행합니다. totalUnits = R x blockCount.`
5. Bring `README.ko.md` into parity for touched dependency and BT feature table content.
6. Keep `MultithreadingTester` wording separate from `SuspendedJobTester`; `MultithreadingTester` examples such as `DistributedLockTest` may still use `workers x rounds` semantics and must not be rewritten as if they were `SuspendedJobTester`.

Validation:

- `./gradlew :redis-distributed-lock:test`
- Confirm no touched test imports `kotlin.test.assertFailsWith`.

### Task 7: README and diagram check

Complexity: small
Risk: user-facing drift

1. Review README pairs for touched modules:
   - `messaging/kafka/README.md` and localized variants if present
   - `messaging/kafka-reply/README.md` and localized variants if present
   - `redis/cluster-demo/README.md` and `README.ko.md` if present
   - `redis/redisson-examples/README.md` and `README.ko.md` if present
   - `redis/distributed-lock/README.md` and `README.ko.md`
2. Add or update `Used Bluetape4k Features` rows only where the code path actually uses the named module.
3. Do not update existing diagram images unless the event or lock/cache flow changes materially. The planned code changes are API-adoption and documentation-boundary changes, not new flow semantics.
4. Keep public-facing README text in the existing language of each file.

Validation:

- Manual content review.
- `git diff --check`.

### Task 8: Full targeted verification

Complexity: medium
Risk: Docker/Testcontainers runtime

Run commands serially:

```bash
./gradlew :messaging-kafka:compileKotlin :messaging-kafka:compileTestKotlin
./gradlew :messaging-kafka-reply:compileKotlin :messaging-kafka-reply:compileTestKotlin
./gradlew :redis-cluster-demo:test
./gradlew :redis-distributed-lock:test
./gradlew :redis-redisson-examples:test
git diff --check
```

If `:redis-redisson-examples:test` is too broad or unstable, run a narrower `--tests` command that executes the changed BT-backed example and record the limitation in the final report and PR.

### Task 9: Review, lesson, and PR

Complexity: small
Risk: workflow compliance

1. Run Step 6-R code review gate with Claude Code CLI artifact and integrated P0/P1 table.
2. Fix any P0/P1 findings and rerun affected verification.
3. Add `docs/lessons/2026-05-26-issue-81-messaging-advanced.md` with:
   - context
   - decisions
   - outcome
   - verification evidence
   - future-agent guidance
4. Commit implementation and lesson with Lore protocol trailers.
5. Create a GitHub PR assigned to `debop`, referencing `Fixes #81`.
6. Do not auto-merge; report PR URL and CI status.

## Review Log

### Step 3-R Codex Perspectives

Artifact: `.omx/artifacts/codex-step-3r-issue-81-plan-perspectives-20260526091300.md`

Result: `P0=0 P1=0 P2=8 P3=0`

### Step 3-R Claude Code Opus Advisor

Initial artifact: `.omx/artifacts/claude-step-3r-issue-81-plan-5min-20260526091049.md`

Initial result: `P0=0 P1=1`; accepted the finding that README prose still described `SuspendedJobTester` as each worker running every round.

Final artifact: `.omx/artifacts/claude-step-3r-issue-81-plan-5min-final-20260526091541.md`

Final result: `P0=0 P1=0 Verdict=PASS`

### Step 3-R Integration

Step 3-R is closed. Required P1 plan edit was applied in Task 6:

- Exact English and Korean README wording replacements for cooperative `SuspendedJobTester` workers.
- Explicit separation from `MultithreadingTester` semantics.

No P0/P1 findings remain.
