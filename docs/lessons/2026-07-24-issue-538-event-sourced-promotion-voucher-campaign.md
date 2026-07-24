# Issue #538 이벤트 소싱 프로모션 바우처 캠페인 예제 교훈

## Context

프로모션 캠페인과 바우처 claim을 event sourcing으로 재구성하려면 append-only event 저장만으로는
충분하지 않다. 같은 command의 동시 재시도, optimistic revision 충돌, projection 지연, worker
lease 탈취, poison event, rebuild generation 전환, 암호화 키 폐기가 겹쳐도 PostgreSQL event
stream이 유일한 권위를 유지해야 했다. 예제는 Java 25 virtual thread, Spring MVC,
Spring-managed HikariCP, Exposed JDBC, PostgreSQL을 사용한다.

## Decision or Finding

- `EventStoreRepository`는 generic CRUD를 노출하지 않는 semantic transaction boundary로 stream
  head lock, event append, idempotency descriptor, snapshot을 PostgreSQL transaction으로 묶었다.
  bluetape4k `ExposedJdbcRepository`는 append-only mutation을 차단한 `EventLogRepository`처럼
  generic 조회 계약이 안전한 보조 표면에만 제한했다.
- projection persistence는 generic CRUD repository를 노출하지 않았다. Lease/fencing, dedup,
  checkpoint, read model 갱신을 하나의 semantic transaction으로 유지하지 않으면 stale worker가
  fencing을 우회할 수 있기 때문이다.
- rebuild도 generation token과 state transition을 전용 persistence boundary에서 검증한다.
  `BUILDING -> VALIDATING -> ACTIVE -> RETIRED` 전환과 cancel/resume은 현재 generation을 확인한
  writer만 수행한다.
- Spring context 테스트는 Boot가 관리하는 Hikari `DataSource`와 bluetape4k Exposed Spring Boot
  integration을 사용한다. PostgreSQL 권한·query plan 같은 저수준 capability 테스트만 별도 Hikari
  pool을 Exposed `Database`에 등록하며, production semantic transaction 경계는 사용하지 않는다.
- 호출자 입력 검증은 bluetape4k `require*` helper를 사용하고, 정규화 또는 검증된 반환값을 이후
  persistence와 descriptor fingerprint에 전달한다.
- HTTP query는 `X-Stream-Position`, `X-Projection-Position`, `X-Projection-Lag`를 함께 반환한다.
  최소 position에 도달하지 못하면 무기한 대기하지 않고 `202 PROJECTION_PENDING`과 retry 정보를
  반환한다. SSE는 snapshot-first, `Last-Event-ID`, retention reset 계약을 갖는다.
- projection rebuild, poison retry, reconciliation은 operator API와 audit event를 공유하고,
  HMAC/encryption key 폐기는 replay가 필요한 retention과 충돌하면 `REPLAY_KEY_UNAVAILABLE`로
  명시적으로 실패한다.

## Adopted and Rejected

채택한 방식은 PostgreSQL stream head를 잠그고 global position을 연속 예약한 뒤 event,
idempotency 결과, snapshot 후보를 atomic하게 기록하는 것이다. projection은 at-least-once
delivery를 전제로 event dedup과 checkpoint를 같은 transaction에서 처리한다.

다음 대안은 제외했다.

- projection repository에 generic CRUD를 제공하는 방식: lease/fencing 검사를 우회할 표면이 생긴다.
- in-memory 또는 H2만으로 append race를 증명하는 방식: PostgreSQL row lock과 transaction
  rollback 계약을 검증하지 못한다.
- controller가 raw Kotlin `require`로 입력을 검사하는 방식: bluetape4k ecosystem의 일관된
  validation contract와 검증된 반환값 전달을 놓친다.
- rebuild 중 기존 read model을 즉시 삭제하는 방식: 검증 전 generation이 사용자 조회를 오염시킨다.
- projection 지연을 HTTP 200의 stale body만으로 숨기는 방식: caller가 consistency 상태를
  판별하거나 안전하게 retry할 수 없다.

## Outcome

같은 stream의 expected revision 충돌은 하나의 append만 성공하고, 동일 event/command 재전송은
저장된 결과로 수렴한다. 다른 stream은 global position fence 아래 연속적인 위치를 받는다.
Projection worker가 lease를 잃거나 재시작해도 stale fencing token은 read model과 checkpoint를
변경할 수 없다. Rebuild는 새 generation을 검증한 뒤에만 active pointer를 전환하므로 조회가
부분 재생 상태를 관찰하지 않는다. Rebuild 검증 중 active checkpoint가 target을 앞서가면 fenced
전이로 candidate target을 확장하고 `BUILDING`으로 돌아가므로 `VALIDATING`에 영구 정체되지 않는다.

