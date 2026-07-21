# Issue #548 Job Safety Lab 교훈

Date: 2026-07-22
Scope: `leader/job-safety-lab`

## 배경

leader election을 사용하면 동시에 한 worker만 실행된다고 생각하기 쉽다. 그러나 lease TTL이 끝난 worker가 실제로 멈췄다는 보장은 없다. GC pause, network partition, 긴 I/O 뒤에 과거 worker가 재개될 수 있고, 서로 다른 job 이름도 같은 business resource를 갱신할 수 있다. 이 예제는 많은 서비스 회사가 겪는 이 경계를 Java 25 Spring Boot, Redis, PostgreSQL, JetBrains Exposed로 재현한다.

## 핵심 결정

- `bluetape4k-leader-redis-lettuce`는 현재 active runner를 줄이는 leader election에 사용한다.
- leader backend의 owner token은 opaque다. 순서를 비교하거나 fencing token으로 바꾸지 않는다.
- resource 단위 Lua lease가 monotonic fencing token을 발급한다.
- PostgreSQL은 membership revision, home region/epoch, minimum writer version, namespace epoch, `incomingFence > lastAcceptedFence`를 한 transaction에서 다시 확인한다.
- checkpoint, execution, resource, outbox는 Exposed transaction 하나에 commit한다.
- DB가 fence할 수 없는 provider effect는 stable `OperationId`, transactional outbox, idempotency/lookup, durable receipt로 수렴한다.

## leader, lease, fence, DB를 하나의 보장으로 합치면 안 된다

leader election은 mutual exclusion과 failover에 유용하지만 stale writer rejection을 제공하지 않는다. resource lease도 TTL 이후 old worker가 살아나는 일을 막지 못한다. 순서 비교 가능한 fence를 durable authority가 write 조건으로 확인해야만 A41 pause 뒤 B42 commit, A41 resume 순서에서 과거 write를 거부할 수 있다.

반대로 fencing token은 외부 이메일, 결제, webhook을 취소하지 못한다. 그래서 durable completion은 DB commit과 별도 상태다. `COMMITTED` 뒤 provider 결과가 불명확하면 `RECONCILIATION_REQUIRED`로 남고, 원래 operation을 조회해 receipt가 기록된 뒤에만 `COMPLETED`가 된다.

## Exposed transaction 경계가 architecture를 설명한다

모든 concrete repository는 Bluetape의 `ExposedJdbcRepository`를 구현한다. repository와 test fixture 모두 Exposed DSL/DAO를 사용하며 raw SQL, `JdbcTemplate`, JDBC statement, `Transaction.exec`가 없다. authority load, conditional resource update, checkpoint/execution/outbox write가 같은 `JobSafetyJdbcExecutor.transaction`에 있으므로 stale fence에서 update count가 0이면 나머지 write도 남지 않는다.

Spring context restart test에서 Exposed의 `Database` 등록이 process-wide라는 점이 드러났다. `DataSource`만 닫으면 다음 context가 닫힌 registration을 재사용할 수 있어, executor가 close 시 `TransactionManager.closeAndUnregister`를 호출하도록 lifecycle 경계를 명시했다.

## outbox claim도 crash 경계를 가져야 한다

처음 구현은 provider call을 transaction 밖으로 옮겼지만, claim commit 직후 process가 죽는 경우를 놓쳤다. `CLAIMED` row가 영구 정체되는 문제를 리뷰에서 발견했다. claim timeout을 durable하게 기록하고, 만료된 claim은 execute lane이 아니라 query-only reconciliation lane으로 보내야 한다. provider가 이미 적용했을 가능성이 있기 때문이다.

이 규칙은 retry에서도 같은 `OperationId`를 유지해야 한다는 뜻이다. response loss마다 새 ID를 만들면 provider idempotency와 receipt uniqueness가 모두 무력화된다.

## cancellation은 일반 실패가 아니다

domain callback에서 발생한 일반 exception은 stable `FAILED` 결과로 바꿀 수 있지만 `InterruptedException`까지 흡수하면 virtual-thread cancellation이 사라진다. interrupt flag를 복구하고 lease cleanup을 실행한 뒤 interruption을 전파하도록 고쳤다. cleanup failure는 이미 durable한 commit 결과를 뒤집지 않되 structured warning과 interrupt flag를 남긴다.

## Redis counter history와 namespace epoch

lease key는 삭제할 수 있지만 counter key는 과거 DB fence보다 작아지면 안 된다. backup/restore에서 counter history를 잃었다면 1부터 재시작하지 않는다. Redis namespace epoch와 PostgreSQL rollout marker를 함께 올리고 old namespace를 fail closed해야 한다. Lua script는 counter missing with existing lease, overflow, epoch mismatch, malformed lease를 모두 backend failure로 돌린다.

## microservice로 분리할 때 유지할 ownership

- Scheduler service: trigger, membership snapshot, leader election
- Execution service: resource fence와 PostgreSQL conditional commit
- Effect worker: outbox claim, provider lookup, receipt
- Operator control: bounded reconciliation/reset와 audit

서비스를 나눠도 `OperationId`, `ConflictKey`, membership revision, region epoch, namespace epoch, contract version, fencing token을 end-to-end로 전달한다. broker offset이나 message order를 fencing token으로 대체하지 않으며, execution service가 checkpoint/execution/outbox atomic commit을 소유한다.

## 검증에서 확인한 사실

- 58개 default test와 실제 PostgreSQL/Redis 12개 integration test가 통과했다.
- Redis takeover는 41 다음 42를 발급하고 stale owner renew/release를 거부했다.
- PostgreSQL은 fence 42 commit 뒤 fence 41, stale membership, wrong region, old writer, stale namespace를 각각 안정적인 reason으로 거부했다.
- context restart와 duplicate delivery 뒤 provider application과 receipt는 각각 1개였다.
- README locale pair, 네 SVG/PNG diagram, stale-check, actionlint, source guard가 통과했다.

## 앞으로 지켜야 할 guard

- opaque leader token을 `FencingToken`으로 변환하는 API를 추가하지 않는다.
- Redis/provider 호출을 Exposed transaction 안으로 옮기지 않는다.
- expired outbox claim을 무조건 execute하지 않고 original operation query로 보낸다.
- counter history를 삭제하거나 namespace epoch를 DB marker와 독립적으로 바꾸지 않는다.
- production에서는 `SchemaUtils` 대신 versioned migration을 사용한다.
- 범용 fencing lease는 workshop에서 바로 public API로 승격하지 않고 `bluetape4k-projects` issue #1068의 `bluetape4k-lettuce` 후보 설계를 따른다.
