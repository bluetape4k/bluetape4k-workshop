# Issue #552 사용량 계측과 청구 원장 예제의 구현 교훈

## 문제

SaaS 청구는 단순한 `quantity × price` 계산보다 중복과 시간 경계, 재시작이 어렵다. 같은 usage와 HTTP command가 다시 들어오고, 가격은 시간에 따라 바뀌며, close worker는 어느 batch에서든 죽을 수 있다. 이미 발행한 invoice를 다시 쓰지 않으면서 cutoff 뒤 늦게 도착한 usage도 회계 이력에 남겨야 한다.

## 선택한 architecture

Baseline은 Spring Boot modular monolith와 PostgreSQL 하나로 구성했다. Ingestion, pricing, period/close, invoice, adjustment, reconciliation을 application boundary로 나누되 period, close checkpoint, ledger, invoice는 같은 transaction authority에 남겼다. Redis, leader election, broker를 correctness 전제에 넣지 않았다.

Mutable workflow는 command receipt, billing period, close run에 저장한다. 금전 결과는 append-only ledger, invoice, line provenance에 저장한다. 이 구분이 `FAILED_VALIDATION` 복구와 발행 결과 불변성을 동시에 가능하게 했다.

## Exposed-only persistence

모든 concrete repository는 `MeteringExposedJdbcRepository` 또는 append-only 파생형을 통해 Bluetape `ExposedJdbcRepository`를 구현한다. Production과 test fixture 모두 Exposed DAO/DSL만 사용하며 raw SQL, `JdbcTemplate`, `java.sql.*`, migration SQL을 만들지 않았다. Append-only repository는 inherited `save`, `saveAll`, `delete*`를 모두 거부한다.

Test fixture의 `SchemaUtils`는 ephemeral PostgreSQL schema만 준비한다. 실제 배포는 Exposed table contract와 일치하는 schema를 조직의 migration pipeline으로 제공해야 한다.

## Idempotency transaction 분리

Command receipt acquire/takeover는 짧은 `REQUIRES_NEW` transaction이다. Domain command가 commit된 뒤 owner token을 포함한 terminal CAS로 status/body를 저장한다. 동일 key와 동일 fingerprint는 결과를 replay하고, 다른 fingerprint는 conflict다. Lease가 끝나면 새 owner가 takeover하며 이전 owner의 terminal update는 실패한다.

Producer duplicate는 별도 `(tenantId, sourceSystem, sourceEventId)` unique constraint로 막았다. HTTP retry와 producer retry를 하나의 key로 합치지 않은 것이 중요하다.

## 재시작 가능한 close와 가격 공백 복구

Close 시작 시 `cutoffReceivedAt`을 한 번 고정한다. Batch는 `(occurredAt, usageEventId)` keyset 뒤를 읽고 charge append와 checkpoint를 한 transaction에 commit한다. Crash 전 commit이면 둘 다 rollback되고, commit 뒤 재실행이면 ledger unique key가 중복 charge를 막는다.

가격이 없는 usage가 있으면 run은 `FAILED_VALIDATION`으로 멈춘다. Operator가 겹치지 않는 historical price gap을 명시적으로 append한 뒤 run의 scan checkpoint를 초기화하고 같은 cutoff로 재개한다. 기존 charge는 unique key 때문에 다시 청구되지 않고, 이전에 unpriced였던 usage만 새 charge를 얻는다.

## 불변 invoice와 adjustment

Invoice는 `READY_TO_FINALIZE` run의 ledger snapshot에서 생성한다. Line은 meter, price version, entry type으로 묶고 각 ledger entry를 provenance row로 연결한다. Period, close run, invoice를 한 transaction에서 finalize한다.

Late usage는 finalized service period를 바꾸지 않는다. Server posting time의 유일한 OPEN period에 양수 `DEBIT_ADJUSTMENT`를 append한다. Credit도 양수 amount와 `CREDIT_ADJUSTMENT` direction을 사용하고 original ledger entry를 연결한다.

## Reconciliation과 stale-safe repair

Reconciliation은 billing authority를 수정하지 않고 여섯 finding snapshot만 남긴다.

- cutoff 전·후 unledgered usage
- ledger와 occurred-time price 불일치
- invoice line aggregate 불일치
- invoice total 불일치
- tenant 또는 currency authority 불일치

Late-usage repair는 command receipt로 idempotent하게 실행한다. Finding의 expected digest와 현재 usage digest가 같고 기존 debit이 없을 때만 append한다. 첫 repair 뒤 같은 finding을 다시 사용하면 stale로 거부한다.

## 실패와 수정

초기 구현은 reconciliation이 unledgered usage 한 유형만 찾았고 stress task에는 실행할 test가 없었다. 여섯 유형을 Exposed query로 분리하고, audit write와 repair command를 분리했다. 10,000건 stress scenario는 batch마다 새 `BillingCloseService`를 구성해 in-memory worker 상태에 의존하지 않는지 확인하도록 만들었다.

또한 command 실패가 receipt를 계속 `IN_PROGRESS`로 남기는 문제를 수정했다. Validation/state failure는 terminal failed response로 기록하고, 예상하지 못한 infrastructure failure만 lease takeover 대상으로 남겼다.

## 검증 evidence

- Container-free unit/architecture: tenant mismatch, required idempotency key, Kotlin null-safety, mandatory repository, append-only mutation, raw JDBC 금지
- PostgreSQL integration: 20-way receipt owner election, replay/conflict, lease takeover, stale owner CAS, terminal-only cleanup
- End-to-end: usage → close → invoice → late debit → credit → reconcile
- Recovery: price gap failure → explicit gap repair → same-cutoff resume
- Reconciliation: 여섯 finding type과 repair 후 stale rejection
- Stress: usage 10,000건, 500건 batch, 새 worker 반복 구성, charge 정확히 10,000건

Java 25가 이 모듈의 실제 runtime contract다. 로컬 GraalVM JDK 25 launcher가 `_dyld_start`에서 정지한 환경에서는 Java 21 override로 compile, Detekt, PostgreSQL behavior를 진단했지만 `MeteringRuntimeContractTest`와 최종 Java 25 full suite는 정상 JDK 25 환경 또는 CI가 권위가 된다.

## Production 적용 경계

이 예제는 flat unit price, 단일 currency period, 하나의 PostgreSQL authority에 집중한다. Tax, tiered pricing, refund/payment, full Event Sourcing은 포함하지 않는다. 마이크로서비스로 분리할 때도 ingestion과 pricing ownership을 먼저 나누고, period/close/ledger/invoice transaction boundary는 함께 유지해야 한다. Outbox와 broker는 그 이후에 추가하며 database unique/CAS를 대신하지 않는다.
