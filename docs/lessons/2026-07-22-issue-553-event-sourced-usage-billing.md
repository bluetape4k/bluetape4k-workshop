# Issue #553 Event-Sourced Usage Billing 구현 교훈

## Context

기존 #552 예제는 PostgreSQL의 정규화된 상태와 불변 ledger/invoice를 권위로 삼는 실전적인 baseline이다. #553은 감사 가능한 전체 사실 이력, 특정 시점 replay, schema 진화, 무중단 projection 교체가 실제 요구사항인 조직을 위해 별도 advanced 예제로 분리했다. 두 접근을 한 모듈의 옵션으로 섞지 않은 이유는 Event Sourcing의 운영 비용을 숨기지 않기 위해서다.

## Decision or Finding

### Event store가 유일한 domain authority다

Stream은 tenant, type, ID, version으로 식별하고 global position은 projection의 keyset 순서를 제공한다. Append transaction은 expected stream version을 검증하고 canonical payload/metadata와 이전 hash를 묶어 새 hash를 만든다. 저장한 payload를 upcast 결과로 덮어쓰지 않는다.

Reducer는 I/O가 없는 pure fold로 유지했다. Replay는 hash 검증 뒤에 schema upcast와 decode를 수행한다. 이 순서가 깨지면 변조된 payload를 변환 과정이 정상화해 버릴 수 있다.

### Snapshot과 projection은 폐기 가능한 가속 계층이다

Snapshot은 reducer version, stream version, last event hash가 일치할 때만 사용한다. 검증에 실패하면 genesis replay로 돌아간다. Projection은 generation key로 격리하고 ACTIVE만 query가 읽는다. BUILDING generation은 capture한 high watermark까지 따라잡은 뒤 owner fencing을 포함한 조건부 switch로 ACTIVE가 된다.

Poison event는 건너뛰지 않는다. 실패 position과 digest를 quarantine하고 shadow generation만 FAILED로 만든다. 기존 ACTIVE view를 유지하는 것이 “계속 진행”보다 안전하다.

### HTTP 멱등성과 stream concurrency는 별도 guard다

Command receipt는 key digest와 request fingerprint로 exact response를 replay한다. Owner lease takeover와 terminal owner-token CAS가 오래된 요청의 완료를 차단한다. Event append의 expected version은 같은 stream에 대한 동시 business decision을 차단한다. 둘 중 하나만으로 다른 문제를 해결하려 하지 않았다.

### Exposed repository contract를 데이터 접근 경계로 고정했다

모든 concrete repository는 `ExposedJdbcRepository`를 구현한다. Production과 test fixture 모두 Exposed DAO/DSL 및 `SchemaUtils`만 사용한다. PostgreSQL lock, unique, compare-and-set 의미도 raw SQL로 우회하지 않고 Exposed expression과 transaction 안에서 표현했다.

## Outcome

- Meter, Usage, Billing Period, Invoice, Adjustment reducer와 append-only event store
- Canonical hash chain, schema-version codec/upcaster registry, deterministic replay
- 검증 가능한 snapshot fallback
- owner-token lease/fencing, marker, checkpoint, generation state를 갖춘 projection worker
- restartable bounded billing close와 append-only debit/credit correction
- replay authority와 ACTIVE read model을 비교하는 reconciliation
- tenant security, bounded read-your-write wait, projection headers, Micrometer, Actuator health
- modular monolith에서 service-owned database/outbox/inbox로 가는 microservice extraction guide

## Verification

- Container-free unit/architecture test: reducer, hash, upcast chain, fingerprint, repository/Exposed/Kotlin contract
- PostgreSQL integration test: append concurrency, receipt takeover/CAS, snapshot validation, projection fencing/recovery, correction/reconciliation, tenant isolation, live Spring Boot HTTP
- Stress test: usage event 10,000건, bounded close, `UsageRated` 10,000건, BUILDING generation 2 full replay, ACTIVE switch, total/provenance/reconciliation 일치
- Diagram QA: 8개 SVG/PNG에 대해 XML, text normalization, connector, geometry, endpoint, mixed-corner, sequence-style 검사

초기 stress 구성은 ACTIVE와 BUILDING 두 projection을 동시에 처리하고 큰 batch를 사용해 로컬 PostgreSQL 연결을 과도하게 압박했다. 정확성 증거에 불필요한 ACTIVE 재처리를 제거하고 BUILDING full rebuild만 batch 100으로 실행하자 동일한 계약을 안정적으로 검증할 수 있었다. Stress test의 목표는 최대 처리량 숫자가 아니라 bounded recovery다.

## Future Guidance

- Baseline ledger로 요구를 충족할 수 있으면 #552를 선택한다. Event Sourcing을 감사 로그의 동의어로 사용하지 않는다.
- 새 event schema는 반드시 연속 upcaster와 과거 fixture replay test를 함께 추가한다.
- Projection handler 실패를 skip하거나 checkpoint만 전진시키지 않는다.
- Read-your-write는 무한 wait가 아니라 client position과 짧은 timeout을 사용한다.
- 마이크로서비스 분리는 stream/event 운영이 안정된 뒤 service-owned database, transactional outbox, at-least-once, inbox dedup 순으로 진행한다.
- 재사용 가능한 projection job fencing lease는 bluetape4k-projects #1070의 라이브러리 경계 결정을 따른다.
