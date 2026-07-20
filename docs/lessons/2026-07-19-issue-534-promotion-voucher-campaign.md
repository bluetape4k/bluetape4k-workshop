# Issue #534 프로모션 바우처 캠페인 예제 교훈

## Context

고경합 바우처 캠페인은 많은 HTTP 요청을 수용하는 것과 제한된 캠페인 수량을 정확히 소비하는
것을 분리해야 한다. 응답 유실, 같은 멱등 키의 동시 요청, Redis 장애, 운영 review 지연,
reconciliation worker 재시작이 겹쳐도 oversell, 중복 사용, code 재노출이 없어야 했다. 예제는
Java 25 virtual thread, Spring MVC, Exposed JDBC, PostgreSQL을 기본으로 사용하고 Redis는
Lettuce 기반 advisory 경계로만 사용한다.

## Decision

- PostgreSQL row lock, revision, campaign capacity, claim 상태, idempotency descriptor를 correctness의
  유일한 권위로 둔다.
- Exposed 접근은 `bluetape4k-exposed-jdbc` repository 패턴으로 구성하고,
  `bluetape4k-exposed-jdbc-tests`와 `PostgreSQLServer`로 실제 PostgreSQL 계약을 검증한다.
- Tomcat은 Java 25 virtual thread로 요청을 수용하지만 HikariCP는 최대 16 connection으로 제한한다.
  JDBC connection을 얻기 전에 foreground 12, worker 1, SSE maintenance 3 permit을 확보해 lane별
  starvation을 막는다.
- Redis/Lettuce rate admission과 Bloom signal은 부하와 risk hint만 제공한다. Redis가 없거나
  timeout이어도 최종 mutation은 같은 PostgreSQL transaction에서 다시 검증한다.
- allocation 응답에서 한 번 제공한 code는 safe `GET`으로 복원하지 않는다. 별도의 멱등
  acknowledgement command가 전달 확인을 기록하고 review 승인 경계와 조회 경계를 분리한다.
- reconciliation은 Spring Modulith publication/inbox evidence를 유지하고 leader가 한 번에 최대
  50개를 처리한다. 자동 경로가 멈추면 같은 bounded path를 operator가 수동 실행할 수 있다.
- operational code는 `bluetape4k-logging`을 사용하며 tenant, principal, voucher code,
  idempotency key, operator secret, request body 원문을 로그에 남기지 않는다.

## Outcome

같은 key와 같은 fingerprint는 성공과 terminal failure를 저장된 response descriptor로 replay하고,
다른 fingerprint는 deterministic conflict로 거절한다. Redis 장애는 중복 작업량이나 review 비율을
바꿀 수 있지만 PostgreSQL capacity를 우회하지 않는다. Review, code acknowledgement, redemption,
revoke race와 event 재처리는 revision 및 inbox uniqueness로 하나의 terminal 결과에 수렴한다.

Virtual thread를 사용해도 DB capacity는 늘지 않는다. Tomcat의 큰 admission 범위와 Hikari 16,
60초 connection/transaction timeout, 12/1/3 permit lane을 별도 경계로 유지해야 많은 대기 요청이
worker나 SSE cleanup capacity를 잠식하지 않는다.

## Verification

- live `WebTestClient`와 실제 Tomcat으로 allocation, acknowledgement, redemption, operator review,
  SSE reconnect/polling fallback을 검증했다.
- `PostgreSQLServer` 기반 repository/integration test로 capacity race, row lock timeout, idempotency,
  migration, backup/restore, restart를 검증했다.
- Redis healthy/timeout과 concurrency 64/128 조합을 두 번 실행해 각 profile의 JSON/JFR evidence와
  correctness invariant를 남겼다.
- logging redaction, Hikari/permit 상한, resource leak 0, stable 409/429/503 응답을 자동 검증했다.

## Future Guidance

- Virtual thread 예제는 Tomcat thread 수만 크게 보이지 말고 DB pool, connection/transaction timeout,
  connection 이전 permit, worker/SSE reserved capacity를 함께 문서화하고 테스트한다.
- Redis나 Bloom filter를 추가할 때는 장애가 correctness를 약화하지 않는지, PostgreSQL에서 같은
  invariant를 다시 확인하는지부터 검증한다.
- 일회성 secret/code는 조회 DTO에서 분리하고 저장된 idempotency descriptor가 복원에 필요한
  key version만 참조하도록 한다. 이전 key version은 claim/replay/audit retention이 끝나기 전에
  삭제하지 않는다.
- `WebTestClient`는 mock server가 아니라 live server에 연결해 servlet, virtual thread,
  serialization, header, streaming resource lifecycle을 함께 검증한다.
- `withTables(TestDB.POSTGRESQL, ...)` fixture와 application migration test가 같은 container를
  공유하면 동일한 `public` table 이름을 정리할 수 있다. Application-context test는 Base58 이름의
  전용 schema에서 migration을 실행해 fixture cleanup과 schema history를 격리한다.
- one-shot failure fixture는 allocation/redemption 같은 operation을 명시적으로 구분하고, 멱등 replay는
  저장된 response만 반환해야 한다. Replay 과정에서 fixture signal을 다시 arm하면 같은 키가 새로운
  side effect를 만들게 된다.
- tenant authority를 reset하는 browser scenario는 기존 `EventSource`를 먼저 닫고 cursor와 reconnect
  timer를 비운 뒤 snapshot-first stream을 다시 연결해야 한다. 삭제된 audit cursor를 재사용하면
  정상 reset이 무한 400 reconnect loop로 보인다.
- database transaction 안에서 process-local fixture signal을 바꿀 때는 mutation을 `afterCommit`으로
  지연한다. 그렇지 않으면 idempotency finalize나 reset transaction rollback 뒤에도 JVM memory만
  변경되어 다음 요청이 존재하지 않는 signal을 소비하거나 필요한 signal을 잃는다.
- 실패 시나리오 cookbook은 label 목록만 제공하지 말고 실행, authoritative outcome 검증, snapshot
  reload, audit/SSE evidence까지 하나의 반복 가능한 choreography로 묶는다.
- `Promise.allSettled`의 rejected 개수는 correctness evidence가 아니다. 각 loser의 HTTP status와
  stable error code를 검증하고, 마지막 PostgreSQL snapshot의 state/revision/capacity와 함께 판정한다.
- Browser의 same-origin safe `GET`은 `Origin` header를 생략할 수 있다. Operator secret을 cookie에
  두지 않는 전제에서도 explicit same-origin header를 보내고 server host/port와 대조하며,
  cross-origin preflight는 계속 차단해야 한다.
- voucher pool 사전 생성은 #537, event-sourced reconstruction은 #538의 별도 학습 경계로 유지한다.
