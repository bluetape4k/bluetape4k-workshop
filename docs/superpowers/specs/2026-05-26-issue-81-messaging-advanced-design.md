# Issue #81 Messaging/Redis Advanced Design

Date: 2026-05-26
Repository: `bluetape4k-workshop`
Branch: `feat/issue-81-messaging-advanced`
Issue: https://github.com/bluetape4k/bluetape4k-workshop/issues/81

## Problem

Issue #81 asks for production-shaped messaging and distributed-state examples across:

- `messaging/kafka`
- `messaging/kafka-reply`
- `redis/cluster-demo`
- `redis/redisson-examples`

The examples must demonstrate request/reply, retry or replay behavior, distributed lock correctness, Redis cluster behavior, failure-path tests, event-flow and lock/cache coordination diagrams, Testcontainers or local-service requirements, and a Bluetape4k-first explanation.

The user also explicitly tightened the requirement: actively use the Bluetape4k Kafka, Lettuce, and Redisson modules, not just raw framework APIs or Testcontainers helpers. In this Spring Kafka 4 workshop, that Kafka requirement maps to the compatible `bluetape4k-kafka4` artifact, not the Spring Kafka 3.x `bluetape4k-kafka` artifact.

## Current Evidence

### Prior work

`docs/lessons/2026-05-24-issue-81-messaging-advanced.md` records earlier README strengthening for `redis/cluster-demo` and `redis/distributed-lock`; it also notes that `messaging/kafka`, `messaging/kafka-reply`, and `redis/redisson-examples` were partly covered in Issue #83.

Current docs already include several diagrams and BT feature tables, but current build files show underuse of the requested BT modules:

- `messaging/kafka/build.gradle.kts` has the Spring Kafka 3.x `implementation(libs.bluetape4k.kafka)` alias commented out.
- `messaging/kafka-reply/build.gradle.kts` has the Spring Kafka 3.x `implementation(libs.bluetape4k.kafka)` alias commented out.
- `redis/cluster-demo/build.gradle.kts` uses raw `lettuce.core` but does not declare `bluetape4k-lettuce`.
- `redis/redisson-examples/build.gradle.kts` uses `bluetape4k-redis` and raw Redisson, but does not declare `bluetape4k-redisson`.
- `redis/distributed-lock/build.gradle.kts` already declares `bluetape4k-redis` and `bluetape4k-redisson`.
- `gradle/libs.versions.toml` already declares `bluetape4k-kafka`, `bluetape4k-lettuce`, and `bluetape4k-redisson` aliases without explicit versions, but it does not yet declare the needed Spring Kafka 4-compatible `bluetape4k-kafka4` alias.
- The actual imported BOM is `io.github.bluetape4k:bluetape4k-dependencies:1.1.3`, not the stale values mentioned in older guidance text.

### CodeGraph and source inspection

CodeGraph was initialized for this worktree and reports 1,403 indexed files, 17,552 nodes, and 34,425 edges. Relevant current entry points are:

- `messaging/kafka/src/main/kotlin/io/bluetape4k/workshop/messaging/kafka/controller/GreetingController.kt`
- `messaging/kafka/src/main/kotlin/io/bluetape4k/workshop/messaging/kafka/listener/GreetingMessageHandler.kt`
- `messaging/kafka-reply/src/main/kotlin/io/bluetape4k/workshop/kafka/ping/PingController.kt`
- `messaging/kafka-reply/src/main/kotlin/io/bluetape4k/workshop/kafka/pong/PongHandler.kt`
- `redis/cluster-demo/src/main/kotlin/io/bluetape4k/workshop/redis/cluster/RedisClusterApplication.kt`
- `redis/cluster-demo/src/main/kotlin/io/bluetape4k/workshop/redis/cluster/service/NumberService.kt`
- `redis/distributed-lock/src/main/kotlin/io/bluetape4k/workshop/lock/service/SuspendingFencedInventoryService.kt`
- `redis/redisson-examples/src/test/kotlin/io/bluetape4k/workshop/redisson/AbstractRedissonTest.kt`

Current usage:

- Kafka modules already use `KafkaServer.Launcher.kafka`, but main send paths still call raw `KafkaTemplate.send(...)`.
- `PingController` uses `ReplyingKafkaTemplate.sendAndReceive(...)`, Bluetape4k `onSuccess` / `onFailure`, and coroutine `await()`.
- `RedisClusterApplication` uses `RedisClusterServer.Launcher.redisCluster` and `RedisClusterServer.Launcher.LettuceLib.clientResources(redisCluster)`.
- `NumberService` uses Spring Data Redis `clusterConnection` and Bluetape4k byte conversion helpers, but not `bluetape4k-lettuce` APIs.
- `SuspendingFencedInventoryService` uses `RedissonClient.getLockId`, `tryLockAsync(..., lockId)`, `tokenAsync.await()`, and `withContext(NonCancellable)` around `unlockAsync(lockId).await()`.
- `redis/redisson-examples` uses `RedissonCodecs`, `streamAddArgsOf`, and coroutine `getLockId`, but the artifact dependency should be made explicit through `bluetape4k-redisson`.

### Bluetape4k API evidence from sibling source

Current sibling library source under `bluetape4k-projects` shows:

- `bluetape4k-kafka4` provides Spring Kafka 4-compatible `KafkaOperations.suspendSend(...)` extensions and `sendFlowAsParallel(...)` / `sendAndForget(...)` flow helpers.
- `bluetape4k-kafka4` also provides `KafkaCodecs` for string, byte array, Jackson, Kryo, Fory, and compressed binary codecs.
- `bluetape4k-lettuce` provides `LettuceClients`, `LettuceLongCodec`, `LettuceJsonCodecs`, `LettuceBinaryCodec`, `RedisFuture.awaitSuspending()`, `awaitAll()`, `withPipeline(...)`, `LettuceLock`, `LettuceSuspendLock`, and `LettuceSuspendAtomicLong`.
- `bluetape4k-redisson` provides `redissonClient {}`, `redissonClientForHighConcurrency(...)`, `RedissonCodecs`, `RedissonCacheConfig`, `localCachedMap(...)`, `mapCache(...)`, `streamAddArgsOf(...)`, and coroutine `RFuture` helpers.

### Current test and doc drift

Existing Redis distributed-lock tests already cover over-sell prevention, cancellation-safe unlock, stale-token rejection, and lock-acquisition failure. However:

- `DistributedLockTest.kt` imports `kotlin.test.assertFailsWith`; new changes should use Bluetape4k assertion APIs.
- `SuspendFencedLockTest.kt` correctly describes `SuspendedJobTester().workers(20).rounds(20)`: `totalUnits = rounds x blockCount`; `workers` controls concurrency only.
- `redis/distributed-lock/README.md` and `README.ko.md` contain independent `SuspendedJobTester` drift in the Before/After example: they imply `workers x rounds` or pair `.rounds(1)` with "20 attempts"; the correct formula is `rounds x blockCount`.
- `redis/distributed-lock/README.ko.md` lacks full parity with the English README's `Used bluetape4k Features` and Before/After content.

## External Reference Evidence

Context7 was attempted for Spring Kafka, Redisson, and Spring Data Redis, but returned a monthly quota error. Official web fallback was used:

- Spring Kafka documents `ReplyingKafkaTemplate.sendAndReceive(...)`, `sendFuture`, reply timeout behavior, and `@SendTo` request/reply usage.
- Redisson documents `RFencedLock` fencing tokens and stale-token rejection by guarded services.
- Spring Data Redis documents Redis Cluster configuration and cluster behavior.
- Lettuce documents adaptive topology refresh triggers and dynamic refresh sources.

## Constraints

- Use `bluetape4k-workflow` Type A Full Design and `bluetape4k-design` strictly.
- Actively use `bluetape4k-kafka4`, `bluetape4k-lettuce`, and `bluetape4k-redisson` in happy paths or executable smoke/test paths.
- Do not add a generic framework wrapper when an existing Bluetape4k utility exists.
- Do not commit `.codegraph/`; it is generated local index state.
- Keep public GitHub, PR, commit, and KDoc artifacts in English.
- Internal specs, plans, and lessons may be Korean or English; this spec uses English for compact review.
- Testcontainers-backed tests must run serially.
- README changes must preserve localized README parity where localized files already exist.
- No new dependency versions: use existing aliases governed by `bluetape4k-dependencies` BOM and repo-local catalog aliases.

## Pre-Implementation Verification Gates

Before implementation starts, the plan must verify these API and dependency facts:

1. **Version catalog and BOM**
   - Confirm `libs.bluetape4k.kafka4`, `libs.bluetape4k.lettuce`, and `libs.bluetape4k.redisson` exist in `gradle/libs.versions.toml`.
   - Add `bluetape4k-kafka4 = { module = "io.github.bluetape4k:bluetape4k-kafka4" }` if the alias is absent.
   - Confirm those aliases have no explicit version.
   - Confirm the root build imports `platform(libs.bluetape4k.dependencies)`.
   - Confirm the resolved BOM version from this repo's catalog is `bluetape4k-dependencies-version = "1.1.3"`.