README EN/KO는 architecture, command-to-projection sequence, rebuild state diagram을 같은
generator에서 만들고 실제 controller route/header/error constant를 validator로 대조한다.
Gradle 자동 등록은 119-project graph로 검증했으며 `settings.gradle.kts`에는 module별 include를
추가하지 않았다. Repository `AGENTS.md`도 module inventory를 소유하지 않으므로 변경 대상이
아니다.

## Verification

다음 명령을 재실행 가능한 검증 경계로 둔다.

```bash
node scripts/generate-event-sourced-voucher-diagrams.mjs
node scripts/validate-event-sourced-voucher-readme.mjs
./gradlew projects --console=plain
./gradlew :commerce-event-sourced-promotion-voucher-campaign:test --max-workers=1
./gradlew :commerce-event-sourced-promotion-voucher-campaign:integrationTest --max-workers=1
./gradlew :commerce-event-sourced-promotion-voucher-campaign:stressTest \
  -PeventSourcedStress=true --max-workers=1
./gradlew :commerce-event-sourced-promotion-voucher-campaign:build --max-workers=1
```

PostgreSQL integration test는 append conflict/rollback/race/duplicate/multi-stream, snapshot,
projection lease/fencing/dedup/checkpoint, rebuild generation, poison retry와 reconciliation을 실제
transaction으로 검증한다. 호출자·developer API·운영 관점 리뷰에서 드러난 generic CRUD,
raw validation, 직접 database connection, 모호한 lag/rebuild 계약은 구현과 문서 양쪽에서
제거했다.

Task 16의 fresh module evidence는 unit 63건, PostgreSQL integration 77건, explicit stress 9건과
`build`, `detekt`, `detektTest`, Kover gate 통과다. README validator는 locale 2개, diagram 3개,
route 8개, source file 6개, heading 11개를 검증했고 diagram QA, stale-reference/image 검사,
`actionlint`, `git diff --check`도 통과했다. `scripts/smoke-validate.sh commerce`에서는 이 module과
usage-metering regression이 통과했지만, 범위 밖 기존
`commerce-concert-ticket-flash-sale`의 payment reconciliation 테스트 3건이 실패했다. #538에서
해당 module을 수정하지 않고 repository-level known gap으로 남겼다.

Exact-head review에서 추가로 발견된 세 경계도 닫았다. Startup authority probe는 `READINESS`
permit을 Hikari connection보다 먼저 획득한다. Snapshot identity와 unique index는
tenant/type/id/version 전체를 사용한다. Runtime tick 실패는 projection health를 `DEGRADED`로
전환하고 retry 성공 시 복구하며, active lag/batch와 rebuild progress/ETA meter를 실제 worker
경로에서 갱신한다. Rebuild poison 결과도 health 복구 조건에 포함하고, poison meter는 `ACTIVE`와
현재 in-progress rebuild generation의 DB `FAILED` row를 집계하는 현재값 Gauge로 바꿔 동일
poison의 반복 poll이 값을 부풀리지 않도록 했다.

## Rollback Boundary

되돌릴 때는 module source만 남기고 등록 표면 일부를 제거하지 않는다. Root/commerce README,
smoke/full/nightly task, JUnit/Kover artifact 경로, validation matrix, source-backed README
validator, 세 diagram과 generator, 이 lesson을 하나의 registration set으로 함께 되돌린다.
Event schema나 persisted generation을 이미 운영 데이터에 쓴 뒤에는 코드만 되돌리지 말고
새 reader의 backward compatibility와 projection rebuild 가능성을 먼저 확인한다.

## Future Guidance

- Event sourcing 예제는 append 성공뿐 아니라 duplicate, conflict, rollback, multi-stream global
  ordering을 실제 PostgreSQL로 증명한다.
- Semantic repository가 fencing invariant를 소유하면 generic CRUD convenience를 추가하지 않는다.
- Virtual thread는 JDBC capacity가 아니다. Spring-managed HikariCP 상한과 transaction timeout을
  별도로 문서화하고 관찰한다.
- Projection-aware API는 stream position, projection position, lag, retry/reset semantics를
  함께 제공해야 caller가 eventual consistency를 명시적으로 다룰 수 있다.
- Rebuild는 새 generation의 전체 검증과 atomic activation 전까지 기존 active generation을
  유지한다.
