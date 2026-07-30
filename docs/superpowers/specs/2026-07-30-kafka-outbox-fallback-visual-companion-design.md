# Kafka Outbox Fallback Visual Companion 설계

날짜: 2026-07-30
저장소: `bluetape4k-workshop`
대상 모듈: `:messaging-kafka-outbox-fallback`
문서 범위: 한국어·영어, Light·Dark Theme, 데스크톱·모바일

## 1. 목적

이 Visual Companion은 Kafka-first Outbox Fallback 예제가 선택한 설계와 장애 복구 방식을 설명한다.
독자는 먼저 기존 Transactional Outbox와 Kafka-first Fallback의 차이를 비교하고, 이어서 정상 발행과
장애 복구 흐름을 직접 실행한다.

문서의 핵심 질문은 다음과 같다.

> 주문 트랜잭션의 Outbox 쓰기를 줄이면서 Kafka 장애 시 이벤트 발행을 어떻게 복구하는가?

독자는 문서를 읽은 뒤 다음 내용을 설명할 수 있어야 한다.

1. 기존 Transactional Outbox와 Kafka-first Fallback의 정상 처리 경로가 어떻게 다른가.
2. Kafka-first Fallback이 정상 주문의 DB 입력을 줄이는 대신 어떤 위험을 추가하는가.
3. Kafka 발행 실패와 타임아웃이 각각 어떤 상태를 만드는가.
4. `EventPublicationRelay`와 `PublicationReconciler`가 서로 다른 장애를 어떻게 복구하는가.
5. 고정된 `eventId`와 consumer의 중복 처리 방지가 왜 필요한가.
6. 실제 클래스, 테이블, 설정, API, 테스트가 각 설계 요구사항을 어떻게 구현하는가.

## 2. 독자와 사용 상황

주요 독자는 Spring Boot, Kafka, PostgreSQL, Exposed를 사용하는 백엔드 개발자다. Transactional
Outbox의 개념은 알지만, 모든 이벤트를 Outbox 테이블에 저장하지 않는 변형은 처음 접할 수 있다.

Visual Companion은 다음 상황에서 사용한다.

- 예제를 실행하기 전에 설계 의도와 적용 조건을 파악할 때
- Kafka 장애 시 DB와 Kafka에 어떤 데이터가 남는지 확인할 때
- `EventPublicationRelay`, `PublicationReconciler`, claim 컬럼의 역할을 소스코드와 연결할 때
- 이 방식을 운영 환경에 적용할 수 있는지 판단할 때
- README와 소스코드를 읽기 전에 전체 구조를 빠르게 파악할 때

## 3. 근거 자료

시각화의 사실과 용어는 다음 자료를 기준으로 한다.

- `messaging/kafka-outbox-fallback/README.ko.md`
- `messaging/kafka-outbox-fallback/README.md`
- `docs/superpowers/specs/2026-06-29-issue-348-kafka-outbox-fallback-design.md`
- `docs/superpowers/plans/2026-06-29-issue-348-kafka-outbox-fallback-plan.md`
- `docs/review/2026-06-29-issue-348-kafka-outbox-fallback-code-review.md`
- `docs/review/2026-07-03-issue-370-kafka-outbox-sql-review.md`
- `docs/review/2026-07-03-issue-371-kafka-outbox-contention-review.md`
- `messaging/kafka-outbox-fallback/src/main/kotlin`
- `messaging/kafka-outbox-fallback/src/test/kotlin`
- `docs/images/readme-diagrams/kafka-outbox-fallback-readme-architecture-01.svg`
- `docs/images/readme-diagrams/kafka-outbox-fallback-readme-state-01.svg`
- `docs/images/readme-diagrams/kafka-outbox-fallback-readme-sequence-01.svg`

기존 다이어그램은 정보 누락 여부를 확인하는 참고 자료로 사용한다. HTML Architecture Diagram,
Class Diagram, 상태 모델은 현재 소스에서 다시 구성하며 기존 SVG나 PNG를 설계 원본으로 사용하지 않는다.

## 4. 문서 구성 방식

### 4.1 검토한 구성

#### 구성 A: 설계 비교 후 장애 복구 시뮬레이션

