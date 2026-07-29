# Issue #348 Kafka-첫 번째 보낼 편지함 대체 디자인

날짜: 2026-06-29
저장소: `bluetape4k-workshop`
분기: `feat/issue-348-kafka-outbox-fallback`
이슈: https://github.com/bluetape4k/bluetape4k-workshop/issues/348

## 문제

`messaging/transactional-outbox`은 이미 고전적인 트랜잭션을 보여줍니다.
보낼 편지함 패턴: `orders` 및 `outbox_events`이 내부에 함께 기록됩니다.
동일한 데이터베이스 트랜잭션이 있고 스케줄러는 나중에 보낼 편지함 행을 게시합니다.
Kafka.

Issue #348은(는) 핫 쓰기가 가능한 시스템에 대한 보완 패턴을 요청합니다.
거래는 더 가벼워져야 합니다:

1. 트랜잭션 내부에 도메인 `orders` 행만 씁니다.
2. 커밋 후 도메인 이벤트를 Kafka에 직접 게시하세요.
3. Kafka 직접 게시를 최대 3회까지 다시 시도하세요.
4. Kafka을 사용할 수 없거나 재시도 예산이 소진된 경우 이벤트를 다음과 같이 저장합니다.
   `NOT_PUBLISHED` 내구성 있는 대체 저장소에 있습니다.
5. 새로운 모듈-로컬 발신함 스타일 릴레이가 대체 행을 Kafka에 게시하고
   재시도, `PUBLISHED` 및 데드 레터 상태를 거쳐 이동합니다.

이는 기존의 거래용 보낼 편지함을 엄격히 대체하는 것은 아닙니다. 그것은
명시적인 복구 및 조정 기능을 갖춘 트랜잭션 로드 우선 변형
절충안. HTTP 응답 지연 시간은 주요 성공 지표가 아닙니다.
direct Kafka 경로는 커밋 후 제한된 전송 확인을 기다립니다.

## 현재 증거

- `messaging/transactional-outbox`은 `orders`과 `outbox_events`를 하나로 작성합니다.
  거래. README는 고전적인 보증을 문서화하고,
  `OrderService.placeOrder`은 두 행을 함께 삽입합니다.
- `OutboxPublisher.publishEvent`은(는) Kafka에 동기적으로 보냅니다.
  `PUBLISHED` 행을 표시하기 전 `kafkaTemplate.send(...).get()`.
- `docs/lessons/2026-05-24-transactional-outbox-pattern.md`은 클래식을 녹음합니다.
  이중 쓰기 실패: DB 커밋 후 Kafka 실패로 인해 이벤트가 손실될 수 있습니다.
- `docs/lessons/2026-05-27-issue-228-domain-module-adoption.md` 명시적으로
  `messaging/transactional-outbox`을 `bluetape4k-kafka4`로 변경하지 말라고 합니다.
  현재 차단 전송 때문에 게시자 디자인이 변경되지 않는 한
  고전적인 성공 계약을 유지합니다.
- `messaging/kafka` 및 `messaging/kafka-reply`은 이미 Kafka 예제를 제공합니다.
  버전 카탈로그에 이미 `bluetape4k-kafka4`이(가) 있습니다.
  `bluetape4k-redis`, `bluetape4k-redisson`, 스프링 Kafka 테스트, Kafka
  Testcontainers 및 PostgreSQL Testcontainers 별칭.
- 기존 예제 워크플로에는 현재 `messaging/kafka/**`이 포함되어 있지만 포함되어 있지 않습니다.
  미래의 `messaging/kafka-outbox-fallback/**` 경로.

CodeGraph이(가) 루트 체크아웃의 `OutboxPublisher`을 찾았습니다. 새로운 작업 트리는 그렇지 않았습니다
해당 기호에 대해서는 아직 색인이 생성되어 있습니다. 따라서 현재 설계 증거는 루트를 사용합니다.
CodeGraph 히트 플러스 다이렉트 소스, README, GNO 및 공식 문서 검사.

## 외부 API 증거

- Spring Kafka 공식 문서 쇼 `KafkaTemplate.send(...)`는
  `CompletableFuture<SendResult<K, V>>`; 호출자는 비동기 완료를 처리할 수 있거나
  전송 성공 확인이 필요한 경우 `get()`으로 차단하세요.
