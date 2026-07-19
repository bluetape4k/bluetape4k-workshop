# Issue #533 예약 제어 평면 예제 교훈

## 결정

- PostgreSQL row lock과 revision 검증을 예약 상태의 최종 권위로 유지한다.
- Redis/Lettuce는 중복 요청 억제와 admission hint에만 사용하며, 장애 시 node-local bulkhead와 PostgreSQL 검증으로 수렴한다.
- Exposed 접근은 `bluetape4k-exposed-jdbc` repository 패턴으로 구성하고 실제 DB 테스트는
  `bluetape4k-exposed-jdbc-tests`와 `PostgreSQLServer`로 검증한다.
- Java 25 virtual thread는 HTTP 동시성을 담당하고, HikariCP는 작은 pool과 제한된 대기 시간으로 PostgreSQL에 전달되는 동시성을 제한한다.
- hold, waitlist, offer, notification outbox는 같은 resource transaction 경계에서 상태를 전이하며,
  외부 notification 전송은 commit 이후 재시도 가능한 outbox worker가 수행한다.

## 동시성에서 배운 점

Virtual thread와 큰 Tomcat admission queue는 DB capacity를 늘리지 않는다. 예약 correctness는
resource row lock, revision CAS, idempotency record가 보장하고 HikariCP pool은 DB에 도달하는
동시 요청 수를 제한한다. 따라서 Tomcat `threads.max=8000`은 요청 수용 범위로만 해석하며,
Hikari `maximum-pool-size=8`, connection/transaction timeout `60s`를 별도 경계로 유지한다.

만료 sweeper는 repository별 후보를 따로 제한하면 안 된다. hold와 offer를 각각 32개씩 읽어
순서대로 처리하면, 더 오래된 offer가 hold batch 뒤로 밀려 resource capacity 반환이 지연될 수
있다. 두 후보를 `expiresAt`, `resourceId`로 전역 정렬한 뒤 resource별 중복을 제거하고 하나의
limit을 적용해야 mixed-expiry starvation을 피할 수 있다.

## Redis fallback에서 배운 점

Redis는 correctness authority가 아니므로 application context 시작을 막아서는 안 된다. Lettuce
연결 실패는 `InFlightCommandSuppressor`와 admission gate에서 fail-open 또는 node-local 제한으로
전환하고, 최종 상태 전이는 항상 PostgreSQL transaction에서 다시 검증한다. 이 경계 덕분에 Redis
장애 중에도 중복 작업량은 늘 수 있지만 oversell이나 stale offer accept는 허용되지 않는다.

## 멱등성·credential·logging 계약

- 같은 idempotency key와 같은 payload는 저장된 응답을 replay한다.
- 같은 key와 다른 payload는 deterministic conflict를 반환한다.
- hold와 offer credential은 digest만 저장하고 raw credential은 HTTP 응답 이후 로그나 DB에 남기지 않는다.
- `bluetape4k-logging`은 request disposition, revision, resource, fallback mode, outbox attempt를
  lazy `key=value` event로 기록하며 raw key, payload, credential은 기록하지 않는다.
- operator endpoint는 local/test principal의 role과 key를 모두 검증하고 destructive action 전에
  영향 snapshot을 반환한다.

## 검증 계약

- `RANDOM_PORT + WebTestClient.bindToServer()`로 실제 Tomcat, virtual thread, serialization,
  security header, operator authorization 경계를 검증한다.
- `PostgreSQLServer`로 hold/confirm/cancel/extend, waitlist FIFO, offer TTL, expiry handoff,
  idempotency lease, outbox retry를 검증한다.
- Redis 연결 가능/불가능 두 bootstrap 경로와 Lettuce coordination을 각각 검증한다.
- architecture diagram은 PostgreSQL authority와 Redis/leader advisory 경계를 구분하고,
  sequence diagram은 retry, expiry, promotion, stale credential, notification retry를 포함한다.

## 검토에서 놓쳤던 점과 향후 guard

초기 검토는 hold와 offer repository의 개별 정렬만 확인해 전체 후보 집합의 starvation 가능성을
놓쳤다. 이후 32개의 늦게 만료된 hold와 하나의 더 오래된 expired offer를 함께 구성하는 회귀
테스트로 이 결함을 재현하고 전역 정렬로 수정했다. 앞으로 여러 repository의 후보를 하나의
worker limit으로 처리할 때는 개별 query ordering이 아니라 병합 이후의 global ordering과
fairness를 반드시 테스트한다.
