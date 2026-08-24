# Issue #527 Last-Mile Routing 구현 lesson

## 결정

이번 모듈은 workshop의 실행 가능한 참고 구현이므로 외부 routing engine을
가져오는 대신 고정 matrix와 deterministic provider를 사용했다. 이렇게 하면
planner 제약, PostgreSQL 권위 상태, callback 순서, approval CAS, browser redaction을
네트워크·credential 없이 재현할 수 있다.

## Bluetape4k ecosystem 적용

- 저장소는 `bluetape4k-exposed`의 `AuditableLongIdTable`, `LongJdbcRepository`,
  `LongAuditableJdbcRepository`를 aggregate/child table 성격에 맞게 사용했다. 앱은
  `LastMileRepository` facade만 호출하고 Exposed DSL은 adapter 파일에 격리했다.
- 식별자·version·event digest는 작은 Kotlin value/domain type으로 제한했고,
  `Uuid.V7`로 event id를 생성했다.
- virtual-thread executor와 JDK 25 runtime을 `LastMileLifecycle` admission fence와
  함께 닫아 shutdown 중 신규 작업 유입을 막았다.
- 모든 새 테스트의 값·타입·null·예외 검증은 `io.bluetape4k.assertions`로 통일했다.
  특히 assignability stream 검사를 `shouldBeTrue`로 우회하지 않고
  `shouldBeInstanceOf<JdbcRepository<*, *>>()`로 바꿔 오류 시 실제 타입을 남긴다.

## 재사용할 패턴

1. 외부 provider DTO를 domain/persistence에 흘리지 말고 `RoutingRequest`,
   `RoutingSubmission`, `RoutingResult`, `RoutingCallback`으로 정규화한다.
2. callback은 inbox unique key → constant-time canonical digest → provider revision
   CAS 순서로 처리한다. 중복은 no-op, digest conflict와 stale revision은 서로 다른
   결과로 남긴다.
3. proposal history와 committed stop을 분리한다. approval은 plan state, matrix
   revision, carrier version, started pin, pickup/delivery order를 다시 확인한 뒤
   committed stop/outbox/audit를 한 transaction에 기록한다.
4. browser에는 주소·고객·raw provider text를 보내지 않고 coordinate id에서 만든
   synthetic point와 제한된 ETA/window/skill/score만 투영한다. 정적 자산은 CSP와
   `textContent`/DOM API를 사용한다.
5. 새 workshop module은 코드만 추가하지 않는다. root README 두 locale, Examples
   workflow, optimization smoke group, stale required-module 검사까지 같은 변경에
   등록한다.

## 이번 작업에서 확인한 함정

- pickup과 delivery는 같은 `jobId`를 공유하므로 committed stop의 `jobId` 단독
  unique index는 잘못이다. `(planId, planRevision, vehicleId, sequence)` 같은
  route-position key를 사용해야 두 stop을 보존한다.
- nullable provider revision은 `eq(0)`으로 absent를 표현할 수 없다. `IS NULL` CAS와
  값이 있는 revision CAS를 분리해야 local proposal과 callback/poll 승격이 모두
  안전하다.
- callback envelope의 provider/request/revision과 nested result가 어긋나면 digest와
  audit가 서로 다른 의미를 갖는다. domain 생성 시 세 값을 일치시키고, HTTP
  mapping에서도 envelope revision을 authoritative source로 사용한다.
- `contentHashCode()`는 ETag에 충분한 계약이 아니다. canonical JSON bytes의
  SHA-256을 사용해 충돌 위험과 캐시 의미 혼동을 줄였다.

## 후속 작업 경계

실제 provider wire contract, durable submission/input rehydration을 포함한 process
restart replay, production auth/CSRF/tenant, schema migration, full browser journey와
대규모 benchmark는 이 lesson의 다음 issue로 분리한다. 현재 synthetic 구현의
deterministic·bounded·redacted 계약을 깨면서 범위를 확장하지 않는다.
