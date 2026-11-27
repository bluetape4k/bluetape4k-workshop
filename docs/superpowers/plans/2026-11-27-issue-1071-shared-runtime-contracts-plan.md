# shared runtime 공통 계약 구현 계획

_Date: 2026-11-27 | Issue #1071 | Spec: `docs/superpowers/specs/2026-11-27-issue-1071-shared-runtime-contracts-design.md`_

## 실행 원칙

- 기존 PR #1070과 별도 branch/worktree에서 실행한다.
- 각 task는 TDD RED → GREEN → REFACTOR 순서로 진행한다.
- 순수 core를 먼저 만들고 Redis/Testcontainers adapter와 consumer migration을
  순차 실행한다.
- container test는 `--max-workers=1`로 직렬 실행한다.
- public API가 확정되기 전에는 consumer migration을 시작하지 않는다. 외부 타입을
  쓰는 production adapter는 `shared/src/main`에 두되 `compileOnlyApi`와 consumer의
  explicit runtime 선언을 함께 검증한다.

## PR train

| PR | 책임 | 선행 |
|---|---|---|
| P1 | shared production core/API/SPI | 없음 |
| P2 | Redis lifecycle·Lettuce·Testcontainers adapter | P1 |
| P3 | commerce fingerprint 소비자 이전 | P1 |
| P4 | optimization primitive 소비자 이전 | P1 |
| P5 | image security policy 소비자 이전 | P1 |
| P6 | Netty timeout adapter와 11개 소비자 이전 | P1 |
| P7 | ABI/compile/container smoke, 문서, upstream 승격 근거 | P2~P6 |

## P1 — shared production core/API/SPI

### T1 — fingerprint contract (RED → GREEN)

**Files**

- Create: `shared/src/main/kotlin/io/bluetape4k/workshop/shared/fingerprint/FingerprintEncoder.kt`
- Test: `shared/src/test/kotlin/io/bluetape4k/workshop/shared/fingerprint/FingerprintEncoderTest.kt`

**Acceptance**

- NUL domain framing, raw UTF-8 SHA-256 hex, HMAC-SHA256 URL-safe base64 primitive을
  제공한다.
- 기존 ticket/reservation/order golden vector를 test resource 또는 parameterized
  test로 고정한다.
- canonical JSON parser나 repository 상태를 shared에 추가하지 않는다.

### T2 — sparse matrix contract

**Files**

- Create: `shared/src/main/kotlin/io/bluetape4k/workshop/shared/optimization/TravelTimeMatrix.kt`
- Test: `shared/src/test/kotlin/io/bluetape4k/workshop/shared/optimization/TravelTimeMatrixTest.kt`

**Acceptance**

- revision/coordinate/edge 검증, O(1) lookup, edge count, deterministic digest를 제공한다.
- empty/limit 정책은 `TravelTimeMatrixLimits`로 호출자가 선택한다.
- 기존 두 planner의 domain exception은 adapter에서 변환한다.

### T3 — image security policies

**Files**

- Create: `shared/src/main/kotlin/io/bluetape4k/workshop/shared/image/SafeFilenamePolicy.kt`
- Create: `shared/src/main/kotlin/io/bluetape4k/workshop/shared/image/PublicBaseUriPolicy.kt`
- Test: `shared/src/test/kotlin/io/bluetape4k/workshop/shared/image/*Test.kt`

**Acceptance**

- path segment 제거, unsafe character 치환, bounded filename을 보장한다.
- scheme/host/userInfo/query/fragment/traversal/encoded dot/HTTPS 및 local storage
  remote URL 조건을 검증한다.
- profile path authorization은 포함하지 않는다.

### T4 — Netty timeout policy와 runtime adapter

**Files**

- Create: `shared/src/main/kotlin/io/bluetape4k/workshop/shared/netty/NettyTimeoutPolicy.kt`
- Create: `shared/src/main/kotlin/io/bluetape4k/workshop/shared/netty/NettyTimeoutHandlers.kt`
- Test: `shared/src/test/kotlin/io/bluetape4k/workshop/shared/netty/NettyTimeoutPolicyTest.kt`

**Acceptance**

- read/write `Duration`가 finite·positive인지 검증한다.
- 10초 기본 정책과 30초 WebSocket 정책을 값 객체로 표현한다.
- 순수 policy는 Netty/Reactor 타입을 요구하지 않는다. handler 설치는 명시적
  production runtime adapter가 담당하고, 소비자별 loop/pool/SO_LINGER 값은 유지한다.

## P2 — Redis runtime adapter

### T5 — endpoint/owner lifecycle contract

**Files**

- Create: `shared/src/main/kotlin/io/bluetape4k/workshop/shared/redis/OwnedRedisRuntime.kt`
  (`RedisEndpoint`와 lifecycle SPI 포함)
- Test: `shared/src/test/kotlin/io/bluetape4k/workshop/shared/redis/*LifecycleTest.kt`

**Acceptance**

- explicit `start`, idempotent `close`, partial-start rollback, reverse close를 검증한다.
- endpoint property key를 기존 `testcontainers.redis.*`와 호환한다.

### T6 — production Testcontainers runner and Lettuce bridge