2. **Kafka extension receiver**
   - Confirm the resolved `bluetape4k-kafka4` artifact exposes `suspendSend` on a receiver type compatible with `KafkaTemplate<String, Any>`.
   - Sibling source shows `KafkaOperations<K, V>.suspendSend(...)`; the implementation must verify that `KafkaTemplate` implements `KafkaOperations` for the resolved Spring Kafka version.
   - If the resolved artifact lacks the extension, do not hand-roll a replacement under the Bluetape4k name. Record the gap and keep the existing raw Spring Kafka path until an upstream issue exists.

3. **Lettuce cluster boundary**
   - Confirm whether `bluetape4k-lettuce` exposes Redis Cluster-aware helpers.
   - Sibling source evidence currently shows `RedisClient`-oriented `LettuceClients`, codecs, locks, atomic longs, and future helpers. Unless a cluster-aware helper is verified, the BT Lettuce adoption must be a scoped low-level Redis path, while Spring Data Redis keeps the Redis Cluster example.

4. **Redisson helper compatibility**
   - Confirm `bluetape4k-redisson` provides the intended helpers in the resolved artifact: `redissonClient {}`, `RedissonCodecs`, `localCachedMap(...)`/`mapCache(...)`, `streamAddArgsOf(...)`, and coroutine future helpers.

## Design Options

### Option A: Dependency-only documentation cleanup

Scope:

- Uncomment/add the requested BT module dependencies.
- Update README tables and fix test comments/docs.

Pros:

- Lowest risk.

Cons:

- Fails the user's explicit "actively use" requirement because artifacts would appear without meaningful happy-path usage.

### Option B: Focused BT-module adoption in existing examples

Scope:

- `messaging/kafka`: depend on `bluetape4k-kafka4` and replace raw `KafkaTemplate.send(...)` calls in `GreetingController` with `KafkaOperations.suspendSend(...)`.
- `messaging/kafka-reply`: depend on `bluetape4k-kafka4`; keep `ReplyingKafkaTemplate.sendAndReceive(...)` for true request/reply because the current BT API evidence does not show a direct request/reply replacement. Document the gap honestly and keep BT callback/coroutine helpers in the path.
- `redis/cluster-demo`: depend on `bluetape4k-lettuce` and add a small Lettuce-backed cluster support path, such as using `LettuceClients`, `LettuceLongCodec`, and `awaitSuspending()` in a new service/test that exercises a Testcontainers Redis endpoint. Keep existing Spring Data Redis cluster behavior as the main cluster example when cluster-aware BT Lettuce wrappers are not present.
- `redis/redisson-examples`: depend on `bluetape4k-redisson` explicitly and update examples/docs to foreground `RedissonCodecs`, `redissonClient {}` or high-concurrency client helpers, `localCachedMap(...)`/`mapCache(...)`, `streamAddArgsOf(...)`, and coroutine helpers where they already fit.
- `redis/distributed-lock`: keep existing `bluetape4k-redisson`/`bluetape4k-redis` lock path; fix assertion style and `SuspendedJobTester` semantics in code comments and README pairs.

Pros:

- Satisfies the explicit BT-module adoption request without large new subsystems.
- Improves executable proof rather than only README text.
- Reuses current module boundaries and examples.

Cons:

- Adds cross-module Gradle verification surface.
- Requires careful distinction between `bluetape4k-lettuce` single-node utilities and the existing Redis Cluster example.

### Option C: New combined Kafka + Redis orchestration subsystem

Scope:

- Add a new advanced module or broad scenario combining Kafka event replay, Redis locks, cluster writes, and Redisson near-cache coordination.

Pros:

- Broadest possible interpretation of #81.

Cons:

- Too much churn for an issue that already has most examples in place.
- Higher risk of generic hand-rolled code.
- Would need CI/nightly/module registration checks and larger review scope.

## Selected Approach

Select Option B.

Option A is rejected because it would not meet the user's explicit "actively use" requirement. Option C is rejected because current evidence supports focused adoption inside existing examples.

## Required Behavior Changes

### Kafka