기존 Transactional Outbox와 Kafka-first Fallback을 먼저 비교한다. 이어서 Kafka 장애와 복구
흐름을 대화형 시뮬레이션으로 실행한다.

장점:

- 예제를 만든 이유와 실제 동작을 한 문서에서 연결할 수 있다.
- Kafka-first Fallback을 일반적인 권장 방식으로 오해할 가능성을 줄인다.
- 정상 처리 비용과 장애 복구 비용을 분리해서 설명할 수 있다.

단점:

- 문서 길이가 길어진다.
- 비교와 시뮬레이션 사이의 용어를 일관되게 유지해야 한다.

#### 구성 B: 장애 복구 시뮬레이션 중심

첫 화면에서 Kafka 장애를 재현하고 relay와 reconciler를 실행한다.

장점:

- 실제 동작을 빠르게 확인할 수 있다.
- 대화형 요소가 문서의 중심이 된다.

단점:

- 기존 Transactional Outbox 대신 이 구조를 선택한 이유가 늦게 전달된다.
- 독자가 복구 기능만 보고 정상 처리 경로의 비용 절감 목적을 놓칠 수 있다.

#### 구성 C: 아키텍처와 클래스 구조 중심

Architecture Diagram, Class Diagram, 테이블, 상태 모델을 중심으로 구성한다.

장점:

- 구현 클래스를 분석하기 쉽다.
- 소스코드 탐색 지도로 사용할 수 있다.

단점:

- 문제 상황과 설계 선택의 이유가 구조 설명에 묻힌다.
- 처음 접하는 독자에게는 클래스 수가 많아 보일 수 있다.

### 4.2 선택한 구성

구성 A를 사용한다. 페이지는 **설계 비교 → 장애 복구 시뮬레이션** 순서로 진행한다. Class
Diagram과 구현 설명은 시뮬레이션 이후에 배치해, 독자가 먼저 동작을 이해한 뒤 소스 구조를 확인하도록
한다.

## 5. 제목과 첫 화면

한국어 제목:

> 주문 트랜잭션의 Outbox 쓰기를 줄이면서 Kafka 장애 시 이벤트 발행을 복구한다

영어 제목:

> Reduce Outbox Writes in Order Transactions While Recovering Event Publication After Kafka Failures

첫 화면은 다음 세 문장으로 예제의 전체 흐름을 설명한다.

1. 정상 처리에서는 `orders`만 저장한 뒤 Kafka로 직접 발행한다.
2. Kafka 발행에 실패하면 `event_publications`에 재발행 대상을 저장한다.
3. 재발행 대상 저장까지 실패하면 `PublicationReconciler`가 주문을 기준으로 발행 정보를 재구성한다.

첫 화면에 작업 선정 과정, 문서 생성 근거, Codex 작업 정보, 검증 로그를 표시하지 않는다. 모든 문장은
예제를 이해하는 독자에게 필요한 정보만 담는다.

## 6. 정보 구조

페이지는 다음 순서로 구성한다.

1. 고정 상단 내비게이션
2. 제목과 핵심 처리 흐름
3. 예제 시나리오와 설계 배경
4. Transactional Outbox와 Kafka-first Fallback 비교
5. Architecture Diagram
6. 장애 복구 시뮬레이션
7. 발행 상태 모델
8. Class Diagram
9. Exposed와 PostgreSQL 구현
10. 설정과 운영 시 고려사항
11. 실제 실행
12. 테스트로 확인한 동작
13. 관련 문서와 저장소 링크

상단 내비게이션은 기존 Visual Companion의 공통 구조를 사용한다. 저장소, 예제, 한국어·영어,
Theme 전환 위치를 기존 문서와 동일하게 유지한다.

## 7. 예제 시나리오와 설계 배경

기존 Transactional Outbox는 주문과 Outbox 이벤트를 같은 DB 트랜잭션에 저장한다. 이벤트 발행
정보가 주문과 함께 저장되므로 복구 기준이 명확하지만 모든 주문 처리에서 두 테이블을 갱신한다.

Kafka-first Fallback은 정상 처리 경로를 다음과 같이 변경한다.

```text
orders 저장 → 트랜잭션 커밋 → Kafka 직접 발행
```