- Spring Kafka 문서는 실패한 레코드를 데드 레터로 게시하는 데 실패한 문서도 문서화합니다.
  주제는 `DeadLetterPublishingRecoverer`이지만 이 워크샵 모듈은
  애플리케이션 소유 대체 상태를 숨기지 않고 예시에 표시
  내부 리스너 오류 처리.
- Redisson 공식 문서 쇼 `RStream`는 `add`, `readGroup`를 지원합니다.
  `pendingRange`, `fastClaim`, `ack`. 이것은 좋은 대체 버퍼입니다.
  후보이지만 첫 번째 호에 Redis Streams를 구현하면
  두 번째 내구성 있는 대기열 구현에 대한 예입니다.
- Exposed 공식 문서에서는 Spring 관리 `@Transactional` 서비스가
  모든 호출을 수동으로 래핑하지 않고 Exposed DSL 작업을 사용합니다.
  `transaction {}` 블록.

## 디자인 목표

- 일반 경로 발신함 삽입을 방지하여 핫 쓰기 트랜잭션을 줄입니다.
- 행복한 경로를 단순하게 유지하세요. 하나의 도메인 트랜잭션을 수행한 다음 직접 경계를 지정합니다. Kafka
  출판하다.
- 내구성 있는 폴백을 통해 실패한 게시를 관찰 및 복구 가능하게 유지
  행.
- 릴레이 재시도와 소비자가 멱등성을 가질 수 있도록 이벤트 ID를 안정적으로 유지하세요.
- 더 강력한 일관성 기준으로 클래식 트랜잭션 발신함을 그대로 유지합니다.
- 정직하게 절충 방법을 가르치십시오. 일반 경로 DB 작업을 낮추고 원자성을 약화시키십시오.
  reconciler/idempotency이 존재하지 않는 한.
- 제한된 로그, 안전한 데모 엔드포인트,
  Micrometer 카운터 및 테이블 상태 검사.

## 논골

- `messaging/transactional-outbox`를 바꾸지 마십시오.
- 이 패턴이 클래식 패턴과 동일한 원자 보장을 제공한다고 검증문하지 마세요.
  거래 발신함.
- Redis이 종속 항목으로 언급되었기 때문에 사용되지 않은 Redis 종속성을 추가하지 마세요.
  가능한 버퍼.
- 범용 이벤트 프레임워크를 구축하지 마십시오.
- bluetape4k 실행 프로그램이 있는 경우 원시 Testcontainers를 수동으로 롤링하지 마세요.
- Redis 스트림, Kafka 트랜잭션, 정확히 1회 전달을 구현하지 마세요.
  Issue #348의 총 주문 또는 생산 소비자 멱등성.
- 원시 이벤트 페이로드, 원시 예외 텍스트, 스택 추적,
  자격 증명, 비밀이 포함된 브로커 URL, 토큰 또는 데모 엔드포인트를 통한 키.

## 접근 옵션

### 옵션 A: 관계형 대체 게시 테이블

다음을 사용하여 `messaging/kafka-outbox-fallback`를 만듭니다.

- 도메인 상태에 대한 `orders` 테이블입니다.
- 실패한 직접 게시에 대한 `event_publications` 테이블입니다.
- `OrderService`은 트랜잭션에 `orders`만 저장합니다.
- `OrderEventPublisher`은 커밋 후 실행되며 최대 3개까지 Kafka에 게시됩니다.
  시도하고 실패한 후에만 `NOT_PUBLISHED`을 저장합니다.
- `PublicationRelay`은 `NOT_PUBLISHED` 및 재시도 가능한 `FAILED` 행을 폴링하고 다음으로 보냅니다.
  Kafka, 업데이트 `PUBLISHED`, `FAILED` 또는 `DEAD_LETTER`.
- `PublicationReconciler`은 이벤트 상태를 알 수 없는 주문을 식별하고
  결정적 이벤트 ID를 사용하여 대체 행을 재구성할 수 있습니다. 이것은
  의도적으로 이미 복제할 수 있는 손실 방지 안전망
  공개된 이벤트; 소비자는 이벤트 ID별로 중복을 제거해야 합니다.

장점:

- 사용자가 요청한 흐름에 가장 가깝습니다.
- 현재 Exposed/PostgreSQL/Kafka 워크숍 패턴을 재사용합니다.
- 테스트 및 README 다이어그램에서 폴백의 내구성과 검사 가능성을 유지합니다.
- 핵심 절충안이 명확해지기 전에 Redis 복잡성을 추가하지 마세요.