- Add `implementation(libs.bluetape4k.kafka4)` to `messaging/kafka`.
- Replace raw fire-and-forget `kafkaTemplate.send(...)` calls in `GreetingController` with `kafkaTemplate.suspendSend(...)`.
- Use the exact import and receiver confirmed by the pre-implementation gate.
- Verify controller tests or compile tests prove the BT-backed path.
- Add `implementation(libs.bluetape4k.kafka4)` to `messaging/kafka-reply`.
- Keep request/reply on `ReplyingKafkaTemplate.sendAndReceive(...)`; if no BT request/reply extension exists, document this as an intentional boundary rather than inventing a wrapper.
- Add a `Bluetape4k boundary` section to `messaging/kafka-reply/README.md` explaining that request/reply remains on Spring Kafka's `ReplyingKafkaTemplate` because no verified `bluetape4k-kafka4` request/reply abstraction exists in the resolved artifact.

### Lettuce

- Add `implementation(libs.bluetape4k.lettuce)` to `redis/cluster-demo`.
- Add a focused executable test path using `LettuceClients` plus a BT codec or coroutine future helper. The intended test shape is a new cluster-demo test that uses the Testcontainers Redis endpoint, `LettuceClients`, `LettuceLongCodec` or `RedisFuture.awaitSuspending()`, and verifies a write/read round trip.
- Do not replace Spring Data Redis cluster behavior with a non-cluster-aware helper unless source evidence proves the BT helper supports Redis Cluster.
- Document the boundary: `RedisClusterServer.Launcher.LettuceLib` configures Testcontainers cluster client resources; `bluetape4k-lettuce` provides typed codecs, client helpers, and coroutine future support for low-level Redis operations.

### Redisson

- Add `implementation(libs.bluetape4k.redisson)` to `redis/redisson-examples`.
- Prefer BT Redisson helpers in examples where straightforward:
  - `redissonClient {}` or `redissonClientForHighConcurrency(...)` for client construction.
  - `RedissonCodecs.*` for codec selection.
  - `localCachedMap(...)` or `mapCache(...)` for options-heavy cache examples.
  - `streamAddArgsOf(...)` for stream append examples.
  - coroutine `RFuture` helpers where collections of futures are awaited.
- Keep raw Redisson API where the example specifically teaches a raw Redisson object and no BT helper is appropriate; document the boundary.

### Distributed Lock Cleanup

- Replace `kotlin.test.assertFailsWith` with the approved Bluetape4k assertion import in all currently affected distributed-lock tests:
  - `redis/distributed-lock/src/test/kotlin/io/bluetape4k/workshop/lock/DistributedLockTest.kt`
  - `redis/distributed-lock/src/test/kotlin/io/bluetape4k/workshop/lock/FencedStaleHolderTest.kt`
  - `redis/distributed-lock/src/test/kotlin/io/bluetape4k/workshop/lock/LockFailureTest.kt`
- Correct `SuspendedJobTester` comments and README semantics:
  - `workers(N).rounds(R).add { ... }` means `R * blockCount` total executions; `workers` controls concurrency only.
  - `workers(20).rounds(20).add { ... }` means 20 attempts for one registered block, not 400.
  - Keep `workers(20).rounds(20)` as a 20-attempt concurrent stress proof. The expected successful deductions remain 10 because stock is 100 and qty is 10; the remaining attempts must fail without over-sell.
  - In `README.md`, replace `SuspendedJobTester.workers(N).rounds(R) launches N workers each running R rounds` with wording that `rounds(R)` controls total executions per registered block and `workers(N)` controls concurrency.
  - In `README.md`, replace `fixed workers x rounds = total attempts` with `fixed rounds x blockCount = total attempts`, and change the Before/After example from `.rounds(1) // ... 20 total attempts` to `.rounds(20) // 20 total attempts, exactly 10 succeed`.
  - Apply the same Korean wording correction in `README.ko.md`: `rounds(R)`는 등록된 `add { }` 블록당 총 실행 횟수이고 `workers(N)`는 동시성만 제어한다고 설명한다.
  - In both README files, keep the summary example as `workers(20).rounds(20) -> 20 total attempts -> exactly 10 succeed`.
- Bring `README.ko.md` into parity with the English README's BT feature coverage and dependencies.

## Risks and Failure Modes

1. **BT API compatibility risk**: `bluetape4k-kafka4`, `bluetape4k-lettuce`, or `bluetape4k-redisson` APIs may differ between sibling source and the version resolved by this workshop.
   - Mitigation: compile affected modules before claiming implementation. Use source evidence only as design input, not proof.