`event_publications`에는 모든 이벤트를 저장하지 않는다. 다음 경우에만 재발행 정보를 저장한다.

- Kafka 발행 실패
- Kafka 발행 타임아웃
- `direct-publish-enabled=false`
- `PublicationReconciler`가 누락된 발행 정보를 복구한 경우

이 방식은 정상 주문의 DB 입력을 줄이지만 다음 조건을 추가한다.

- Kafka 응답 대기 시간이 주문 API 응답 시간에 포함된다.
- 타임아웃이 발생하면 Kafka가 이벤트를 저장했는지 확정할 수 없다.
- Kafka 발행과 재발행 정보 저장이 모두 실패할 수 있다.
- reconciler가 이미 발행된 이벤트를 다시 구성할 수 있다.
- consumer는 `eventId`를 기준으로 중복 이벤트를 처리해야 한다.

이 문서는 Kafka-first Fallback을 일반적인 대체 방식으로 소개하지 않는다. 주문 트랜잭션의 DB 쓰기
부하가 실제 병목이고, 중복 이벤트를 처리할 수 있으며, 누락된 발행 정보를 점검할 운영 절차가 있을
때 검토할 수 있는 절충안으로 설명한다.

## 8. 설계 비교 UI

### 8.1 상호작용

`Transactional Outbox`와 `Kafka-first Fallback`을 선택하는 분할 컨트롤을 제공한다. 선택한 방식에
따라 다음 요소가 함께 변경된다.

- 정상 처리 흐름
- DB 입력 대상
- Kafka 발행 시점
- API 응답과 Kafka 대기의 관계
- 장애 복구 기준
- 중복 발행 위험
- 적합한 사용 조건

### 8.2 비교 내용

| 항목 | Transactional Outbox | Kafka-first Fallback |
|---|---|---|
| 주문 트랜잭션 | `orders`와 Outbox row 저장 | `orders`만 저장 |
| 정상 주문의 DB 입력 | 2건 | 1건 |
| Kafka 발행 시점 | relay가 비동기로 발행 | 주문 커밋 후 API 처리 중 직접 발행 |
| 발행 정보 저장 | 모든 이벤트 | 장애 또는 복구 대상 이벤트 |
| Kafka 장애 시 주문 저장 | 영향 없음 | 주문 저장 후 fallback 경로 실행 |
| 발행 여부 확인 기준 | Outbox row | Kafka 응답 또는 fallback row |
| 누락 정보 재구성 | 일반적으로 불필요 | reconciler 필요 |
| 중복 처리 방지 | 권장 | 필수 |

`정상 주문의 DB 입력`은 벤치마크 결과가 아니라 구현 구조에서 확인되는 입력 건수다. 처리량,
지연 시간, 비용 절감률처럼 측정하지 않은 수치는 표시하지 않는다.

## 9. Architecture Diagram

### 9.1 독자 질문

Architecture Diagram은 다음 질문에 답해야 한다.

> 주문 저장, Kafka 직접 발행, 재발행 정보 저장, relay, reconciliation을 각각 어떤 구성 요소가
> 담당하는가?

시간 순서를 자세히 표현하지 않고 구성 요소의 책임과 연결 관계를 보여준다. 단계별 시간 순서는 장애
복구 시뮬레이션에서 다룬다.

### 9.2 구성 요소

API 계층:

- `OrderController`
- `PublicationController`

애플리케이션 계층:

- `PlaceOrderUseCase`
- `PublicationQueryService`

도메인·저장 계층:

- `TransactionalOrderWriter`
- `EventPublicationRepository`
- `OrderTable`
- `EventPublicationTable`
- PostgreSQL `orders`
- PostgreSQL `event_publications`

메시징 계층:

- `OrderEventPublisher`
- Kafka `order-events`

복구·운영 계층:

- `EventPublicationRelay`
- `PublicationReconciler`
- `OutboxMetrics`

### 9.3 연결 관계

```text
OrderController
    ↓
PlaceOrderUseCase
    ├─ TransactionalOrderWriter
    │      ↓
    │   PostgreSQL.orders
    │
    └─ OrderEventPublisher
           ├─ 성공 → Kafka.order-events
           └─ 실패 → EventPublicationRepository
                         ↓
                  PostgreSQL.event_publications

EventPublicationRelay
    ├─ event_publications claim
    └─ Kafka 재발행

PublicationReconciler
    ├─ 발행 정보가 없는 orders 조회
    └─ event_publications 재구성
```