**Files**

- Create: `shared/src/main/kotlin/io/bluetape4k/workshop/shared/redis/TestcontainersRedisRuntime.kt`
- Create: `shared/src/main/kotlin/io/bluetape4k/workshop/shared/redis/LettuceRedisClientFactory.kt`
- Modify: `shared/build.gradle.kts`
- Test: `shared/src/test/kotlin/io/bluetape4k/workshop/shared/redis/*Test.kt`

**Acceptance**

- upstream `RedisServer`와 `LettuceClients`를 위임 사용한다.
- `RedisClient`, connection, `ClientResources`의 close 책임을 숨기지 않는다.
- Testcontainers/Spring 의존성이 unrelated runtime consumer에 전이되지 않는다.
- production main API의 외부 타입은 `compileOnlyApi`로 노출하고, 실제 사용 모듈이
  runtime 의존성을 명시한다.

## P3 — commerce fingerprint migration

**Targets**

- `commerce/concert-ticket-flash-sale`
- `commerce/reservation-control-plane`
- `commerce/order-lifecycle-fulfillment`
- usage metering/billing idempotency consumers

**Acceptance**

- local implementation은 shared encoder adapter로 대체한다.
- 기존 golden vector가 byte-for-byte 동일하다.
- persisted fingerprint migration이 필요하면 별도 issue로 분리하고 이번 PR에서 format을
  바꾸지 않는다.

## P4 — optimization migration

**Targets**

- `optimization/field-service-dispatch`
- `optimization/last-mile-routing`

**Acceptance**

- matrix lookup/digest/order primitive만 shared를 사용한다.
- domain limits, exceptions, planner algorithms, proposal models는 유지한다.
- 기존 complexity/metrics contract를 보존한다.

## P5 — image security migration

**Targets**

- `image-processing/advanced-workflow`
- `image-processing/profile-image-moderation`

**Acceptance**

- filename/base URI 중복 구현을 shared policy로 대체한다.
- profile private/public/pending/default path authorization은 local policy로 남긴다.
- traversal, encoded dot, userInfo, query, fragment, insecure remote URL adversarial test를 유지한다.

## P6 — Netty migration

**Targets**

- 11개 `NettyConfig.kt` 소비자

**Acceptance**

- read/write timeout만 shared policy/customizer를 사용한다.
- 10초/30초 값, `SO_LINGER`, event-loop 수, connection pool 크기를 보존한다.
- 각 모듈의 compile/test smoke를 통과한다.

## P7 — verification and handoff

**Commands**

```bash
./gradlew :shared:test --console=plain
./gradlew :shared:compileTestFixturesKotlin --console=plain
./gradlew :shared:dependencies --configuration testFixturesRuntimeClasspath --console=plain
./gradlew :shared:dependencies --configuration compileOnlyApi --console=plain
./gradlew detekt --console=plain
./gradlew :shared:jar --console=plain
javap -public -classpath shared/build/libs/shared.jar io.bluetape4k.workshop.shared.*
git diff --check
```

영향 모듈은 변경된 slice별 targeted test와 compile smoke를 먼저 실행하고, 마지막에
container-backed workflow를 직렬 실행한다. 실패 시 해당 PR train slice만 rollback한다.

## Rollback

core API와 adapter를 독립 커밋으로 유지한다. consumer migration 실패 시 adapter와
consumer commit을 되돌리고 local implementation을 복원한다. 저장된 fingerprint format과
기존 URL 경로는 migration 없이 변경하지 않는다.

## Plan DoD

- [x] P1~P6 acceptance를 fresh command output으로 확인한다.
- [x] public API와 dependency graph read-back을 남긴다.
- [ ] PR별 exact-head CI/check/review/thread 상태를 다시 읽는다.
- [ ] upstream `bluetape4k-*` 승격 후보와 보류 이유를 issue #1071에 기록한다.

### 2026-11-27 검증 기록

- shared 신규 계약 targeted test: 21건 통과, filename golden 보정 후 4건 통과.
- commerce·optimization·image 소비자 golden test: 전체 지정 slice 통과.
- Netty 소비자 11개 `testClasses`: `BUILD SUCCESSFUL`.
- `:shared:jar`, `javap -public`, `compileOnlyApi` dependency read-back: 통과.
- `git diff --check`: 통과.
- `:shared:test` 전체 36건 중 기존 `shared.web` 3건은 공개되지 않은
  `bluetape4k/mock-web-server:2.2.0` 이미지 pull 실패로 초기화 실패했다. 신규
  승격 계약에는 영향이 없어 targeted test로 분리 확인했다.
- 루트 `detekt`는 이번 변경과 무관한 기존 commerce 모듈 issue로 실패했다.

## SPW writer self-review

- [x] SPW-01: Issue/spec/plan/source/reader를 고정했다.
- [x] SPW-02: task dependency, files, tests, rollback, DoD를 명시했다.
- [x] SPW-03: 한국어 technical register를 적용했다.
- [x] SPW-04: source와 계획의 API/의존성 경계를 대조했다.
- [x] SPW-05: plan을 read-back했다.
- [x] KO-01~KO-07: 의미 보존·용어·목록·링크 표면을 점검했다.