2. **Cluster boundary risk**: `bluetape4k-lettuce` helpers may be single-node utilities while the issue asks for Redis cluster behavior.
   - Mitigation: keep existing Spring Data Redis cluster tests and use BT Lettuce for a clearly scoped low-level path unless a cluster-aware BT API is verified.

3. **Kafka request/reply overreach**: There may be no BT request/reply abstraction over `ReplyingKafkaTemplate`.
   - Mitigation: keep Spring Kafka request/reply as the protocol primitive, use BT helpers where they exist, and document the unsupported BT gap explicitly.

4. **Serialization security risk**: Kafka/Redis codec examples can accidentally imply unsafe polymorphic deserialization.
   - Mitigation: prefer type-specific codec examples and document codec boundaries. Do not add open polymorphic deserialization or type-header deserialization without an explicit allow-list.

5. **Testcontainers flakiness**: Kafka/Redis tests need Docker and must not run concurrently.
   - Mitigation: run targeted Testcontainers-backed Gradle commands serially.

6. **Generated local index contamination**: `.codegraph/` may appear in `git status`.
   - Mitigation: exclude `.codegraph/` from commits and verify staged files before commit.

## Acceptance Criteria Mapping

| Issue criterion | Current evidence | Planned closure |
|---|---|---|
| Request/reply | `PingController`, `PongHandler`, Kafka reply README and diagram | Keep Spring Kafka request/reply; add BT dependency and document BT boundary |
| Retry or replay behavior | Redisson stale-token rejection and read/write-through examples | Keep and strengthen Redisson docs/tests; do not fake retry if unsupported |
| Distributed lock correctness | `DistributedLockTest`, `FencedLockTest`, `SuspendFencedLockTest` | Fix assertion style and `SuspendedJobTester` semantics; rerun tests |
| Redis cluster behavior | `RedisClusterApplication`, `NumberServiceTest`, cluster README | Keep cluster proof; add scoped BT Lettuce usage |
| Failure-path tests | Lock acquisition failure, stale token rejection, cancellation unlock, insufficient stock | Preserve and verify |
| Sequence diagrams | Existing Kafka reply, Kafka, Redis cluster, distributed lock, Redisson diagram assets | Update docs only if new flow changes require it |
| Testcontainers/local prerequisites | Existing READMEs document Docker/Testcontainers | Keep and update where BT module usage changes commands |
| Used Bluetape4k features table | Present but incomplete or drifted in places | Add `bluetape4k-kafka4`, `bluetape4k-lettuce`, `bluetape4k-redisson` rows with real code references |
| BT-backed smoke/tests | Redis lock/cluster tests and Kafka controller paths | Ensure tests or compile checks exercise BT-backed code |

## Definition of Done

- Spec and plan exist under `docs/superpowers/`.
- Step 2-R and Step 3-R pass with Claude Code CLI artifacts and P0/P1 = 0.
- `messaging/kafka` uses `bluetape4k-kafka4` APIs in the send path.
- `messaging/kafka-reply` declares and documents `bluetape4k-kafka4` usage/boundary without replacing true request/reply incorrectly.
- `redis/cluster-demo` declares and exercises `bluetape4k-lettuce` in a scoped low-level Redis path while preserving Redis Cluster tests.
- `redis/redisson-examples` declares and foregrounds `bluetape4k-redisson` helpers.
- Any narrower `redis-redisson-examples` test slice must execute the added or changed BT-backed code path; compile-only proof is insufficient for active usage.
- `redis/distributed-lock` comments and README pairs correctly describe `SuspendedJobTester` semantics.
- `redis/distributed-lock/README.ko.md` is brought into parity with the English README where touched.
- `kotlin.test.assertFailsWith` is removed from touched tests.
- Targeted verification passes serially:
  - `./gradlew :messaging-kafka:compileKotlin :messaging-kafka:compileTestKotlin`
  - `./gradlew :messaging-kafka-reply:compileKotlin :messaging-kafka-reply:compileTestKotlin`
  - `./gradlew :redis-cluster-demo:test`
  - `./gradlew :redis-distributed-lock:test`
  - `./gradlew :redis-redisson-examples:test` or a narrower test slice if full module runtime is too broad, with the gap recorded
- `git diff --check` passes.
- Step 6-R code review gate passes with P0/P1 = 0.
- Lesson file is written and committed before PR creation.
- PR references `Fixes #81`, includes DoD evidence, and is assigned to `debop`.
- CI passes before any merge request.