### 9.4 시각 규칙

- API 계층: 청록색
- 애플리케이션 계층: 파란색
- 저장 계층: 녹색
- 메시징 계층: 주황색
- 복구·운영 계층: 자홍색
- 장애 또는 최종 실패 상태: 빨간색

색상만으로 의미를 전달하지 않는다. 계층명, 아이콘, 선 종류를 함께 사용한다. 카드 내부의 제목과
보조 문구는 가로·세로 중앙에 배치한다. 모든 peer card는 동일한 정렬 방식을 사용한다.

한국어와 영어 HTML은 각각 독립된 텍스트를 사용한다. 긴 영문 클래스명은 제목과 역할 설명을 분리해
가독성을 유지한다. 글꼴 크기를 과도하게 줄여 카드에 맞추지 않는다.

## 10. 장애 복구 시뮬레이션

### 10.1 공통 UI

시나리오 선택 메뉴:

- 정상 발행
- Kafka 발행 실패
- Kafka 발행 타임아웃
- relay 복구
- relay 반복 실패
- fallback 저장 실패와 reconciliation

조작:

- `재생`
- `다음 단계`
- `이전 단계`
- `초기화`

고정 상태 패널:

- API 결과
- `orders` row 수
- `event_publications` row 수
- Kafka 이벤트 수
- 직접 발행 시도 횟수
- relay 재시도 횟수
- 발행 상태
- claim 소유자
- 다음 처리 시각
- 현재 위험 또는 운영 조치

각 시나리오는 단계가 바뀌어도 패널 크기가 변하지 않아야 한다.

### 10.2 정상 발행

단계:

1. `POST /api/orders`
2. `TransactionalOrderWriter`가 `orders` 저장
3. 주문 트랜잭션 커밋
4. `OrderEventPublisher`가 Kafka 발행
5. Kafka 발행 확인
6. API가 `PUBLISHED_DIRECT` 반환

최종 상태:

- `orders`: 1건
- `event_publications`: 0건
- Kafka 이벤트: 1건
- API 결과: `PUBLISHED_DIRECT`

### 10.3 Kafka 발행 실패

단계:

1. 주문 저장과 커밋
2. Kafka 직접 발행 1차 실패
3. Kafka 직접 발행 2차 실패
4. Kafka 직접 발행 3차 실패
5. `event_publications`에 `NOT_PUBLISHED` row upsert
6. API가 `FALLBACK_STORED` 반환

최종 상태:

- `orders`: 1건
- `event_publications`: 1건
- `direct_attempt_count`: 3
- `relay_retry_count`: 0
- `status`: `NOT_PUBLISHED`
- API 결과: `FALLBACK_STORED`

### 10.4 Kafka 발행 타임아웃

애플리케이션은 타임아웃 이후 발행 결과를 확정할 수 없다. Kafka가 이벤트를 저장했지만 응답만
지연되었을 수 있다.

시뮬레이션은 두 가능성을 동시에 표시한다.

```text
애플리케이션 관점: 발행 결과 확인 실패
Kafka 관점: 이벤트 수신 여부 불명
복구 처리: 같은 eventId로 NOT_PUBLISHED row 저장
```

경고:

> 타임아웃은 이벤트가 발행되지 않았다는 뜻이 아니다. relay가 같은 이벤트를 다시 발행할 수 있으므로
> consumer는 `eventId`를 기준으로 중복 이벤트를 처리해야 한다.

### 10.5 Relay 복구

단계:

1. `EventPublicationRelay`가 처리 가능한 row 조회
2. 작업자 ID와 claim 만료 시각 설정
3. Kafka 재발행
4. 발행 확인
5. `status=PUBLISHED`, `published_at` 설정
6. claim 컬럼 초기화

최종 상태:

- `status`: `PUBLISHED`
- `claimed_by`: `null`
- `published_at`: 설정됨