단점:

- 대체 쓰기는 여전히 실패 경로의 데이터베이스를 사용합니다.
- Kafka 실패와 대체 삽입 사이의 충돌에는 여전히 조정자가 필요합니다.
  적용 범위.

### 옵션 B: Redis 스트림 대체 버퍼

실패한 게시 버퍼로 Redis 스트림을 사용합니다.

- 직접 Kafka 게시는 여전히 좋은 방법입니다.
- 실패한 이벤트는 Redis 스트림에 추가됩니다.
- 스트림 소비자 그룹은 항목을 Kafka에 전달하고 그 후에 이를 승인합니다.
  성공.
- Pending/claim 논리는 소비자 충돌을 처리합니다.

장점:

- 폴백 시에도 DB 로드를 낮게 유지합니다.
- 사용자의 Redis 버퍼 아이디어를 직접적으로 보여줍니다.

단점:

- 첫 번째 구현에 두 번째 내구성 시스템을 추가합니다.
- Redis 실패 의미, 보류 중인 항목 처리, 스트림 그룹 설정이 필요합니다.
  PostgreSQL 및 Kafka 외에 Testcontainers Redis.
- 예는 Redis 아웃박스 트레이드오프만큼 스트리밍됩니다.

### 옵션 C: 더 적은 수의 열 또는 일괄 삽입이 포함된 기본 발신함

도메인 트랜잭션 내에서 보낼 편지함 행을 계속 작성하되 행 또는
일괄 처리 동작.

장점:

- 가장 강력한 보낼 편지함 의미 체계를 유지합니다.

단점:

- 주요 목표를 충족하지 못합니다. 일반 경로의 보낸 편지함 쓰기를 제거합니다.
  뜨거운 거래.

## 선택된 접근법

Issue #348에 대해 옵션 A를 선택합니다.

옵션 A는 요청된 트랜잭션-로드 균형을 직접 가르치면서 유지합니다.
워크숍 모듈에 맞게 구현이 충분히 작습니다. 옵션 B는 여전히
핵심 동작이 문서화되면 확장 지점과 좋은 미래 문제가 발생합니다.
검증되었습니다. 옵션 C는 일반 경로 보낸 편지함을 유지하므로 거부됩니다.
쓰기 때문에 사용자의 목표를 다루지 않습니다.

## 제안된 모듈

경로: `messaging/kafka-outbox-fallback`

Gradle 기존 포함 규칙을 통한 모듈 이름:
`:messaging-kafka-outbox-fallback`

기본 패키지:
`io.bluetape4k.workshop.messaging.fallback`

권장되는 종속성:

- `bluetape4k-core`, `bluetape4k-logging`, `bluetape4k-coroutines`
- Exposed core/JDBC/Spring 거래 지원
- Spring Boot 4 웹 MVC, 검증, 액츄에이터, 스프링 Kafka
- 봄 Kafka `KafkaTemplate`; `bluetape4k-kafka4`을 추가하지 마십시오.
  구현에서는 구체적인 API를 사용합니다.
- PostgreSQL 런타임 + Testcontainers PostgreSQL
- Kafka Testcontainers
- 잭슨 3
- `bluetape4k-junit5`, `bluetape4k-assertions`, MockK/springmockk

Redis 종속성은 첫 번째 구현에서 의도적으로 제외됩니다.
Redis 스트림 폴백은 Issue #348 범위가 아닌 후속 이슈 후보입니다.

## 부품 설계

### 테이블

`orders`

- `id`
- `customer_id`
- `product`
- `quantity`
- `status`
- `created_at`
- `updated_at`

`event_publications`

- `id`
- `event_id` 고유한 안정 ID
- `aggregate_type`
- `aggregate_id`
- `event_type`
- `payload`
- `status`: `NOT_PUBLISHED`, `PUBLISHED`, `FAILED`, `DEAD_LETTER`
- `direct_attempt_count`
- `relay_retry_count`
- `last_error_code`
- `last_error_summary`
- `next_attempt_at`
- `claimed_by`
- `claimed_until`
- `created_at`
- `published_at`
- `updated_at`

테이블에는 실패했거나 재구성된 발행물만 저장됩니다. 직접적인 Kafka 성공
출판 행을 생성하지 않습니다. 대체 행은 다음으로 시작합니다.
`relay_retry_count = 0` 직접 게시가 이미 세 가지 직접 게시를 모두 사용한 경우에도 마찬가지입니다.
시도. `event_id`은 결정적입니다.
`order-placed:{orderId}:v1`; `event_id`의 고유한 upsert가 대체 삽입을 만듭니다.
그리고 조정은 멱등적입니다.

