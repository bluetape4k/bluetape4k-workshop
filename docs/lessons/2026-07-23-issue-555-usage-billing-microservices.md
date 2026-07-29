# Issue #555 사용량 과금 마이크로서비스 구현 교훈

## Context

#552의 단일 PostgreSQL ledger와 #553의 단일 event-store authority는 분산 배포가 필요하지 않은 경우 더
단순하고 강한 선택이다. #555는 Meter, Usage, Billing, Invoice, Query가 실제로 독립 배포·소유·확장되어야
하는 경우에만 선택하는 예제다. 서비스마다 PostgreSQL과 decoder를 따로 두고 Kafka는 at-least-once
transport로만 사용한다.

## Decision or Finding

### Local database가 correctness authority다

각 producer는 business fact와 outbox row를 같은 Exposed transaction에 기록한다. Relay의 claim은 owner,
lease, fenced completion으로 보호한다. Kafka publish 성공 뒤 `PUBLISHED` 반영 전에 죽으면 duplicate가
발생할 수 있으므로 receiver는 `(tenantId, eventId, payloadDigest)` inbox 결정을 local effect보다 먼저
durable하게 만든다. Consumer offset이나 broker ack를 재무 완료 증거로 사용하지 않는다.

### Duplicate와 poison은 서로 다른 정상 복구 경로다

같은 event ID와 같은 digest는 성공한 duplicate다. 같은 ID와 다른 digest, 지원하지 않는 mandatory
schema는 correctness conflict이므로 Query quarantine에 event metadata와 이유를 보존한다. Permanent failure가
독립 aggregate의 진행을 막지 않게 하되, redrive는 payload 편집이나 자동 재전달이 아닌 audited request로 제한한다.

### Correction은 원본 갱신이 아니라 새 사실이다

Billing이 adjustment command authority를 소유하고 `AdjustmentPosted`가 original `ChargeRated` event ID를
`correctionOf`로 참조한다. Invoice는 기존 line을 바꾸지 않고 correction line을 append하며 Query는 원본과
보정을 함께 보여 준다. 이 방식은 replay와 reconciliation에서 이력의 원인을 잃지 않는다.

### Test fixture도 Exposed-only 경계를 지킨다

Production뿐 아니라 composition fixture에서도 raw JDBC, `JdbcTemplate`, `DriverManager`, statement 생성,
`Transaction.exec`를 허용하지 않았다. PostgreSQL schema 준비와 검증 query도 Exposed repository/DSL을 통해
수행해 예제가 가르치는 경계와 테스트 편의 코드가 충돌하지 않게 했다.

### 다이어그램도 실행 가능한 운영 계약의 일부다

Architecture, outbox/inbox state, delivery, poison recovery, correction, extraction 여섯 흐름을 source SVG와
CairoSVG PNG로 함께 관리한다. README validator는 두 locale과 6개 asset pair를 요구하고, diagram QA는
marker, endpoint, connector intrusion/crossing, mixed-corner, PNG render를 검사한다. SVG만 맞고 raster가
뒤집히거나 잘리는 상태를 완료로 보지 않는다.

### 기계적 diagram 검사에는 구조 의미도 포함해야 한다

XML과 connector 검사만 통과해도 sequence를 가장한 generic card가 남을 수 있다. 따라서 architecture와
temporal sequence를 구분하는 SVG kind marker를 두고, sequence에는 participant label pill, lifeline,
activation, numbered message, branch frame을 validator가 요구해야 한다. 각 SVG/PNG asset을 source path와
pixel size까지 ledger로 남기면, 이름만 맞는 중복 asset이나 raster에서 잘린 label을 완료로 오인하지 않는다.

### 경계 계약은 코드 스타일이 아니라 재처리 안전성이다

각 서비스의 durable data model에 명시적인 serialization ID를 두고, 외부 decoder는 Bluetape validation
helper로 required field를 검증하며, debug consumer log에는 raw financial payload 대신 event ID/type/reason만 남긴다.
이 세 가지를 architecture test로 고정하면 복사된 decoder가 validation·관측·재처리 contract를 조금씩
잃는 drift를 조기에 막을 수 있다.

### Broker path 단절은 bootstrap proxy만으로 증명되지 않는다

Host JVM client 앞에 proxy를 두더라도 Kafka metadata가 direct broker endpoint를 advertise하면 이후 연결은
proxy를 우회할 수 있다. `BrokerPathRecoveryIntegrationTest`는 Toxiproxy와 Kafka custom listener를 같은 Docker
network에 두고 custom listener의 advertised endpoint를 proxy mapped port로 고정한다. 양방향 toxic을 끊어
`RETRY_WAIT`와 기존 outbox backlog를 확인한 뒤, toxic 제거 후 같은 row를 재시도해 Usage price evidence를
관측한다. 이 단일 broker TCP path recovery는 leader election, ISR, replication, cluster failover 증명이 아니다.

## Outcome

- Java 25 / Spring Boot 4 기반 독립 서비스 5개와 composition test module
- 서비스별 PostgreSQL, Exposed repository, 독립 wire decoder
- Transactional outbox, durable inbox/quarantine, lease/fencing, replay-safe handler
- Append-only adjustment와 immutable Invoice correction history
- Kafka 1개와 PostgreSQL 5개를 사용하는 12개 composition integration test
- 단계적 extraction과 route-only rollback을 설명하는 영문/국문 README와 다이어그램 6종

## Verification

- Composition: 12 tests, failures/errors/skipped 0, including real broker-path recovery
- Aggregated Kover: line 2177 covered/188 missed, class 240 covered/19 missed
- Repository commerce smoke: 107 tasks PASS including aggregated Kover
- Repo-wide `detekt detektTest`: 68 tasks PASS
- README validator: locales 2, diagrams 6
- Diagram QA: explicit targets 6, geometry/endpoint/connector/marker failures 0
- `stale-check`, `actionlint`, `git diff --check`: PASS

## Future Guidance

- 하나의 transaction authority로 충분하면 #552를 유지하고 분산 실패 경계를 만들지 않는다.
- Service를 추가하면 composition Kover aggregation, smoke/full workflow, README validator, diagram generator를
  같은 branch에서 갱신한다.
- Broker 장애를 이유로 financial fact를 다시 만들지 말고 기존 outbox row와 lease를 복구한다.
- TCP path recovery와 multi-broker failover를 같은 증거로 취급하지 않는다. 후자는 #558에서 leader change와
  replication health를 별도로 증명한다.
- Query quarantine을 raw-envelope 보관소로 오인하지 말고, external retained source의 immutable envelope 조회와 redrive audit을 분리한다.
- Rollback은 routing만 되돌린다. 서비스 DB를 역복사하거나 published financial history를 rewrite하지 않는다.