동시 claim 보기에서는 작업자 두 개가 같은 row를 요청한다. 한 작업자만 row를 claim하고 다른 작업자는
빈 결과를 받는다. 이 화면은 정확히 한 번 발행을 보장한다고 설명하지 않는다. claim은 같은 시점에
두 relay가 같은 row를 처리하는 상황을 방지하지만, Kafka 발행 성공 후 DB 상태 변경 전에 프로세스가
중단되면 중복 발행이 발생할 수 있다.

### 10.6 Relay 반복 실패

단계:

```text
NOT_PUBLISHED
→ claim
→ Kafka 실패
→ FAILED
→ 재처리
→ FAILED
→ 재처리
→ DEAD_LETTER
```

`relay-max-retries=3`을 기준으로 `relay_retry_count`와 상태를 변경한다. `DEAD_LETTER` row는 자동
삭제하거나 무한 재시도하지 않는다. 운영자가 오류 원인과 재처리 여부를 확인해야 한다.

### 10.7 Fallback 저장 실패와 Reconciliation

단계:

1. 주문 저장과 커밋
2. Kafka 직접 발행 실패
3. `event_publications` 저장 실패
4. API가 `FALLBACK_STORE_FAILED` 반환
5. grace period 경과
6. `PublicationReconciler`가 발행 정보가 없는 주문 조회
7. `OrderPlacedEvent`와 고정된 `eventId` 재구성
8. `NOT_PUBLISHED` row upsert
9. relay 처리 대상으로 전환

reconciler는 이벤트가 Kafka에 발행되지 않았음을 증명하지 않는다. 이미 직접 발행된 이벤트의 응답만
유실된 경우에도 발행 정보를 재구성할 수 있다. 이 기능은 누락 방지 장치이며, 중복 발행 가능성을
제거하지 않는다.

## 11. 발행 상태 모델

상태 모델은 다음 전이를 보여준다.

```text
NO ROW
   │ Kafka 실패 / 직접 발행 비활성화 / reconciler 복구
   ▼
NOT_PUBLISHED
   │ relay claim
   ▼
CLAIMED
   ├─ 발행 성공 ────────────────→ PUBLISHED
   └─ 발행 실패
          ├─ retry 가능 ────────→ FAILED ──→ CLAIMED
          └─ retry 한도 도달 ───→ DEAD_LETTER
```

`CLAIMED`는 `EventPublicationStatus` enum 값이 아니다. `claimedBy`와 `claimedUntil`이 유효한
동안의 처리 상태다. 상태 선택 시 의미, 진입 조건, 다음 상태, 운영 조치를 표시한다.

## 12. Class Diagram

### 12.1 독자 질문

Class Diagram은 다음 질문에 답해야 한다.

> 시나리오의 각 단계는 실제 어떤 클래스와 메서드가 담당하는가?

### 12.2 클래스와 메서드

```text
OrderController
+ placeOrder(request): ResponseEntity<OrderResponse>

PlaceOrderUseCase
+ placeOrder(request): OrderResponse

TransactionalOrderWriter
+ saveOrder(customerId, product, quantity): OrderRecord
+ getOrder(orderId): OrderRecord

OrderEventPublisher
+ publishDirectOrFallback(event): OrderPublicationStatus

EventPublicationRepository
+ upsertNotPublished(...)
+ upsertReconstructed(...)
+ claimNextBatch(...)
+ markPublished(...)
+ markRelayFailure(...)
+ findOrdersWithoutPublicationsCreatedOnOrBefore(...)

EventPublicationRelay
+ scheduledRelay()
+ relayOnce(): RelayResult

PublicationReconciler
+ scheduledReconcile()
+ reconcileOnce(): ReconcileResult

PublicationQueryService
+ findAll(): List<PublicationResponse>

OutboxMetrics
+ recordDirectPublish(result)
+ recordFallbackStored(result)
+ recordRelay(result)
+ recordReconciler(result)
```

Class Diagram은 모든 생성자 인수와 보조 메서드를 나열하지 않는다. 시나리오와 직접 연결되는
책임·메서드·사용 관계만 표시한다.

## 13. Exposed와 PostgreSQL 구현

### 13.1 테이블

`OrderTable`은 `orders`를 정의한다.

- 주문 ID
- 고객 ID
- 상품
- 수량
- 주문 상태
- 생성·변경 시각