`payload`은(는) 입력된 형식으로 직렬화된 닫힌 `OrderPlacedEvent` JSON 문서입니다.
DTO. Jackson 기본 입력 및 클래스 이름 다형성은 금지됩니다. 유효 탑재량
크기는 모듈 구성에 따라 제한됩니다. `last_error_summary`은 제한되어 있습니다.
텍스트를 삭제했으며 스택 추적, 직렬화된 예외 개체,
다음을 포함하는 원시 페이로드, 자격 증명, API 키, 토큰 또는 연결 URL
기미.

### 서비스

`PlaceOrderUseCase`

- bluetape4k `require*` 도우미를 사용하여 입력의 유효성을 검사합니다.
- 주문 배치를 위한 공개 발신자 계약을 소유합니다.
- 내부를 호출하는 비트랜잭션 오케스트레이터입니다.
  `TransactionalOrderWriter.saveOrder(...)` 방법. 작가가 소유권을 갖고 있다.
  `@Transactional` 경계를 설정하고 `orders`만 씁니다.
- 커밋 후 REST을 반환하기 전에 직접 Kafka 게시를 실행합니다.
  응답. 응답은 `publicationStatus`를 `PUBLISHED_DIRECT`로 보고합니다.
  `FALLBACK_STORED` 또는 `FALLBACK_STORE_FAILED`.
- 호출자가 호출할 수 있도록 하위 수준의 "순서만 저장" 메서드 internal/test-only를 유지합니다.
  publish/fallback 단계를 잊어서는 안 됩니다.

`OrderEventPublisher`

- 안정적인 `eventId`을 사용하여 `OrderPlaced` 이벤트를 빌드합니다.
- DB 커밋 후 Kafka에 게시합니다.
- 명시적인 경우 차단 `KafkaTemplate.send(...).get(timeout)`을 사용합니다.
  작업장 친화적인 확인 계약.
- 시도당 시간 초과 및 총 직접 시도를 통해 최대 3번의 직접 시도를 재시도합니다.
  예산을 공개합니다. 시간 초과는 게시 실패로 처리되며 대체를 트리거합니다.
- 최종 직접 실패 시 다음을 사용하여 `event_publications` 행을 생성하거나 업데이트합니다.
  `NOT_PUBLISHED`, `direct_attempt_count = 3`, `relay_retry_count = 0`.
- 대체 삽입이 실패하면 구조적 오류를 기록하고 측정항목을 증가시키며
  `FALLBACK_STORE_FAILED`을 반환합니다. 조정자는 복구 메커니즘입니다.
- v1에서는 일시 중지 API를 사용하지 않습니다. 향후 일시 중지 코드가 추가되면 다음을 수행해야 합니다.
  광범위한 예외 처리 전에 `CancellationException`을 다시 발생시킵니다.

`PublicationRelay`

- `NOT_PUBLISHED` 및 재시도 가능한 `FAILED` 행을 폴링합니다.
- 보내기 전에 행을 원자적으로 요청합니다. 구현에서는 다음을 사용할 수 있습니다.
  `SELECT ... FOR UPDATE SKIP LOCKED` 또는 낙관적 `UPDATE ... WHERE
  status IN (...) ANDclaim_until < now()` 패턴이지만 두 가지를 방지해야 합니다.
  스케줄러는 동일한 비터미널 행을 동시에 게시하지 않습니다.
- 제한된 배치 크기, 폴링 간격, `next_attempt_at`, `claimed_by` 및
  `claimed_until`. 오래된 청구는 TTL 청구 이후에 다시 시도할 수 있습니다.
- Kafka로 보낸 다음 `PUBLISHED`으로 표시합니다.
- `relay_retry_count`을 증가시키고 삭제된 오류 code/summary를 저장합니다.
  실패.
- 최대 재시도 후 `DEAD_LETTER`으로 이동합니다.
- 이미 터미널 행에 대해 멱등성이 있습니다.
- Kafka 전송이 성공한 후 프로세스가 충돌하는 경우 Kafka 이벤트가 중복될 수 있습니다.
  그러나 행이 표시되기 전에 `PUBLISHED`; 이는 최소 한 번 이상으로 문서화되어 있습니다.
  행동을 취하며 결정론적 `event_id` 소비자 중복 제거에 의존합니다.

