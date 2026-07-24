# Workshop 재사용 Inventory 및 Kotlin Pattern Remediation 설계

Date: 2026-07-24
Repository: `bluetape4k-workshop`
Branch: `feature/issue-563-workshop-reuse-kotlin-patterns`
Issues: #563, #564

## 문제

Workshop은 bluetape4k 생태계를 사용하는 소비자 예제 저장소다. 따라서 이미
릴리스된 라이브러리 기능을 예제 전용 helper로 다시 구현해서는 안 되며, 반대로
하나의 예제에만 의미가 있는 코드를 `shared`로 일반화해서도 안 된다.

정적 조사에서 다음과 같은 구체적 후보가 확인됐다.

- Basic/advanced observability 예제는 동일한 coroutine Observation lifecycle
  helper를 유지한다. 캐시된 릴리스 `bluetape4k-micrometer:1.11.0`은
  `withObservationContextSuspending` 및 `withObservationSuspending` 공개 API를
  제공한다.
- `shared` HTTP extensions는 14개 이상의 독립 top-level module group에서
  사용되고, voucher black-box contract는 두 개의 독립 구현을 검증한다.
- `OrderEventStream`은 virtual-thread executor 위에서 monitor와 production
  `!!`를 사용한다.
- Job Operations Console에는 `UUID.randomUUID()`가 identity, correlation,
  subscription 생성에 남아 있다.
- 테스트에는 bluetape4k assertion/polling idiom 대신 표준 JUnit/Kotlin assertion을
  사용하는 후보가 있다.

## 목표

1. #563에서 후보를 release reuse, provider gap, Workshop shared, example-specific으로
   증거 기반 분류한다.
2. #564에서 실제 Kotlin pattern 위반만 수정하고, 기존 예제의 교육 목적과 예외
   계약을 보존한다.
3. 재사용 경계와 remediation 결과를 이슈, 테스트, KDoc/README에 일관되게 남긴다.

## 비목표

- Kotlin/JDK API의 기계적 전면 치환.
- 한 개 예제에만 의미가 있는 helper의 `shared` 승격.
- `bluetape4k-projects`의 개발 브랜치만 근거로 한 consumer 변경.
- 새 third-party dependency 추가.
- 현재 task와 무관한 event-sourced voucher worktree 변경.

## 접근 방식

### Option A: 모든 후보를 `shared`로 이동

한 위치에 코드를 모으지만, HTTP adapter, voucher contract, Observation lifecycle,
graph helper의 의존성과 교육 목적이 서로 다르다. provider 기능을 중복하고
`shared`의 API 표면만 넓힌다.

Rejected.

### Option B: 파일 단위의 일괄 Kotlin 정리

검색 결과만으로 raw `require`, assertion, UUID, `runCatching`을 모두 바꾸면
caller validation과 internal invariant, test-only teaching behavior를 혼동한다.
동시성/취소 계약도 충분히 검증하지 못한다.

Rejected.

### Option C: release-first inventory와 위험도 순 remediation

먼저 릴리스 artifact, caller, test, documentation 근거로 reuse 경계를 확정한다.
그 뒤 P1인 virtual-thread SSE lifecycle을 독립적으로 수정하고, P2는 Job Console
UUID와 테스트 assertion을 module 단위로 다룬다. provider gap은 Workshop wrapper가
아닌 provider issue로 분리한다.

Selected.

## 설계

### #563 재사용 경계

