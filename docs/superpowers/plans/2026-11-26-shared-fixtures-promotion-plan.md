# 교차 주제 테스트 fixture 승격 실행 계획

## 실행 기준

- 설계: [`2026-11-26-shared-fixtures-promotion-design.md`](../specs/2026-11-26-shared-fixtures-promotion-design.md)
- 이슈: [#1069](https://github.com/bluetape4k/bluetape4k-workshop/issues/1069)
- 실행 브랜치: `feat/shared-fixtures-promotion`
- 첫 단계: `OwnedRedisNamespace`만 공통 `shared` test fixture로 추출
- 제외: `MutableClock`, `WorkerPid`, artifact/journal primitive, `bluetape4k-*` publication

이 계획은 기존 두 구현의 동작을 먼저 소비자 테스트로 고정한 뒤 공통 구현으로 바꾸는 순서다. 공통 타입은 Redis client를 모르고, 소비자 wrapper만 implementation policy와 Lettuce adapter를 소유한다.

## 의존 순서

```text
baseline/read-back
      ↓
consumer contract RED (shared API를 먼저 참조)
      ↓
shared generic implementation
      ↓
operations + commerce wrappers/dependency wiring
      ↓
targeted tests → compile → diff/static checks
      ↓
issue evidence + workflow DoD
```

## 단계별 작업

### 1. 기준선과 대상 고정

**대상:** workflow evidence, 현재 두 fixture와 Gradle dependency

- `develop`의 기준 SHA와 feature worktree가 동일한지 다시 확인한다.
- `shared` baseline `:shared:test`의 기존 `bluetape4k/mock-web-server:2.2.0` 초기화 실패를 별도 gap으로 보존한다.
- 다음 두 소비자의 ownership, deletion, non-convergence 테스트 이름과 실제 namespace vector를 보존한다.
  - `operations/job-console-core/src/test/kotlin/.../OwnedRedisNamespaceTest.kt`
  - `commerce/concert-ticket-flash-sale/src/test/kotlin/.../TicketOwnedRedisNamespaceTest.kt`

**증적:** `git status`, baseline command output, source path inventory, issue #1069 read-back.

### 2. RED — consumer tests를 공통 API 경계로 이동

**대상:** 두 consumer test와 local fixture의 공개 타입 이름

- 두 테스트가 공통 package의 `OwnedRedisScanPage`, `OwnedRedisKeyCommands`, `OwnedRedisWriterBarrier`, `OwnedRedisDeletionResult`를 참조하도록 준비한다.
- 기존 테스트의 시나리오, golden vector digest, 실제 Redis 검증은 바꾸지 않는다.
- 아직 shared fixture가 없으므로 `:operations:job-console-core:test`와 `:commerce:concert-ticket-flash-sale:test`의 compile이 실패하는 RED 증적을 남긴다.

**증적:** 실패한 compile/test output에 `unresolved reference` 또는 missing shared type이 포함되는지 확인한다.

### 3. GREEN — shared generic fixture 구현

**파일:** `shared/src/testFixtures/kotlin/io/bluetape4k/workshop/shared/redis/OwnedRedisNamespace.kt`

- 기존 두 구현의 알고리즘을 하나로 옮긴다.
- `OwnedRedisScanPage`, `OwnedRedisKeyCommands`, `OwnedRedisWriterBarrier`, `OwnedRedisDeletionResult`를 정의한다.
- `parse`에 `implementationPolicy: (String) -> Boolean`을 주입한다.
- namespace component count, terminal delimiter, owner regex, positive bounds, bounded cursor loop, foreign candidate guard, unlink count, post-delete convergence를 모두 유지한다.
- `io.lettuce.*`, Testcontainers, Spring, PostgreSQL import를 추가하지 않는다.
- `TreeSet`을 사용해 반환 key 순서를 기존 contract와 동일하게 유지한다.

**증적:** `:shared:testFixturesClasses` 또는 동등한 fixture compile이 성공하고 `rg 'io\.lettuce' shared/src/testFixtures/.../OwnedRedisNamespace.kt` 결과가 비어 있다.

### 4. Consumer wiring — 정책과 adapter만 local에 남김

**파일:**

- `operations/job-console-core/build.gradle.kts`
- `operations/job-console-core/src/testFixtures/kotlin/io/bluetape4k/workshop/operations/jobconsole/highcontention/OwnedRedisNamespace.kt`
- `commerce/concert-ticket-flash-sale/build.gradle.kts`
- `commerce/concert-ticket-flash-sale/src/test/kotlin/io/bluetape4k/workshop/commerce/ticket/highcontention/TicketOwnedRedisNamespace.kt`

- operations는 `testFixturesImplementation(testFixtures(project(":shared")))`를 추가하고 `job-core`, `job-spring`, `job-ktor` policy wrapper와 Lettuce adapter를 유지한다.
- commerce ticket은 `testImplementation(testFixtures(project(":shared")))`를 추가하고 `ticket-spring` policy wrapper와 Lettuce adapter를 유지한다.
- local 파일에서 공통 알고리즘/data class/interface 중복은 제거한다. wrapper의 `parse`는 shared `OwnedRedisNamespace`를 반환해 호출부가 기존 `deleteOwnedKeys`를 계속 사용하게 한다.
- `TicketHighContentionProfileTest`를 포함한 기존 호출부의 public test-facing 이름은 유지한다.

**증적:** 두 wrapper가 shared class를 호출하고 local algorithm body가 사라졌는지 source diff로 확인한다.

### 5. GREEN — 계약/통합 검증

**명령:**

```bash
./gradlew :shared:testFixturesClasses --no-daemon --console=plain
./gradlew :operations:job-console-core:test --tests '*OwnedRedisNamespaceTest' --no-daemon --console=plain
./gradlew :commerce:concert-ticket-flash-sale:test --tests '*TicketOwnedRedisNamespaceTest' --no-daemon --console=plain
```

- consumer tests의 vector, fail-closed, barrier ordering, bounded scan, real Redis 결과를 읽어 확인한다.
- Docker image가 없는 baseline 오류와 새 fixture의 compile/test 오류를 구분한다.
- 필요한 경우 `:operations:job-console-core:testFixturesClasses`를 별도로 실행해 fixture classpath를 확인한다.

**증적:** 각 명령의 exit status와 test count, 실패 시 정확한 원인/경로.

### 6. 정적 검증과 이슈 갱신

- `git diff --check`와 변경 파일 inventory를 실행한다.
- `OwnedRedisNamespace`가 `shared/src/main`으로 새지 않았는지, consumer가 `bluetape4k-dependencies` BOM 외 별도 버전을 추가하지 않았는지 확인한다.
- issue #1069에 Phase A의 완료/보류 항목, 테스트 결과, baseline Docker gap, 다음 `MutableClock`/`WorkerPid` 단계 링크를 한국어 comment로 기록한다.
- workflow component evidence에 spec, plan, implementation, verification 증적을 차례로 첨부한다.

**증적:** issue read-back, `git diff --check`, Gradle output, workflow receipt checksum.

## 실패 시 복구 지점

1. RED compile 실패가 shared API 문제인지 확인한 뒤, 테스트 변경만 되돌릴 수 있다.
2. shared compile 실패 시 consumer wrapper를 유지한 채 generic fixture를 수정한다.
3. dependency variant 오류가 나면 `testFixtures(project(":shared"))`와 기존 `project(":shared")`의 classpath를 비교하고, 불필요한 중복 선언은 남기지 않는다.
4. real Redis만 실패하면 Docker/Redis 원인을 별도로 진단하며 알고리즘 성공으로 간주하지 않는다.
5. 공통 추출이 계약을 보존하지 못하면 두 local fixture를 복구하고 이슈에 추출 보류 사유를 기록한다.

## 검토 게이트

- 설계 명세를 먼저 커밋한 후 이 계획을 커밋한다.
- 구현 전 RED output을 확보한다.
- 구현 후 두 consumer의 독립 테스트를 모두 실행한다.
- independent code review가 없는 single-developer lane에서는 이를 `N/A (single-developer lane)`으로 기록하되, exact diff, targeted tests, source inventory, workflow evidence는 생략하지 않는다.
- 이번 계획 완료는 Phase A의 검증 완료를 뜻하며, 전체 issue #1069의 `MutableClock`, `WorkerPid`, upstream publication 완료를 의미하지 않는다.

## Writer gate 기록

- **SPW-01**: 독자는 Workshop 유지보수자이며, 실행 질문은 `OwnedRedisNamespace`를 두 소비자에서 공통 test fixture로 안전하게 추출하는 방법이다. 근거 source, issue, 설계 문서와 baseline gap을 명시했다.
- **SPW-02**: 의존 순서, exact files, RED/GREEN 명령, expected evidence, rollback point, issue/workflow gate를 포함했다.
- **SPW-03**: 한국어 기술 문체를 사용하고 명령·경로·식별자·오류 토큰은 그대로 보존했다.
- **SPW-04**: 설계의 선택(`testFixtures`, adapter injection, policy wrapper)과 실제 Gradle/source 구조를 대조했다.
- **SPW-05**: 계획 본문을 다시 읽고 단계 순서와 대상 파일이 설계 명세·이슈와 일치하는지 확인한 뒤 workflow `plan` evidence에 기록한다.