`PublicationReconciler`

- 이 패턴에 대한 작동 안전망을 보여줍니다.
- 이벤트 게시가 다음과 같은 주문에 대한 대체 행을 재구성하거나 업데이트합니다.
  알려지지 않은.
- 일반 쓰기 트랜잭션을 보내는 동안 보낼 편지함 행이 없도록 유지합니다.
  예제에서는 잔여 충돌 기간이 명시적이고 복구 가능합니다.
- 이벤트가 게시되지 않았음을 증명하지 않습니다. 왜냐하면 직접적인 성공은
  게시 행이 없으며 조정은 고의적인 손실 방지입니다.
  중복 위험 트레이드 오프.

`PublicationQueryService`

- REST/README 검사를 위한 데모 안전 게시 상태를 제공합니다.
- 응답에서 원시 `payload` 및 원시 오류 텍스트를 제외합니다.
- `eventId`, `aggregateId`, `eventType`, `status`만 노출합니다.
  `directAttemptCount`, `relayRetryCount`, 타임스탬프 및 삭제된 오류
  category/summary.

### 검증 및 보안

- `customer_id` 및 `product`은 필수이고 길이 제한이 있으며 거부해야 합니다.
  제어 문자.
- `quantity`은(는) 긍정적이어야 하며 소규모 작업장 친화적인 최대값으로 제한되어야 합니다.
- 주제 이름과 이벤트 유형은 구성에서 고정된 허용 목록입니다.
  `order-events` 및 `OrderPlaced`.
- 데모 엔드포인트는 local/workshop 예시일 뿐입니다. 프로덕션 사용에는 다음이 필요합니다.
  인증, 권한 부여, 비율 제한, 감사 로깅 및 검토
  액츄에이터 노출.
- 액추에이터 노출은 최소화됩니다. 어떤 엔드포인트도 원시 페이로드를 유출할 수 없습니다.
  스택 추적, 자격 증명, 비밀, 토큰 또는 키가 포함된 브로커 URL.

### 관찰 가능성

필수 Micrometer 미터:

- `result` 태그가 붙은 `workshop.outbox.direct.publish.attempts`.
- `result` 태그가 붙은 `workshop.outbox.fallback.stored`.
- `status` 태그가 붙은 `workshop.outbox.relay.rows`.
- `result` 태그가 붙은 `workshop.outbox.reconciler.repairs`.
- `workshop.outbox.publication.lag` 최고령 `NOT_PUBLISHED`/`FAILED` 연령.

필수 구조화된 로그 이벤트:

- `order.event.direct-publish.failed`
- `order.event.fallback-store.failed`
- `order.event.relay.dead-lettered`
- `order.event.reconciler.repaired`

테스트에서는 최소한 직접적인 테스트를 위해 레지스트리 또는 엔드포인트 어설션을 사용해야 합니다.
게시 실패, 대체 저장소, 릴레이 success/failure 및 조정자 복구
측정항목.

### Kafka 주제 및 데드 레터

- 주요 주제: `order-events`, 이 워크샵 모듈 구성이 소유합니다.
- 소비자 멱등성 키: `eventId`.
- Issue #348은 테이블 상태 `DEAD_LETTER`만 사용합니다. 다음과 같은 Kafka DLQ 주제
  `order-events.dlq`은 v1 범위가 아닌 문서화된 확장 및 향후 문제입니다.

`orders` 및 `event_publications`은 이 내부의 모듈 로컬 테이블 이름입니다.
예. 의도적으로 분리되어 있습니다.
`messaging/transactional-outbox`; 구현 클래스를 공유하지 마십시오.
모듈.

### 성과 및 거래예산

일반적인 직접 성공 명령 수:

- 핫 트랜잭션: 하나의 주문 삽입, 게시 삽입 없음.
- 커밋 후: 한 번 Kafka 확인을 보냅니다.
- 대체 테이블: 행이 없습니다.

직접 실패 명령 수:

- 핫 트랜잭션: 하나의 주문 삽입, 게시 삽입 없음.
- 커밋 후: 최대 3번의 Kafka 전송 시도가 시간 초과되었습니다.
- 대체 경로: 멱등성 게시 upsert 하나.

릴레이 경로 명령 수:

- 하나의 제한된 행 청구 일괄 처리.
- 요청된 행당 하나의 Kafka 전송입니다.
- 청구된 행당 하나의 터미널 또는 재시도 업데이트입니다.

성공 기준은 엄격한 벤치마크가 아닌 기능과 비교입니다.
임계값: README는(는) 클래식 발신함에서 게시 의도를 기록함을 표시해야 합니다.
이 모듈은 해당 일반 경로 삽입을 제거하고
최소한 한 번은 조정 의무를 수락합니다. 지역 스트레스 도우미가 있다면
추가됨, p95 HTTP 응답 시간, p95 직접 게시 시간, 대체를 보고해야 함
글로벌 검증문보다는 매장 수, 릴레이 지연 및 중복 위험 메모
성능 우월성.

## 실패 모드

| 실패 | 예상되는 동작 |
|---|---|
| DB 거래 실패 | 주문도 없고 이벤트 게시도 없습니다. |
| DB 커밋 성공, Kafka 성공 | 주문이 존재합니다. 대체 행이 생성되지 않습니다. |
| DB 커밋 성공, Kafka 일시적 실패 후 성공 | 주문이 존재합니다. 대체 행이 생성되지 않습니다. |
| DB 커밋 성공, Kafka 3번 시도 후 실패 | 주문이 존재합니다. `event_publications.status = NOT_PUBLISHED`. |
| Kafka 전송이 중단되거나 시간 초과를 초과함 | 시간 초과는 게시 실패입니다. 3번의 제한된 시도 후에 대체 행이 업데이트됩니다. |
| Kafka 소진되어 대체 삽입 실패 | 구조적 오류와 측정항목이 방출됩니다. REST 보고 `FALLBACK_STORE_FAILED`; 조정자는 나중에 결정적 이벤트를 업데이트할 수 있습니다. |
| 릴레이 게시 성공 | 행이 `PUBLISHED`으로 이동합니다. |
| 릴레이 재시도 배기 | 행은 수동 검사를 위해 삭제된 마지막 오류 code/summary가 있는 `DEAD_LETTER`으로 이동합니다. |
| `PUBLISHED`을 표시하기 전에 Kafka 전송 후 릴레이가 충돌함 | 나중에 릴레이를 다시 시도하면 이벤트가 중복될 수 있습니다. 소비자는 `event_id`에 의해 중복 제거됩니다. |
| 대체 삽입 전 커밋 후 프로세스 충돌 | 조정자는 `orders`에서 대체 행을 삽입할 수 있습니다. README에서는 이것이 이미 게시된 이벤트와 중복될 수 있으며 기존 보낼 편지함보다 약하다고 설명합니다. |
| 릴레이와 조정자가 중복됨 | 고유한 `event_id` upsert 및 행 청구로 중복 대체 행이 방지됩니다. Kafka 중복된 위험은 최소한 한 번은 유지되고 문서화됩니다. |

## 테스트 전략

인증 패스당 하나의 Testcontainers 지원 Gradle 호출을 사용하세요.

필수 테스트:

- `placeOrder`은 Kafka 직접적인 성공 시 `orders`만 저장합니다.
- 직접 게시는 최대 3번까지 재시도하고 대체 행 없이 성공합니다.
- 직접 게시에 실패하면 `NOT_PUBLISHED` 대체 행이 생성됩니다.
- 직접 게시 시간 초과로 인해 제한된 후 `NOT_PUBLISHED` 대체 행이 생성됩니다.
  시도.
- 대체 삽입 오류가 관찰 가능하며 조정자가 복구할 수 있습니다.
- Relay는 대체 행을 게시하고 이를 `PUBLISHED`으로 표시합니다.
- 릴레이 실패는 재시도 횟수를 증가시키고 `DEAD_LETTER`으로 전환됩니다.
- 중복 릴레이 호출은 터미널 행에 대해 멱등성을 갖습니다.
- 동시 릴레이 호출은 동일한 비터미널 행을 두 번 요청할 수 없습니다.
- 조정자는 게시를 알 수 없는 주문에 대해 누락된 대체를 재구성합니다.
  상태 및 문서의 중복 방지 의미.
- REST 엔드포인트는 생성된 주문을 반환하고 안전한 게시 상태를 노출합니다.
  원시 페이로드, 스택 추적, 브로커 URL, 토큰, 키가 없는 데모 검사
  또는 자격 증명.
- 잘못된 customer/product/quantity 입력이 거부됩니다.
- Metrics/log-observability 경로는 최소한 구성 요소 수준에서 다루어집니다.