`EventPublicationTable`은 `event_publications`를 정의한다.

- 고유한 `event_id`
- aggregate와 event 유형
- 직렬화된 페이로드
- 발행 상태
- 직접 발행 시도 횟수
- relay 재시도 횟수
- 정제된 오류 정보
- 다음 처리 시각
- claim 소유자와 만료 시각
- 생성·발행·변경 시각

### 13.2 트랜잭션 경계

`TransactionalOrderWriter.saveOrder()`의 트랜잭션은 `orders`만 변경한다. Kafka 직접 발행과
`event_publications` 입력은 주문 트랜잭션 커밋 이후에 실행한다.

### 13.3 고정된 Event ID

`OrderPlacedEvent.from(order)`는 다음 형식의 ID를 사용한다.

```text
order-placed:{orderId}:v1
```

`event_publications.event_id`의 고유 제약과 upsert가 같은 주문의 재구성 및 재시도를 반복해도 동일한
발행 정보를 사용하게 한다.

### 13.4 Claim

`claimNextBatch()`는 처리 가능한 상태, `next_attempt_at`, claim 만료 여부, 정렬, batch limit을
SQL에서 처리한다. row를 읽은 뒤 애플리케이션 메모리에서만 선별하지 않는다.

### 13.5 누락 발행 정보 조회

`findOrdersWithoutPublicationsCreatedOnOrBefore()`는 grace period를 지난 주문 중 대응하는
`event_publications` row가 없는 주문을 SQL에서 찾는다. Visual Companion은 이를 “주문 전체를
읽어 애플리케이션에서 비교”하는 흐름으로 표현하지 않는다.

## 14. 설정과 운영 시 고려사항

표시할 주요 설정:

```yaml
workshop:
  kafka-outbox-fallback:
    topic: order-events
    direct-publish-attempts: 3
    direct-publish-timeout: 500ms
    direct-publish-total-timeout: 1600ms
    relay-max-retries: 3
    relay-batch-size: 25
    relay-fixed-delay: 2000ms
    relay-claim-ttl: 30s
    reconciler-grace: 30s
    max-payload-bytes: 8192
    direct-publish-enabled: true
    relay-enabled: true
    reconciler-enabled: true
    demo-admin-endpoints-enabled: false
```

운영 시 고려사항:

- 타임아웃은 Kafka 발행 실패를 확정하지 않는다.
- Kafka record key와 이벤트 본문은 고정된 `eventId`를 사용한다.
- consumer는 같은 `eventId`를 다시 처리해도 결과가 중복되지 않아야 한다.
- `FALLBACK_STORE_FAILED`는 주문은 있지만 영속적인 발행 정보가 없는 상태다.
- `DEAD_LETTER`는 운영자 확인 전까지 보존한다.
- demo admin endpoint는 인증, 권한, 요청 제한 없이 운영 환경에 노출하지 않는다.
- 조회 API는 원본 페이로드, stack trace, credential, token, broker URL을 반환하지 않는다.
- reconciler 범위와 인덱스는 실제 데이터 규모에 맞춰 조정해야 한다.

## 15. 실제 실행

### 15.1 관련 서비스

- PostgreSQL
- Kafka

`application.yml`의 접속 정보를 기준으로 애플리케이션을 실행한다. 테스트는 저장소의 Testcontainers
launcher로 PostgreSQL과 Kafka를 시작한다.

### 15.2 표시할 실행 절차

1. 모듈 테스트 실행
2. 관련 서비스 준비
3. 애플리케이션 실행
4. 주문 생성
5. 주문과 발행 정보 조회
6. demo admin endpoint 활성화
7. relay 수동 실행
8. reconciliation 수동 실행

실행 명령은 구현 단계에서 현재 Gradle task와 설정으로 직접 검증한다. 검증되지 않은 wildcard
테스트 필터나 환경 변수를 문서에 포함하지 않는다.

표시할 API:

```text
POST /api/orders
GET  /api/orders
GET  /api/orders/{id}
GET  /api/publications
POST /api/publications/relay
POST /api/publications/reconcile
```

`POST /api/publications/relay`와 `POST /api/publications/reconcile`은
`demo-admin-endpoints-enabled=true`일 때만 실행할 수 있음을 명시한다.

