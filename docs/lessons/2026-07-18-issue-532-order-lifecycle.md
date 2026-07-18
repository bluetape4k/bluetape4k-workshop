# Issue #532 주문 생명주기 예제 교훈

## 결정

- 주문, 결제, 재고 예약, 배송, 취소, 환불을 하나의 상태 머신으로 합치지 않고 각 aggregate의 status와 revision을 독립적으로 유지한다.
- PostgreSQL을 authoritative store로 사용하며, HTTP idempotency와 provider event inbox도 애플리케이션 소유 테이블로 둔다.
- Exposed 접근은 `bluetape4k-exposed-jdbc`의 repository 기반으로 구성하고, 실제 DB 테스트는 `bluetape4k-exposed-jdbc-tests`의 `PostgreSQLServer`로 검증한다.
- Spring Modulith publication repository도 Exposed 구현을 사용해 실패한 listener 호출을 durable하게 남긴다.
- Java 25 virtual thread는 요청 동시성을 늘리는 도구로만 사용하고, HikariCP pool은 8개로 제한한다.
- 운영 경계는 `bluetape4k-logging`으로 기록하되 raw idempotency key, canonical payload,
  response body, 고객 입력은 로그에 남기지 않는다.

## 동시성 설정에서 배운 점

Virtual thread를 켠 상태에서는 Tomcat `threads.max`가 실질적인 처리량 제한이
아니다. `max-connections`와 `accept-count`가 HTTP admission 경계를 만들고,
DB 구간은 HikariCP가 별도로 제한한다. 따라서 Tomcat thread pool과 DB pool을
같이 8,000으로 늘리는 방식은 PostgreSQL에 과도한 동시성을 전달한다.

이 예제는 Hikari `maximum-pool-size=8`, `connection-timeout=60s`, Spring
transaction timeout `60s`를 사용한다. Timeout 증가는 짧은 부하 급증을
흡수하기 위한 제한된 대기일 뿐이다. 지속적인 포화 상황에서는 timeout을 더
늘리지 말고 쿼리 지연, pool wait, PostgreSQL active connection, publication
backlog를 함께 관찰해야 한다.

## Spring Boot와 Exposed 자동 설정

JetBrains Exposed starter와 bluetape4k Exposed Spring Boot 구성을 동시에 직접
가져오면 `springTransactionManager`가 중복될 수 있다. 이 예제는 JetBrains
core/JDBC artifact와 bluetape4k Spring Boot JDBC 구성을 사용하고, 하나의
transaction manager만 명시한다.

Spring Modulith의 publication auto-configuration은 repository bean을 조건 평가
시점에 확인한다. 애플리케이션 구성에서 Exposed publication repository를 먼저
등록해 durable event publication과 restart replay가 동일한 PostgreSQL 경계를
사용하도록 만들었다.

## 검증 계약

- MVC controller만 검증하는 `MockMvc` 대신 `RANDOM_PORT + WebTestClient.bindToServer()`를 사용한다.
  그래야 실제 Tomcat, Virtual Threads, 정적 리소스, SSE framing과 연결 해제를 함께 검증할 수 있다.
- `MockMvcWebTestClient`는 fluent API만 `WebTestClient`로 바뀌고 transport는 여전히 mock이므로 이 예제의 경계 검증에는 사용하지 않는다.
- 같은 idempotency key와 같은 payload는 최초 응답을 정확히 replay한다.
- 같은 key와 다른 payload는 deterministic conflict를 반환한다.
- 중복 및 순서 역전 provider event는 terminal payment를 다시 적용하지 않는다.
- 실패한 publication은 backlog에 남고, 제한된 replay 뒤 정확히 한 번 적용된다.
- 이미 배송된 line은 취소하지 못하고, 배송 전 line 취소는 별도 refund case를 만든다.
- Split fulfillment는 line별 group 하나를 만드는 것이 아니다. 같은 line의 수량을 둘 이상의
  group link로 실제 배분해야 하고, 취소는 shipped link를 보존한 채 cancellable group의
  수량에만 결정적으로 적용해야 한다.
- `DELIVERED`와 `CANCELLED` group만 남으면 주문을 terminal로 전환한다. 일부 배송 완료가
  있으면 `COMPLETED`, 전부 취소됐으면 `CANCELLED`가 된다.
- 같은 provider event ID의 다른 payload는 즉시 응답만 `CONFLICT`로 끝내지 않고 PostgreSQL
  inbox disposition에도 남겨 UI의 unresolved backlog에 계속 보여야 한다.
- SSE slot은 emitter를 만든 뒤 worker가 시작되기 전 snapshot 조회나 send가 실패해도 반드시
  반환해야 한다.
- 부분 취소는 `CancellationCase`를 먼저 승인하고 fulfillment 잔여 수량을 줄인 뒤 별도 `RefundCase`를 진행한다.
- delayed payment success는 deterministic operator endpoint로 전달하며 provider inbox idempotency를 그대로 통과한다.
- SSE는 snapshot을 먼저 보내고 audit cursor 이후 변경분만 이어서 보낸다.
- 같은 주문의 SSE connection은 poller 하나를 공유하고, DB poll permit을 Hikari pool보다 작게
  유지해야 virtual thread 수가 PostgreSQL 동시성으로 그대로 번지지 않는다.
- Terminal idempotency row는 bounded batch로 정리하되 `IN_PROGRESS` lease는 복구를 위해 보존한다.

## 운영 Logging

`bluetape4k-logging`의 lazy message는 disabled log level에서 문자열 조립 비용을 피하면서
실전형 `key=value` evidence를 남기기에 적합하다. 이 예제는 다음 경계를 기록한다.

- HTTP submit status와 replay 여부
- idempotency acquire/replay/conflict/lease reclaim/finalize
- provider event applied/duplicate/out-of-order/conflict/unresolved
- aggregate transition, revision, cancellation, refund, order terminal 결과
- failed publication replay 요청
- SSE open, release, capacity 초과, 초기화 실패, shutdown

식별에는 UUID와 key hash prefix만 사용하며 raw key와 request payload는 기록하지 않는다.