| 후보 | 결정 | 근거 및 후속 |
|---|---|---|
| Coroutine Observation `observed` | released-bluetape4k | #561에서 BOM-resolved release와 success/error/cancellation/dispatcher propagation parity를 증명한 뒤 local helper를 제거한다. `shared`에는 추가하지 않는다. |
| Shared HTTP extensions | Workshop shared 유지 | 다수 독립 module group 소비와 기존 contract test가 있다. Spring 4 API와 released ecosystem overlap만 검증한다. |
| Voucher black-box contract | Workshop shared 유지 | 두 independent voucher implementation의 HTTP compatibility contract다. 세 번째 독립 구현이 생기기 전에는 provider 승격하지 않는다. |
| Graph `requireEndpoint` 반복 | provider-gap 후보 | graph type을 숨기는 Workshop wrapper를 만들지 않는다. released graph API를 확인한 뒤 실제 공백이면 provider issue를 연다. |
| Exposed DTO mapper, MongoDB test bases | example-specific | table/DTO/fixture lifecycle이 module별로 다르므로 이동하지 않는다. |

`shared` 승격은 최소 두 independent example의 동일한 stable contract, 직접적인
shared test ownership, 예제별 domain semantics 비의존성을 모두 만족해야 한다.

### #564 remediation 순서

1. `OrderEventStream`의 monitor를 명시적 concurrency primitive로 교체한다.
   lifecycle critical section, open/shutdown race, feed sharing, connection capacity를
   동일하게 유지하고 internal `!!`는 `checkNotNull` 또는 nullable flow로 제거한다.
2. 동일 module의 caller input validation은 matching bluetape `require*` helper가
   released API에 있을 때만 전환하고 `IllegalArgumentException` 계약을 유지한다.
3. Job Console의 identity/correlation/subscription 값은 `Uuid.V7`로 전환한다.
   UUID text 형식, uniqueness, stored identity, error correlation의 observable
   contract를 테스트한다.
4. 테스트 assertion/polling은 touched module부터 `bluetape4k-assertions`,
   `assertFailsWith`, Awaitility 또는 맞는 coroutine tester로 전환한다. Testcontainers
   검증은 다른 Gradle process와 병렬 실행하지 않는다.

## 실패 모드와 대응

| 실패 모드 | 신호 | 대응 |
|---|---|---|
| 릴리스 API가 local helper와 parity가 없음 | stop/error/cancellation 또는 parent propagation test 실패 | #561을 provider-gap으로 전환하고 local workaround를 유지한다. |
| SSE lock 교체가 close/open race 또는 capacity accounting을 깨뜨림 | race test timeout, negative active count, leaked poller | 변경을 되돌리고 명시적 lock boundary와 cleanup ownership을 다시 설계한다. |
| UUID 전환이 database/schema 또는 public correlation format을 깨뜨림 | persistence/HTTP contract test 실패 | `Uuid.V7` 반환 type/serialization을 확인하고 one field family씩 전환한다. |
| assertion migration이 false-positive 또는 timing flake를 만든다 | 기존 behavior를 증명하지 못하는 assertion, retry-only pass | intent-specific matcher와 `untilAsserted`/suspend-aware tester로 강화한다. |
| `shared` 추출이 example semantics를 숨긴다 | caller가 두 independent stable contract 조건을 충족하지 못함 | example-specific으로 유지하고 #563 inventory에 N/A 근거를 기록한다. |

## 호환성 및 문서

- Caller validation은 기존 `IllegalArgumentException`과 message-redaction 경계를
  유지한다.
- Public KDoc은 English, user-facing spec/plan/review/lesson은 Korean으로 작성한다.
- 실제 public example usage가 바뀌는 경우에만 해당 `README.md`와 `README.ko.md`를
  함께 갱신한다.
- BOM만 version authority이며 개별 bluetape4k BOM 또는 explicit module version을
  추가하지 않는다.

## 완료 기준

- #563의 모든 후보가 source/caller/test/release evidence와 함께 네 가지
  disposition 중 하나로 분류된다.
- #564의 P0/P1은 0이며 P2는 수정되거나 concrete follow-up issue로 분리된다.
- 변경된 Kotlin test는 bluetape4k assertion/coroutine testing rule을 따른다.
- 영향 module의 targeted test, module test, detekt/static check, `git diff --check`가
  fresh evidence로 통과한다.
- 관련 이슈의 acceptance checklist와 결정 근거가 현재 branch 결과와 일치한다.