## 16. 테스트로 확인한 동작

문서 하단에서 실제 테스트와 보장하는 동작을 연결한다.

| 검증 대상 | `KafkaOutboxFallbackFlowTest`의 테스트 |
|---|---|
| 주문 트랜잭션이 `orders`만 저장 | `transactional writer stores only order row` |
| 유효하지 않은 요청의 저장 차단 | `POST api-orders rejects invalid input with safe 400 and zero persistence` |
| Kafka 직접 발행 성공 | `placeOrder stores only order row and returns PUBLISHED_DIRECT when direct Kafka publish succeeds` |
| 세 번 실패 후 fallback 저장 | `direct publish retries three times then stores NOT_PUBLISHED fallback row` |
| 타임아웃 후 fallback 저장 | `direct publish timeout stores NOT_PUBLISHED fallback row` |
| fallback 저장 실패 노출 | `fallback insert failure returns FALLBACK_STORE_FAILED and records safe metric and log` |
| relay 성공 후 `PUBLISHED` 전환 | `relay publishes fallback row and marks it PUBLISHED` |
| 재시도 한도 후 `DEAD_LETTER` 전환 | `relay failure increments retry and moves to DEAD_LETTER` |
| 동시 relay에서 한 작업자만 claim | `concurrent relay calls cannot claim the same row twice` |
| 만료된 claim 재처리 | `stale relay claim becomes eligible after claim ttl` |
| SQL에서 처리 대상 정렬과 제한 | `claimNextBatch applies SQL eligibility ordering and limit` |
| 누락된 발행 정보 재구성 | `reconciler reconstructs deterministic fallback row and documents duplicate risk` |
| SQL cutoff와 anti-join | `reconciler uses SQL cutoff and anti join for missing publications` |
| 조회 API의 민감 정보 제외 | `publication endpoint never exposes raw payload or raw exception text` |
| 비활성화된 scheduler | `scheduled relay and reconciler do nothing when disabled` |
| 메트릭 기록 | `metrics record direct failure fallback relay and reconciler outcomes` |

테스트 목록은 결과 근거이므로 본문 후반에 배치한다.

## 17. 다국어와 Theme

### 17.1 파일

저장소의 Visual Companion 명명 규칙에 따라 영어 파일과 한국어 파일을 각각 생성한다.

- English: `*.html`
- Korean: `*.ko.html`

두 파일은 상호 링크를 제공한다.

### 17.2 문서 일치

다음 항목은 한국어와 영어에서 일치해야 한다.

- 섹션 순서
- 시나리오 수와 단계
- 클래스와 메서드
- 설정 키와 값
- 상태와 전이
- API와 명령
- 테스트 이름
- 저장소와 문서 링크

한국어 문서는 번역체 대신 국내 소프트웨어 기술문서에서 통용되는 용어를 사용한다. 코드 식별자는
변경하지 않는다. 예를 들어 `EventPublicationRelay`, `fallback`, `claim`은 코드와 연결되는
위치에서 그대로 표시하고, 설명 문장에서는 재발행, 대체 발행 정보 저장, 처리 대상 확보처럼 실제
동작을 함께 설명한다.

### 17.3 Theme

- `auto`
- `light`
- `dark`

첫 방문은 사이트 또는 OS 설정을 따른다. 사용자가 선택한 Theme은 기존 Visual Companion과 같은
방식으로 유지한다. Light Theme에서는 카드와 페이지 배경이 명확히 구분되어야 한다. Dark Theme에서는
빨간색과 자홍색 상태가 검은 배경에서 뭉개지지 않아야 한다.

## 18. 반응형과 접근성

- 데스크톱, 태블릿, 모바일에서 가로 스크롤이 발생하지 않아야 한다.
- Architecture Diagram과 Class Diagram은 작은 화면에서 계층 또는 관계 단위로 세로 배치한다.
- 시뮬레이션 상태 패널은 모바일에서 한 열 또는 두 열로 재배치한다.
- 긴 클래스명과 테스트명은 줄바꿈하되 카드 경계를 침범하지 않는다.
- 버튼에는 명확한 `aria-label` 또는 화면에 표시되는 텍스트를 제공한다.
- 키보드로 탭, 시나리오, 단계 이동, Theme, 언어 링크를 사용할 수 있어야 한다.
- 색상 외에 상태명, 아이콘, 선 종류로 의미를 구분한다.
- 애니메이션 감소 설정에서는 자동 재생과 이동 효과를 줄인다.