테스트 규칙:

- `bluetape4k-assertions`을 사용하세요. AssertJ/Kluent/JUnit 검증문을 도입하지 마세요
  API 새로운 테스트 중입니다.
- `PostgreSQLServer.Launcher.postgres` 및 `KafkaServer.Launcher.kafka`을 사용하세요.
- 실제 Kafka 중단이 발생하는 생산자 오류 경로에는 MockK/springmockk을 사용하세요.
  테스트를 느리거나 불안정하게 만듭니다.
- Testcontainers 테스트를 연속적으로 유지하세요.

## 문서화 및 다이어그램 범위

모듈 README 쌍:

- `messaging/kafka-outbox-fallback/README.md`
- `messaging/kafka-outbox-fallback/README.ko.md`

루트 README 쌍:

- 메시징 아래에 모듈 행을 추가합니다.
- 대상 테스트 명령을 추가합니다.
- English/Korean 패리티를 유지하세요.

README 영상:

- 아키텍처 다이어그램: 도메인 트랜잭션, 직접 Kafka 경로, 대체 테이블,
  중계자이자 화해자.
- 시퀀스 다이어그램: 행복한 경로 및 failure/fallback 경로.
- `event_publications`에 대한 선택적 상태 수명 주기 다이어그램.

다이어그램 요구 사항:

- 위키 아이콘 카탈로그의 공유 Kafka 및 데이터베이스 아이콘을 사용하세요.
- 직교하고 둥근 커넥터를 사용하고 대각선 카드 간 선을 피하십시오.
- 행 간격, 라벨 겹침, 엔드포인트, 기하학 및 PNG 증거를 보고합니다.
- `docs/images/readme-diagrams/`에서 SVG 및 PNG 자산을 모두 렌더링합니다.
- 예상 자산 기본 이름:
  `kafka-outbox-fallback-readme-architecture-01.svg`,
  `kafka-outbox-fallback-readme-sequence-01.svg`,
  `kafka-outbox-fallback-readme-state-01.svg`.

README 콘텐츠 요구 사항:

- 두 README 파일 모두에서 언어를 전환합니다.
- 기존 트랜잭션 발신함과 Kafka-첫 번째 대체에 대한 비교 표입니다.
- "보장되지 않음" 섹션: 고전적인 트랜잭션 원자성이 없음, 정확히 한 번
  배달, 총 주문, Kafka 거래, Redis 스트림 대체, 실제
  Kafka DLQ 또는 Issue #348의 프로덕션 소비자 멱등성 구현.
- 구체적인 `POST /api/orders` 예, 응답 필드, 안전한 게시 상태
  검사 예 및 "Kafka 아래로 -> 대체 행 -> 릴레이 게시"
  연습.
- 운영자 런북: boot/test 명령, 오류 삽입, SQL 쿼리
  중단된 행, retry/dead-letter 검사, 조정자 복구 경로, 스케줄러
  토글 및 예상되는 metric/log 증거.
- 마이그레이션 지침: 모든 변형이 발생하면 클래식 트랜잭션 발신함을 선택하세요.
  동일한 데이터베이스 트랜잭션에서 게시 의도를 지속적으로 기록해야 합니다.
  Kafka을 선택하세요. 낮은 핫 트랜잭션 로드가 가치가 있는 경우에만 첫 번째 폴백을 선택하세요.
  화해 및 중복 처리 의무. 두 예제를 나란히 유지
  옆; `messaging/transactional-outbox`를 마이그레이션하지 마십시오.

블로그 후속 조치:

- 워크숍 PR이(가) 검증된 후 `bluetape4k.github.io` 기사를 작성하세요.
  완성된 코드를 소스 진실로 사용합니다.
- 기존 트랜잭션 발신함과 Kafka-첫 번째 대체 발신함을 비교하세요.
- 명확한 절충점 유지: 일반 경로 트랜잭션 로드가 낮고 약함
  조정자 및 멱등성 이벤트 ID가 없는 원자성.

## 작업 흐름 및 CI 영향

- `settings.gradle.kts`은 다음을 통해 자동으로 모듈을 포함해야 합니다.
  `includeModules("messaging", false, true)`, 그러나 `./gradlew projects`은(는)
  증명해 보세요.
- `.github/workflows/Examples.yml` 경로 필터 업데이트:
  `messaging/kafka-outbox-fallback/**`.
