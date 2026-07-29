# #520 Job Operations Console 교훈

Date: 2026-07-21
Scope: `operations/job-console-*`

## 배경

이 작업은 같은 browser UI와 HTTP/SSE 계약을 Spring MVC와 Ktor에서 제공하면서, 장기 실행 job의 queue, lease, checkpoint, 취소, ETA, event publication을 PostgreSQL 권위로 설명하는 Java 25 예제를 만드는 일이었다.

## 핵심 결정

- 상태와 복구의 권위는 PostgreSQL 하나로 제한했다.
- Redis cancel signal과 SSE는 빠른 알림일 뿐 결과를 소유하지 않는다.
- Spring과 Ktor는 adapter lifecycle만 다르게 구현하고 core DTO, repository, worker, UI, live fixture를 공유한다.
- queue 목록은 bounded cursor page로, 현재 job position은 DB count로 계산한다.
- README에는 architecture, request sequence, 7-state state diagram을 함께 제공한다.

## 구현 중 확인한 교훈

### 1. live adapter test는 route 존재가 아니라 terminal 도달을 증명해야 한다

서버가 202를 반환하는 것만으로는 background worker가 adapter lifecycle에 연결됐다는 증거가 아니다. 같은 live fixture가 제출한 job이 실제로 `succeeded`에 도달하고 running cancellation이 `cancelled`로 수렴하는지 확인해야 한다.

### 2. lease expiry는 reclaimer가 오기 전부터 write fence여야 한다

만료된 worker가 새 worker claim 이전에 checkpoint를 쓰는 틈을 허용하면 lease 의미가 무너진다. current token, revision, `lease_expires_at > CURRENT_TIMESTAMP`를 모든 progress/terminal write 조건에 넣고 checkpoint마다 lease를 갱신해야 한다.

### 3. cancellation recovery는 job을 되살리면 안 된다

`cancel_requested` lease가 만료됐을 때 다시 `running`으로 reclaim하면 durable cancel이 무시된다. 만료 reclaim 경로는 그 row를 직접 `cancelled`로 수렴시켜야 한다.

### 4. cursor는 API model이 아니라 DB query까지 전달되어야 한다

응답에 `nextCursor`가 있어도 repository가 매번 첫 page를 조회하면 cursor는 장식일 뿐이다. cursor sequence를 SQL predicate로 전달하고, 공개 최대 100행을 유지하면서 다음 page 존재 여부만 한 행 lookahead로 확인해야 한다.

### 5. background loop 하나가 서로 다른 책임을 직렬화하면 안 된다

Ktor에서 worker 실행 뒤에 outbox poll을 붙이면 긴 job이 live notification을 굶긴다. worker와 outbox는 별도 application-owned coroutine이어야 하며, Spring에서도 scheduler 책임을 분리해야 한다.

### 6. SSE는 notification이고 UI는 매번 REST를 다시 읽어야 한다

event payload를 화면 상태로 사용하면 유실, 중복, reconnect에서 drift가 생긴다. browser UI는 event 종류와 무관하게 authoritative snapshot을 다시 읽고, stream failure에서도 REST refresh 후 재연결한다.

### 7. ETA schema만 구현해서는 ETA가 생기지 않는다

성공 duration을 실제 worker terminal 경로에서 기록하지 않으면 모든 live snapshot이 영구적으로 `insufficient_data`다. 표본 write와 bounded read를 end-to-end로 연결해야 한다.

### 8. 공통 contract는 framework parity뿐 아니라 race도 드러낸다

두 adapter에 같은 terminal-await fixture를 적용하면서 terminal 전이와 queue projection 사이의 TOCTOU가 드러났다. 상태가 active에서 terminal로 바뀌는 순간 projection이 사라지면 snapshot을 다시 읽어 terminal 결과로 수렴해야 한다.

## 결과

- 신규 세 모듈만 Java 25를 사용한다.
- Spring MVC와 Ktor가 동일한 UI 및 live contract를 통과한다.
- PostgreSQL 장애는 readiness를 닫고, Redis 장애는 `DEGRADED`로만 표시한다.
- 41개 test/integration test, 전역 detekt/build, operations smoke, stale-check, diagram QA가 통과했다.

## 다음 작업 지침

- 새 failure fixture를 추가할 때 core test만 추가하지 말고 공통 live contract로 승격할 수 있는지 먼저 판단한다.
- SSE event에 상태 필드를 늘리지 말고 REST snapshot version과 refresh hint만 유지한다.
- queue capacity나 exact position 비용을 바꾸려면 #522 load-profile 증거를 먼저 갱신한다.
- Java 25 설정을 root toolchain으로 올리지 말고 이 예제 모듈 경계를 유지한다.