## 19. 구현 범위

포함:

- 한국어·영어 독립 HTML
- Light·Dark·Auto Theme
- 기존 Visual Companion 공통 내비게이션
- Transactional Outbox와 Kafka-first Fallback 비교 UI
- Theme 대응 Architecture Diagram
- 여섯 가지 장애·복구 시나리오
- 대화형 발행 상태 모델
- Theme 대응 Class Diagram
- Exposed와 PostgreSQL 구현 설명
- 실제 실행 절차
- 실제 테스트와 동작 연결
- Visual Companion manifest 등록
- 재현 가능한 생성 스크립트

제외:

- 모듈의 Kotlin 구현 변경
- README 내용 변경
- 새로운 벤치마크 수행
- Kafka consumer 구현
- Redis Streams 대체 저장소 구현
- 운영용 인증·권한 기능 구현
- 기존 README SVG·PNG 수정
- 자동 배포 또는 PR 병합

## 20. 검증 기준

### 20.1 내용 검증

- 모든 클래스, 메서드, 상태, 설정 키, API, 테스트 이름이 현재 소스와 일치한다.
- 측정하지 않은 처리량, 지연 시간, 비용 절감률을 주장하지 않는다.
- 타임아웃과 발행 실패를 같은 상태로 설명하지 않는다.
- claim이 exactly-once 발행을 보장한다고 설명하지 않는다.
- reconciler가 미발행을 증명한다고 설명하지 않는다.
- `CLAIMED`를 enum 상태로 표현하지 않는다.

### 20.2 기능 검증

- 한국어·영어 링크가 서로 올바르게 연결된다.
- `auto`, `light`, `dark` Theme이 모두 동작한다.
- 비교 방식 전환 시 모든 설명과 지표가 함께 변경된다.
- 여섯 시나리오에서 이전·다음·초기화가 동작한다.
- 시나리오 최종 상태가 이 설계의 값과 일치한다.
- 동시 claim 시각화에서 한 작업자만 처리 대상을 확보한다.
- 상태 모델 선택 시 설명과 허용 전이가 일치한다.

### 20.3 시각 검증

- 한국어·영어, Light·Dark, 데스크톱·모바일을 각각 캡처한다.
- Architecture Card와 Class Card 내부 텍스트의 수직 중앙 정렬 오차를 검사한다.
- 페이지와 카드의 가로 overflow가 0이어야 한다.
- 텍스트 잘림, 겹침, 카드 경계 접촉이 없어야 한다.
- 연결선이 카드 내부나 계층 제목을 통과하지 않아야 한다.
- 계층 색상과 상태 색상이 Light·Dark Theme에서 모두 구분되어야 한다.
- 브라우저 console error가 0이어야 한다.

### 20.4 저장소 검증

- `node scripts/validate-visual-companions.mjs`
- 생성 스크립트 재실행 후 diff 없음
- `git diff --check`
- `./gradlew :messaging-kafka-outbox-fallback:test --max-workers=1`

실제 실행 명령은 구현 단계에서 별도로 검증한다.

## 21. 완료 조건

다음 조건을 모두 충족하면 구현이 완료된 것으로 판단한다.

1. 한국어·영어 Visual Companion이 같은 정보를 제공한다.
2. Light·Dark·Auto Theme이 기존 Visual Companion과 일관되게 동작한다.
3. 독자가 비교 UI에서 두 Outbox 방식을 구분할 수 있다.
4. 독자가 시뮬레이션에서 Kafka 장애부터 relay 또는 reconciliation까지 진행할 수 있다.
5. Architecture Diagram과 Class Diagram이 실제 클래스·테이블 관계와 일치한다.
6. 실행 절차와 테스트 명령이 현재 저장소에서 동작한다.
7. 모든 시각·기능·저장소 검증이 통과한다.
8. 작업 근거나 검증 로그가 독자용 화면에 노출되지 않는다.