- 순차 컨테이너에 `:messaging-kafka-outbox-fallback:test` 추가
  예시 레인 및 업데이트 결과 아티팩트 paths/summary 종속성.
- `scripts/smoke-validate.sh` 메시지 그룹을 다음으로 업데이트하세요.
  `:messaging-kafka-outbox-fallback:test`.
- 세 가지 예상 README 자산에 대한 다이어그램 유효성 검사 스크립트 업데이트
  기본 이름.
- 워크플로 YAML 변경 사항이 범위 내에 있으므로 `actionlint`을 실행합니다.

## 롤백 및 운영

- 직접 게시, 릴레이 스케줄러, 조정자 스케줄러를 구성할 수 있어야 합니다.
  따라서 운영자는 디버깅 중에 각 경로를 비활성화할 수 있습니다.
- 예제에서의 롤백은 relay/reconciler 비활성화, 드레이닝 또는
  `NOT_PUBLISHED`/`FAILED`/`DEAD_LETTER` 행 검사, 모듈 제거
  CI/smoke 등록에서 행이 없는 후에만 데모 테이블 삭제
  더 이상 필요합니다.
- `DEAD_LETTER` 행은 Issue #348의 수동 검사 기록입니다. 재운전은
  명시적인 방법을 통해 행을 `NOT_PUBLISHED`으로 재설정해야만 허용됩니다.
  README Runbook에 표시된 demo/admin 메서드 또는 SQL입니다.
- 테이블 정리는 수동으로 이루어지며 문서화됩니다. v1에서는 자동 제거가 실행되지 않습니다.

## 수락 기준

- Issue #348 메타데이터는 `debop`, 마일스톤 `1.3.1`에 할당된 상태로 유지됩니다.
- 새 모듈 `:messaging-kafka-outbox-fallback`이(가) 존재하며 다음에서 검색할 수 있습니다.
  Gradle.
- 직접 Kafka 성공은 대체 게시 행을 쓰지 않습니다.
- 3번의 시도 후 Kafka 쓰기가 `NOT_PUBLISHED`에 직접 실패했습니다.
- 직접 Kafka 시간 초과 및 대체 삽입 실패 동작은 제한되어 있습니다.
  관찰 가능하고 테스트되었습니다.
- Relay는 대체 행을 게시하고 `PUBLISHED`, `FAILED` 및 기록을 기록합니다.
  `DEAD_LETTER`의 내용이 정확합니다.
- 릴레이 행 청구는 동시 스케줄러 틱이 동일한 것을 청구하는 것을 방지합니다.
  비터미널 행.
- 조정자는 충돌 창이 작동적으로 복구되는 방법을 보여줍니다.
  중복 위험 및 결정적 이벤트 ID 중복 제거를 문서화합니다.
- 안전한 게시 상태 엔드포인트는 원시 페이로드, 스택 추적,
  연결 문자열, 토큰, 키, 자격 증명 또는 원시 예외 개체.
- README.md 및 README.ko.md는 장단점을 설명하고 클래식과 비교합니다.
  거래 발신함.
- README 다이어그램은 SVG + PNG로 배송되며 다이어그램 레이아웃 증거 게이트를 통과합니다.
- CI/smoke 스크립트에는 모듈이 포함되어 있습니다.
- 필수 확인 명령이 전달됩니다.
  `./gradlew projects`,
  `./gradlew :messaging-kafka-outbox-fallback:test --max-workers=1`,
  경고가 포함된 타겟 컴파일,
  README/diagram 검증자,
  `actionlint`,
  그리고 `git diff --check`.

## 종결된 결정

- Issue #348은 테이블 `DEAD_LETTER` 상태만 사용합니다. 실제 Kafka DLQ 출판은
  후속 호에서 명시적으로 추가하지 않는 한 후속 조치가 필요합니다.
- Redis 스트림 폴백은 후속 이슈 후보입니다. 이 모듈은
  Kafka-first/outbox-fallback 절충.
- 직접 게시 경로는 명시적인 Spring Kafka 차단 확인을 사용합니다.
  `bluetape4k-kafka4` 코루틴 도우미가 아닌 시간 초과 및 총 재시도 예산입니다.
- 공개 API은(는) 단일 `PlaceOrderUseCase.placeOrder(...)` 경계입니다.
  트랜잭션, 커밋 후 게시, 재시도 및 대체 지속성을 소유합니다.
