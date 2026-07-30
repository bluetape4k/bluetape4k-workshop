#!/usr/bin/env node

import { writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const baseline = '39616246e2f03a3b332bbcc40bc04cd7e03c9dad';
const source = '2026-07-30-kafka-outbox-fallback-visual-companion-design.md';
const outputs = {
  ko: 'docs/superpowers/specs/2026-07-30-kafka-outbox-fallback-visual-companion.html',
  en: 'docs/superpowers/specs/2026-07-30-kafka-outbox-fallback-visual-companion.en.html',
};

const approaches = {
  transactional: {
    orderWrites: 2,
    kafkaWaitInRequest: false,
    publicationRows: 'ALL',
    reconciliation: false,
    deduplication: 'RECOMMENDED',
  },
  kafkaFirst: {
    orderWrites: 1,
    kafkaWaitInRequest: true,
    publicationRows: 'FAILURES_ONLY',
    reconciliation: true,
    deduplication: 'REQUIRED',
  },
};

const scenarios = {
  DIRECT_SUCCESS: {
    events: [
      ['ORDER_STORED', 'persistence', { orderRows: 1 }],
      ['TRANSACTION_COMMITTED', 'application', {}],
      ['KAFKA_ATTEMPT_1', 'messaging', { directAttempts: 1 }],
      ['KAFKA_CONFIRMED', 'messaging', { kafkaEvents: 1 }],
      ['API_PUBLISHED_DIRECT', 'api', { apiStatus: 'PUBLISHED_DIRECT' }],
    ],
    final: {
      apiStatus: 'PUBLISHED_DIRECT',
      orderRows: 1,
      publicationRows: 0,
      kafkaEvents: 1,
      directAttempts: 1,
      relayRetries: 0,
      publicationStatus: 'NO ROW',
      claimOwner: null,
    },
  },
  DIRECT_FAILURE: {
    events: [
      ['ORDER_STORED', 'persistence', { orderRows: 1 }],
      ['TRANSACTION_COMMITTED', 'application', {}],
      ['KAFKA_ATTEMPT_1_FAILED', 'messaging', { directAttempts: 1 }],
      ['KAFKA_ATTEMPT_2_FAILED', 'messaging', { directAttempts: 2 }],
      ['KAFKA_ATTEMPT_3_FAILED', 'messaging', { directAttempts: 3 }],
      ['FALLBACK_NOT_PUBLISHED', 'persistence', { publicationRows: 1, publicationStatus: 'NOT_PUBLISHED' }],
      ['API_FALLBACK_STORED', 'api', { apiStatus: 'FALLBACK_STORED' }],
    ],
    final: {
      apiStatus: 'FALLBACK_STORED',
      orderRows: 1,
      publicationRows: 1,
      kafkaEvents: 0,
      directAttempts: 3,
      relayRetries: 0,
      publicationStatus: 'NOT_PUBLISHED',
      claimOwner: null,
    },
  },
  DIRECT_TIMEOUT: {
    events: [
      ['ORDER_STORED', 'persistence', { orderRows: 1 }],
      ['TRANSACTION_COMMITTED', 'application', {}],
      ['KAFKA_TIMEOUT_1', 'messaging', { directAttempts: 1, kafkaEvents: '?' }],
      ['KAFKA_TIMEOUT_2', 'messaging', { directAttempts: 2 }],
      ['KAFKA_TIMEOUT_3', 'messaging', { directAttempts: 3 }],
      ['FALLBACK_SAME_EVENT_ID', 'persistence', { publicationRows: 1, publicationStatus: 'NOT_PUBLISHED' }],
      ['API_FALLBACK_STORED', 'api', { apiStatus: 'FALLBACK_STORED' }],
    ],
    final: {
      apiStatus: 'FALLBACK_STORED',
      orderRows: 1,
      publicationRows: 1,
      kafkaEvents: '?',
      directAttempts: 3,
      relayRetries: 0,
      publicationStatus: 'NOT_PUBLISHED',
      claimOwner: null,
    },
  },
  RELAY_RECOVERY: {
    events: [
      ['FALLBACK_READY', 'persistence', { orderRows: 1, publicationRows: 1, directAttempts: 3, publicationStatus: 'NOT_PUBLISHED', apiStatus: 'FALLBACK_STORED' }],
      ['RELAY_CLAIM_RACE', 'recovery', { publicationStatus: 'CLAIMED', claimOwner: 'relay-a' }],
      ['RELAY_KAFKA_SEND', 'messaging', {}],
      ['RELAY_KAFKA_CONFIRMED', 'messaging', { kafkaEvents: 1 }],
      ['MARK_PUBLISHED', 'persistence', { publicationStatus: 'PUBLISHED', claimOwner: null }],
    ],
    final: {
      apiStatus: 'FALLBACK_STORED',
      orderRows: 1,
      publicationRows: 1,
      kafkaEvents: 1,
      directAttempts: 3,
      relayRetries: 0,
      publicationStatus: 'PUBLISHED',
      claimOwner: null,
    },
  },
  RELAY_DEAD_LETTER: {
    events: [
      ['FALLBACK_READY', 'persistence', { orderRows: 1, publicationRows: 1, directAttempts: 3, publicationStatus: 'NOT_PUBLISHED', apiStatus: 'FALLBACK_STORED' }],
      ['RELAY_CLAIM_1', 'recovery', { publicationStatus: 'CLAIMED', claimOwner: 'relay-a' }],
      ['RELAY_FAILURE_1', 'messaging', { publicationStatus: 'FAILED', relayRetries: 1, claimOwner: null }],
      ['RELAY_CLAIM_2', 'recovery', { publicationStatus: 'CLAIMED', claimOwner: 'relay-b' }],
      ['RELAY_FAILURE_2', 'messaging', { publicationStatus: 'FAILED', relayRetries: 2, claimOwner: null }],
      ['RELAY_CLAIM_3', 'recovery', { publicationStatus: 'CLAIMED', claimOwner: 'relay-c' }],
      ['RELAY_DEAD_LETTERED', 'persistence', { publicationStatus: 'DEAD_LETTER', relayRetries: 3, claimOwner: null }],
    ],
    final: {
      apiStatus: 'FALLBACK_STORED',
      orderRows: 1,
      publicationRows: 1,
      kafkaEvents: 0,
      directAttempts: 3,
      relayRetries: 3,
      publicationStatus: 'DEAD_LETTER',
      claimOwner: null,
    },
  },
  FALLBACK_STORE_FAILURE: {
    events: [
      ['ORDER_STORED', 'persistence', { orderRows: 1 }],
      ['TRANSACTION_COMMITTED', 'application', {}],
      ['DIRECT_ATTEMPTS_FAILED', 'messaging', { directAttempts: 3 }],
      ['FALLBACK_INSERT_FAILED', 'persistence', { apiStatus: 'FALLBACK_STORE_FAILED' }],
      ['GRACE_PERIOD_ELAPSED', 'recovery', {}],
      ['RECONCILER_ANTI_JOIN', 'persistence', {}],
      ['RECONSTRUCT_EVENT', 'recovery', {}],
      ['UPSERT_RECONSTRUCTED', 'persistence', { publicationRows: 1, publicationStatus: 'NOT_PUBLISHED' }],
    ],
    final: {
      apiStatus: 'FALLBACK_STORE_FAILED',
      orderRows: 1,
      publicationRows: 1,
      kafkaEvents: 0,
      directAttempts: 3,
      relayRetries: 0,
      publicationStatus: 'NOT_PUBLISHED',
      claimOwner: null,
    },
  },
};

const expectedFinals = {
  DIRECT_SUCCESS: ['PUBLISHED_DIRECT', 1, 0, 1, 1, 0, 'NO ROW'],
  DIRECT_FAILURE: ['FALLBACK_STORED', 1, 1, 0, 3, 0, 'NOT_PUBLISHED'],
  DIRECT_TIMEOUT: ['FALLBACK_STORED', 1, 1, '?', 3, 0, 'NOT_PUBLISHED'],
  RELAY_RECOVERY: ['FALLBACK_STORED', 1, 1, 1, 3, 0, 'PUBLISHED'],
  RELAY_DEAD_LETTER: ['FALLBACK_STORED', 1, 1, 0, 3, 3, 'DEAD_LETTER'],
  FALLBACK_STORE_FAILURE: ['FALLBACK_STORE_FAILED', 1, 1, 0, 3, 0, 'NOT_PUBLISHED'],
};

const publicationStates = {
  NO_ROW: ['NOT_PUBLISHED'],
  NOT_PUBLISHED: ['CLAIMED'],
  CLAIMED: ['PUBLISHED', 'FAILED', 'DEAD_LETTER'],
  FAILED: ['CLAIMED'],
  PUBLISHED: [],
  DEAD_LETTER: [],
};

const architectureNodes = [
  ['OrderController', 'api'],
  ['PlaceOrderUseCase', 'application'],
  ['TransactionalOrderWriter', 'persistence'],
  ['PostgreSQL.orders', 'persistence'],
  ['OrderEventPublisher', 'messaging'],
  ['Kafka.order-events', 'messaging'],
  ['EventPublicationRepository', 'persistence'],
  ['PostgreSQL.event_publications', 'persistence'],
  ['EventPublicationRelay', 'recovery'],
  ['PublicationReconciler', 'recovery'],
];

const classes = [
  ['OrderController', 'api', ['placeOrder(request): ResponseEntity<OrderResponse>']],
  ['PlaceOrderUseCase', 'application', ['placeOrder(request): OrderResponse']],
  ['TransactionalOrderWriter', 'persistence', ['saveOrder(...): OrderRecord', 'getOrder(orderId): OrderRecord']],
  ['OrderEventPublisher', 'messaging', ['publishDirectOrFallback(event): OrderPublicationStatus']],
  ['EventPublicationRepository', 'persistence', ['upsertNotPublished(...)', 'claimNextBatch(...)', 'markPublished(...)', 'markRelayFailure(...)', 'findOrdersWithoutPublicationsCreatedOnOrBefore(...)']],
  ['EventPublicationRelay', 'recovery', ['scheduledRelay()', 'relayOnce(): RelayResult']],
  ['PublicationReconciler', 'recovery', ['scheduledReconcile()', 'reconcileOnce(): ReconcileResult']],
  ['PublicationQueryService', 'application', ['findAll(): List<PublicationResponse>']],
  ['OutboxMetrics', 'recovery', ['recordDirectPublish(result)', 'recordFallbackStored(result)', 'recordRelay(result)', 'recordReconciler(result)']],
];

const sourceTests = [
  ['transactionOnly', 'transactional writer stores only order row'],
  ['directSuccess', 'placeOrder stores only order row and returns PUBLISHED_DIRECT when direct Kafka publish succeeds'],
  ['directFailure', 'direct publish retries three times then stores NOT_PUBLISHED fallback row'],
  ['timeout', 'direct publish timeout stores NOT_PUBLISHED fallback row'],
  ['fallbackFailure', 'fallback insert failure returns FALLBACK_STORE_FAILED and records safe metric and log'],
  ['relaySuccess', 'relay publishes fallback row and marks it PUBLISHED'],
  ['deadLetter', 'relay failure increments retry and moves to DEAD_LETTER'],
  ['claimRace', 'concurrent relay calls cannot claim the same row twice'],
  ['claimTtl', 'stale relay claim becomes eligible after claim ttl'],
  ['sqlEligibility', 'claimNextBatch applies SQL eligibility ordering and limit'],
  ['reconcile', 'reconciler reconstructs deterministic fallback row and documents duplicate risk'],
  ['antiJoin', 'reconciler uses SQL cutoff and anti join for missing publications'],
  ['safeQuery', 'publication endpoint never exposes raw payload or raw exception text'],
  ['metrics', 'metrics record direct failure fallback relay and reconciler outcomes'],
];

const locales = {
  ko: {
    lang: 'ko',
    title: '주문 트랜잭션의 Outbox 쓰기를 줄이면서 Kafka 장애 시 이벤트 발행을 복구한다',
    description: '기존 Transactional Outbox와 Kafka-first Fallback을 비교하고, 직접 발행 실패부터 relay와 reconciliation까지의 복구 흐름을 실행합니다.',
    alternate: 'English',
    alternateHref: './2026-07-30-kafka-outbox-fallback-visual-companion.en.html',
    themeLabel: '테마 전환',
    repoLabel: '저장소',
    nav: [
      ['설계 비교', 'comparison'],
      ['시스템 구조', 'architecture'],
      ['장애 복구', 'simulation'],
      ['실제 구현', 'implementation'],
      ['실행', 'run'],
    ],
    kicker: 'Kafka Outbox Fallback · Visual Companion',
    heroLead: '정상 처리에서는 orders만 저장하고 Kafka로 직접 발행합니다. Kafka 발행에 실패하면 event_publications에 재발행 대상을 저장하고, 이 저장까지 실패하면 주문을 기준으로 발행 정보를 재구성합니다.',
    heroFacts: [
      ['1건', '정상 주문의 DB 입력'],
      ['3회', 'Kafka 직접 발행 시도'],
      ['6개', '장애·복구 시나리오'],
    ],
    problemTitle: '모든 주문에 Outbox row를 저장하지 않으려면 복구 책임이 추가된다',
    problemLead: '기존 Transactional Outbox는 주문과 발행 정보를 같은 트랜잭션에 저장합니다. Kafka-first Fallback은 정상 경로의 DB 입력을 줄이지만, 타임아웃과 fallback 저장 실패를 별도로 복구해야 합니다.',
    problemCards: [
      ['정상 처리 비용', '모든 주문에 Outbox row와 인덱스를 갱신하면 주문 트랜잭션의 DB 쓰기가 증가합니다.', 'persistence'],
      ['불확실한 타임아웃', '응답이 지연됐을 뿐 Kafka가 이벤트를 저장했을 수 있습니다. 실패로 단정할 수 없습니다.', 'messaging'],
      ['두 번째 저장 실패', 'Kafka 발행과 event_publications 입력이 모두 실패하면 주문만 남습니다.', 'recovery'],
      ['중복 발행 가능성', 'relay 또는 reconciler가 이미 전달된 eventId를 다시 발행할 수 있습니다.', 'application'],
    ],
    brainstormTitle: '구상 단계: 정상 처리 비용과 장애 복구 위험을 분리해 판단한다',
    rejectedLabel: '검토 후 제외',
    selectedLabel: '채택',
    decisions: [
      ['Redis Streams를 대체 저장소로 사용', '첫 예제에 PostgreSQL, Kafka와 별도의 내구성 시스템을 추가해 핵심 절충안이 흐려집니다.', false],
      ['정상 경로의 Outbox row를 유지', '가장 강한 저장 보장을 유지하지만 정상 주문의 DB 입력을 줄이려는 목표를 충족하지 못합니다.', false],
      ['실패한 발행 정보만 PostgreSQL에 저장', '현재 Exposed·PostgreSQL 패턴을 재사용하면서 정상 처리와 복구 비용을 분리할 수 있습니다.', true],
      ['고정된 eventId와 consumer 중복 처리 방지', '타임아웃과 reconciliation에서 발생할 수 있는 중복 발행을 처리합니다.', true],
    ],
    comparisonTitle: '기존 Transactional Outbox와 Kafka-first Fallback은 정상 처리 경로가 다르다',
    comparisonLead: '방식을 전환하면 DB 입력, Kafka 대기, 복구 기준이 함께 바뀝니다. DB 입력 수는 벤치마크가 아니라 현재 구현의 구조적 차이입니다.',
    approachNames: { transactional: 'Transactional Outbox', kafkaFirst: 'Kafka-first Fallback' },
    metrics: {
      orderWrites: '정상 주문의 DB 입력',
      kafkaWait: 'API 처리 중 Kafka 응답 대기',
      publicationRows: '발행 정보 저장',
      reconciliation: '누락 정보 재구성',
      deduplication: 'consumer 중복 처리 방지',
      yes: '필요',
      no: '없음',
      all: '모든 이벤트',
      failures: '장애·복구 대상만',
      recommended: '권장',
      required: '필수',
    },
    approachSummaries: {
      transactional: 'orders와 Outbox row를 같은 트랜잭션에 저장하고 relay가 비동기로 Kafka에 발행합니다.',
      kafkaFirst: 'orders를 커밋한 뒤 Kafka로 직접 발행하고, 실패한 경우에만 event_publications에 저장합니다.',
    },
    architectureTitle: '주문 저장, 직접 발행, 재발행, 발행 정보 재구성을 서로 다른 구성 요소가 담당한다',
    architectureLead: 'Architecture Diagram은 시간 순서가 아니라 책임과 데이터 접근 관계를 보여줍니다. 단계별 시간 순서는 아래 장애 복구 시뮬레이션에서 확인합니다.',
    layerNames: {
      api: 'API 계층',
      application: '애플리케이션 계층',
      persistence: '저장 계층',
      messaging: '메시징 계층',
      recovery: '복구·운영 계층',
    },
    nodeRoles: {
      OrderController: '주문 API',
      PlaceOrderUseCase: '저장 후 발행 조정',
      TransactionalOrderWriter: 'orders 트랜잭션',
      'PostgreSQL.orders': '주문 데이터',
      OrderEventPublisher: 'Kafka 직접 발행과 fallback',
      'Kafka.order-events': 'OrderPlaced 이벤트',
      EventPublicationRepository: '재발행 정보와 claim',
      'PostgreSQL.event_publications': '장애·복구 대상',
      EventPublicationRelay: 'claim 후 Kafka 재발행',
      PublicationReconciler: '누락 발행 정보 재구성',
    },
    architectureFlows: [
      ['OrderController → PlaceOrderUseCase', '주문 요청'],
      ['PlaceOrderUseCase → TransactionalOrderWriter → orders', '주문 트랜잭션'],
      ['PlaceOrderUseCase → OrderEventPublisher → Kafka', '커밋 후 직접 발행'],
      ['OrderEventPublisher → EventPublicationRepository', '발행 실패 시 저장'],
      ['EventPublicationRelay → event_publications → Kafka', 'claim 후 재발행'],
      ['PublicationReconciler → orders → event_publications', '누락 정보 재구성'],
    ],
    simulationTitle: '장애 조건을 선택해 DB, Kafka, API 상태가 바뀌는 과정을 확인한다',
    simulationLead: '단계를 진행하면 현재 실행 계층과 누적 상태가 함께 변경됩니다. 타임아웃의 Kafka 이벤트 수는 0이 아니라 확인 불가로 표시합니다.',
    scenarioNames: {
      DIRECT_SUCCESS: '정상 직접 발행',
      DIRECT_FAILURE: 'Kafka 직접 발행 실패',
      DIRECT_TIMEOUT: 'Kafka 발행 타임아웃',
      RELAY_RECOVERY: 'Relay 재발행 성공',
      RELAY_DEAD_LETTER: 'Relay 반복 실패',
      FALLBACK_STORE_FAILURE: 'Fallback 저장 실패와 복구',
    },
    scenarioReasons: {
      DIRECT_SUCCESS: '정상 처리에서는 event_publications row를 만들지 않습니다.',
      DIRECT_FAILURE: '세 번의 직접 발행 실패 후 재발행 정보를 저장합니다.',
      DIRECT_TIMEOUT: 'Kafka 수신 여부를 확정할 수 없는 상태에서 같은 eventId를 보존합니다.',
      RELAY_RECOVERY: '한 작업자가 row를 claim하고 Kafka 재발행 결과를 PUBLISHED로 기록합니다.',
      RELAY_DEAD_LETTER: '세 번째 relay 실패는 자동 재시도 대신 운영자 확인 상태로 전환됩니다.',
      FALLBACK_STORE_FAILURE: '주문만 남은 상태를 reconciler가 SQL anti-join으로 찾아 재구성합니다.',
    },
    eventDetails: {
      ORDER_STORED: 'TransactionalOrderWriter가 orders row만 저장합니다.',
      TRANSACTION_COMMITTED: '주문 트랜잭션이 커밋됩니다. Kafka 발행은 이 경계 밖에서 실행합니다.',
      KAFKA_ATTEMPT_1: 'OrderEventPublisher가 eventId를 record key로 Kafka에 발행합니다.',
      KAFKA_CONFIRMED: 'Kafka 발행 결과가 제한 시간 안에 확인됩니다.',
      API_PUBLISHED_DIRECT: 'API가 PUBLISHED_DIRECT를 반환하고 fallback row는 만들지 않습니다.',
      KAFKA_ATTEMPT_1_FAILED: '첫 번째 Kafka 발행이 실패합니다.',
      KAFKA_ATTEMPT_2_FAILED: '같은 eventId로 두 번째 직접 발행을 시도하지만 실패합니다.',
      KAFKA_ATTEMPT_3_FAILED: '세 번째 직접 발행도 실패해 재발행 정보 저장으로 전환합니다.',
      FALLBACK_NOT_PUBLISHED: 'event_publications에 NOT_PUBLISHED row를 upsert합니다.',
      API_FALLBACK_STORED: 'API가 FALLBACK_STORED를 반환합니다.',
      KAFKA_TIMEOUT_1: '첫 시도의 응답이 타임아웃됩니다. Kafka 수신 여부는 확인할 수 없습니다.',
      KAFKA_TIMEOUT_2: '같은 eventId로 다시 시도하지만 응답을 확인하지 못합니다.',
      KAFKA_TIMEOUT_3: '세 번째 타임아웃 후 fallback 경로로 전환합니다.',
      FALLBACK_SAME_EVENT_ID: 'order-placed:{orderId}:v1 eventId를 유지한 채 NOT_PUBLISHED row를 저장합니다.',
      FALLBACK_READY: '직접 발행 실패로 만들어진 NOT_PUBLISHED row가 relay 대상입니다.',
      RELAY_CLAIM_RACE: 'relay-a만 row를 claim하고 relay-b는 빈 결과를 받습니다.',
      RELAY_KAFKA_SEND: 'claim을 가진 작업자가 저장된 페이로드를 Kafka에 전송합니다.',
      RELAY_KAFKA_CONFIRMED: 'Kafka가 재발행 결과를 확인합니다.',
      MARK_PUBLISHED: 'claim 소유자를 확인한 뒤 PUBLISHED와 publishedAt을 기록합니다.',
      RELAY_CLAIM_1: 'relay-a가 첫 번째 재발행을 위해 row를 claim합니다.',
      RELAY_FAILURE_1: '첫 실패 후 relayRetryCount=1, status=FAILED로 변경합니다.',
      RELAY_CLAIM_2: '다음 처리 시각이 지난 뒤 relay-b가 다시 claim합니다.',
      RELAY_FAILURE_2: '두 번째 실패 후 relayRetryCount=2를 기록합니다.',
      RELAY_CLAIM_3: 'relay-c가 마지막 허용 재시도를 시작합니다.',
      RELAY_DEAD_LETTERED: '세 번째 실패로 DEAD_LETTER에 전환하고 자동 처리를 중단합니다.',
      DIRECT_ATTEMPTS_FAILED: 'Kafka 직접 발행 세 번이 모두 실패합니다.',
      FALLBACK_INSERT_FAILED: 'event_publications 입력도 실패해 API가 FALLBACK_STORE_FAILED를 반환합니다.',
      GRACE_PERIOD_ELAPSED: 'reconcilerGrace가 지나 복구 조회 대상이 됩니다.',
      RECONCILER_ANTI_JOIN: 'SQL cutoff와 anti-join으로 발행 정보가 없는 주문을 찾습니다.',
      RECONSTRUCT_EVENT: 'orders에서 OrderPlacedEvent와 고정된 eventId를 재구성합니다.',
      UPSERT_RECONSTRUCTED: 'NOT_PUBLISHED row를 upsert해 relay 대상에 포함합니다.',
    },
    controls: {
      previous: '이전 단계',
      next: '다음 단계',
      reset: '처음부터',
      play: '재생',
      pause: '일시 정지',
      step: '현재 단계',
      layer: '실행 계층',
    },
    metricLabels: {
      apiStatus: 'API 결과',
      orderRows: 'orders',
      publicationRows: 'event_publications',
      kafkaEvents: 'Kafka 이벤트',
      directAttempts: '직접 발행 시도',
      relayRetries: 'relay 재시도',
      publicationStatus: '발행 상태',
      claimOwner: 'claim 소유자',
      unknown: '확인 불가',
      none: '없음',
    },
    claimNote: 'Claim은 같은 시점에 두 작업자가 같은 row를 처리하는 상황을 방지합니다. Kafka 발행 확인 후 DB 상태 변경 전에 프로세스가 중단되면 중복 발행은 여전히 가능합니다.',
    lifecycleTitle: '발행 상태와 claim 처리 상태를 구분한다',
    lifecycleLead: '상태를 선택하면 진입 조건과 다음 상태를 확인할 수 있습니다. CLAIMED는 EventPublicationStatus enum 값이 아닙니다.',
    stateDescriptions: {
      NO_ROW: ['Kafka 직접 발행에 성공했거나 아직 누락 정보를 복구하지 않은 상태', 'Kafka 실패, 직접 발행 비활성화, reconciliation'],
      NOT_PUBLISHED: ['relay가 처리해야 하는 영속적인 발행 정보', 'relay claim'],
      CLAIMED: ['claimedBy와 claimedUntil이 유효한 처리 상태', 'PUBLISHED, FAILED, DEAD_LETTER'],
      FAILED: ['relay 실패 후 재시도 한도에 도달하지 않은 상태', 'claim 만료 또는 nextAttemptAt 경과 후 재처리'],
      PUBLISHED: ['Kafka 발행 확인과 publishedAt 기록이 끝난 상태', '종료'],
      DEAD_LETTER: ['재시도 한도에 도달해 운영자 확인이 필요한 상태', '운영자 판단'],
    },
    stateLabels: ['의미', '진입 또는 다음 처리'],
    classesTitle: '실제 클래스가 시뮬레이션의 각 단계를 구현한다',
    classesLead: 'Class Diagram은 시나리오와 직접 연결되는 public 메서드와 사용 관계만 표시합니다.',
    implementationTitle: 'Exposed는 주문 트랜잭션과 재발행 정보 처리를 분리한다',
    implementationLead: 'orders 입력, eventId upsert, claim, SQL anti-join이 서로 다른 메서드에서 실행됩니다.',
    implementationItems: [
      ['주문 트랜잭션', 'TransactionalOrderWriter.saveOrder()는 orders만 변경합니다. Kafka와 event_publications는 커밋 이후에 처리합니다.'],
      ['고정된 eventId', 'OrderPlacedEvent.from(order)는 order-placed:{orderId}:v1을 사용합니다. 고유 event_id upsert가 재시도와 재구성을 단일 row로 모읍니다.'],
      ['SQL claim', 'claimNextBatch()가 상태, nextAttemptAt, claim 만료, 정렬, batch limit을 DB에서 처리합니다.'],
      ['누락 정보 조회', 'findOrdersWithoutPublicationsCreatedOnOrBefore()가 cutoff와 anti-join으로 발행 정보가 없는 주문을 찾습니다.'],
    ],
    runTitle: '관련 서비스를 준비하고 정상 경로와 복구 경로를 확인한다',
    runLead: '애플리케이션은 PostgreSQL과 Kafka를 사용합니다. Demo admin endpoint는 기본적으로 비활성화되어 있습니다.',
    runTabs: ['관련 서비스', '애플리케이션', 'API', 'Relay / Reconcile', '테스트'],
    runCommands: [
      `docker run --rm --name kafka-outbox-postgres \\
  -e POSTGRES_DB=workshop \\
  -e POSTGRES_USER=postgres \\
  -e POSTGRES_PASSWORD=postgres \\
  -p 5432:5432 postgres:18-alpine

# Kafka는 localhost:9092에서 접근할 수 있도록 실행합니다.`,
      `./gradlew :messaging-kafka-outbox-fallback:bootRun`,
      `curl -s -X POST http://localhost:8080/api/orders \\
  -H 'Content-Type: application/json' \\
  -d '{"customerId":"customer-1001","product":"coffee-beans","quantity":2}'

curl -s http://localhost:8080/api/orders
curl -s http://localhost:8080/api/publications`,
      `# application.yml
workshop:
  kafka-outbox-fallback:
    demo-admin-endpoints-enabled: true

curl -s -X POST http://localhost:8080/api/publications/relay
curl -s -X POST http://localhost:8080/api/publications/reconcile`,
      `./gradlew :messaging-kafka-outbox-fallback:test --max-workers=1

./gradlew :messaging-kafka-outbox-fallback:test \\
  --tests 'io.bluetape4k.workshop.messaging.fallback.KafkaOutboxFallbackFlowTest' \\
  --max-workers=1`,
    ],
    runNotes: [
      'PostgreSQL과 Kafka를 먼저 실행합니다. Kafka 실행 방식은 사용하는 배포 환경에 맞춰 localhost:9092를 제공해야 합니다.',
      '기본 설정은 jdbc:postgresql://localhost:5432/workshop과 localhost:9092를 사용합니다.',
      '정상 응답은 publicationStatus=PUBLISHED_DIRECT이며 event_publications는 비어 있습니다.',
      'Demo admin endpoint는 실습용입니다. 운영 환경에는 인증, 권한, 요청 제한 없이 노출하지 않습니다.',
      '테스트는 Testcontainers launcher가 PostgreSQL과 Kafka를 시작하며 KafkaTemplate은 경로별 결과를 제어합니다.',
    ],
    testsTitle: '테스트가 정상 처리, 장애 복구, 동시 claim과 정보 노출 방지를 검증한다',
    testDescriptions: {
      transactionOnly: '주문 트랜잭션이 orders만 변경',
      directSuccess: '직접 발행 성공과 fallback row 부재',
      directFailure: '세 번 실패 후 NOT_PUBLISHED 저장',
      timeout: '제한 시간 내 반환과 fallback 저장',
      fallbackFailure: 'FALLBACK_STORE_FAILED 노출',
      relaySuccess: '재발행 후 PUBLISHED 전환',
      deadLetter: '세 번째 실패 후 DEAD_LETTER 전환',
      claimRace: '동시 작업자 중 하나만 claim',
      claimTtl: '만료된 claim의 재처리',
      sqlEligibility: 'DB에서 정렬과 batch limit 적용',
      reconcile: '고정된 eventId로 누락 정보 재구성',
      antiJoin: 'SQL cutoff와 anti-join 사용',
      safeQuery: '원본 페이로드와 민감한 오류 정보 제외',
      metrics: '직접 발행, fallback, relay, reconciler 메트릭',
    },
    limitsTitle: '정상 처리의 DB 입력은 줄지만 중복과 복구 위험은 사라지지 않는다',
    solvedLabel: '해결',
    limitLabel: '주의',
    limits: [
      ['정상 주문의 Outbox 입력', '정상 경로에서는 orders만 입력하고 발행 정보를 별도로 저장하지 않습니다.', true],
      ['Kafka 장애 후 재발행', 'NOT_PUBLISHED row를 claim해 Kafka로 다시 발행합니다.', true],
      ['발행 정보 저장 실패', 'grace period 이후 orders를 기준으로 발행 정보를 재구성합니다.', true],
      ['타임아웃 이후 발행 여부', 'Kafka가 이벤트를 받았는지 확정할 수 없습니다.', false],
      ['정확히 한 번 전달', 'claim과 고정 eventId만으로 exactly-once 전달을 보장하지 않습니다.', false],
      ['consumer 중복 처리', '같은 eventId를 다시 받아도 업무 결과가 중복되지 않게 구현해야 합니다.', false],
    ],
    sourceLinks: ['한국어 README', '설계 문서', '구현 계획'],
    footer: '현재 소스와 테스트를 기준으로 구성한 Kafka Outbox Fallback Visual Companion',
  },
  en: {
    lang: 'en',
    title: 'Reduce Outbox Writes in Order Transactions While Recovering Event Publication After Kafka Failures',
    description: 'Compare transactional outbox with Kafka-first fallback, then run direct publish failures, relay retries, and reconciliation.',
    alternate: '한국어',
    alternateHref: './2026-07-30-kafka-outbox-fallback-visual-companion.html',
    themeLabel: 'Toggle theme',
    repoLabel: 'Repository',
    nav: [
      ['Design comparison', 'comparison'],
      ['Architecture', 'architecture'],
      ['Failure recovery', 'simulation'],
      ['Implementation', 'implementation'],
      ['Run', 'run'],
    ],
    kicker: 'Kafka Outbox Fallback · Visual Companion',
    heroLead: 'The normal path writes only orders and publishes directly to Kafka. A publish failure stores a replayable event_publications row; if that write also fails, reconciliation rebuilds publication data from the order.',
    heroFacts: [
      ['1', 'normal-path DB write'],
      ['3', 'direct Kafka attempts'],
      ['6', 'failure and recovery scenarios'],
    ],
    problemTitle: 'Removing the Outbox row from every order adds explicit recovery responsibilities',
    problemLead: 'Transactional outbox stores the order and publication data in one transaction. Kafka-first fallback reduces the normal-path database writes, but must recover timeouts and fallback-store failures separately.',
    problemCards: [
      ['Normal-path cost', 'Writing and indexing an Outbox row for every order increases database work in the order transaction.', 'persistence'],
      ['Uncertain timeout', 'Kafka may have stored the event while only its response was delayed. The application cannot declare non-delivery.', 'messaging'],
      ['Second write failure', 'When both Kafka publication and the event_publications write fail, only the order remains.', 'recovery'],
      ['Duplicate publication', 'Relay or reconciliation can publish an eventId that Kafka already accepted.', 'application'],
    ],
    brainstormTitle: 'Brainstorming: separate normal-path write cost from failure-recovery risk',
    rejectedLabel: 'Rejected',
    selectedLabel: 'Selected',
    decisions: [
      ['Use Redis Streams as the fallback store', 'A second durable system would obscure the core PostgreSQL and Kafka trade-off in the first example.', false],
      ['Keep an Outbox row on the normal path', 'This retains the strongest storage semantics but does not reduce normal-order database writes.', false],
      ['Store only failed publications in PostgreSQL', 'This reuses the current Exposed and PostgreSQL patterns while separating normal and recovery costs.', true],
      ['Use a stable eventId and idempotent consumers', 'This handles duplicate publication caused by timeouts and reconciliation.', true],
    ],
    comparisonTitle: 'Transactional outbox and Kafka-first fallback use different normal paths',
    comparisonLead: 'Switching the approach changes database writes, Kafka waiting, and the recovery reference together. The write count is a structural fact, not a benchmark result.',
    approachNames: { transactional: 'Transactional Outbox', kafkaFirst: 'Kafka-first Fallback' },
    metrics: {
      orderWrites: 'normal-order DB writes',
      kafkaWait: 'wait for Kafka in API handling',
      publicationRows: 'publication data',
      reconciliation: 'missing-row reconstruction',
      deduplication: 'consumer deduplication',
      yes: 'required',
      no: 'none',
      all: 'every event',
      failures: 'failures and repairs only',
      recommended: 'recommended',
      required: 'required',
    },
    approachSummaries: {
      transactional: 'Store orders and Outbox rows in one transaction, then let a relay publish asynchronously.',
      kafkaFirst: 'Commit orders, publish directly to Kafka, and write event_publications only after failure.',
    },
    architectureTitle: 'Separate components own order storage, direct publication, relay, and reconstruction',
    architectureLead: 'The architecture view shows responsibilities and data access, not detailed timing. Use the recovery simulator below for ordered behavior.',
    layerNames: {
      api: 'API layer',
      application: 'Application layer',
      persistence: 'Persistence layer',
      messaging: 'Messaging layer',
      recovery: 'Recovery and operations',
    },
    nodeRoles: {
      OrderController: 'order API',
      PlaceOrderUseCase: 'coordinate store then publish',
      TransactionalOrderWriter: 'orders transaction',
      'PostgreSQL.orders': 'order data',
      OrderEventPublisher: 'direct Kafka publish and fallback',
      'Kafka.order-events': 'OrderPlaced events',
      EventPublicationRepository: 'replay rows and claims',
      'PostgreSQL.event_publications': 'failed and repaired publications',
      EventPublicationRelay: 'claim and republish',
      PublicationReconciler: 'reconstruct missing publication data',
    },
    architectureFlows: [
      ['OrderController → PlaceOrderUseCase', 'order request'],
      ['PlaceOrderUseCase → TransactionalOrderWriter → orders', 'order transaction'],
      ['PlaceOrderUseCase → OrderEventPublisher → Kafka', 'direct publish after commit'],
      ['OrderEventPublisher → EventPublicationRepository', 'store after failure'],
      ['EventPublicationRelay → event_publications → Kafka', 'claim and republish'],
      ['PublicationReconciler → orders → event_publications', 'reconstruct missing rows'],
    ],
    simulationTitle: 'Select a failure condition and observe database, Kafka, and API state',
    simulationLead: 'Advancing the scenario changes the active layer and cumulative state. A Kafka timeout uses unknown, not zero, for the received-event count.',
    scenarioNames: {
      DIRECT_SUCCESS: 'Direct publish succeeds',
      DIRECT_FAILURE: 'Direct Kafka publish fails',
      DIRECT_TIMEOUT: 'Kafka publish times out',
      RELAY_RECOVERY: 'Relay republishes successfully',
      RELAY_DEAD_LETTER: 'Relay repeatedly fails',
      FALLBACK_STORE_FAILURE: 'Fallback store fails and recovers',
    },
    scenarioReasons: {
      DIRECT_SUCCESS: 'The normal path creates no event_publications row.',
      DIRECT_FAILURE: 'Three failed direct attempts store replayable publication data.',
      DIRECT_TIMEOUT: 'The same eventId survives even though Kafka receipt is unknown.',
      RELAY_RECOVERY: 'One worker claims the row and records Kafka confirmation as PUBLISHED.',
      RELAY_DEAD_LETTER: 'The third relay failure stops automatic processing for operator review.',
      FALLBACK_STORE_FAILURE: 'The reconciler finds an order without publication data through a SQL anti-join.',
    },
    eventDetails: {
      ORDER_STORED: 'TransactionalOrderWriter writes only the orders row.',
      TRANSACTION_COMMITTED: 'The order transaction commits. Kafka publication runs outside this boundary.',
      KAFKA_ATTEMPT_1: 'OrderEventPublisher sends the eventId as the Kafka record key.',
      KAFKA_CONFIRMED: 'Kafka confirms publication before the timeout.',
      API_PUBLISHED_DIRECT: 'The API returns PUBLISHED_DIRECT without creating a fallback row.',
      KAFKA_ATTEMPT_1_FAILED: 'The first direct Kafka attempt fails.',
      KAFKA_ATTEMPT_2_FAILED: 'The second attempt reuses the eventId and fails.',
      KAFKA_ATTEMPT_3_FAILED: 'The third failure moves processing to durable fallback storage.',
      FALLBACK_NOT_PUBLISHED: 'The repository upserts a NOT_PUBLISHED event_publications row.',
      API_FALLBACK_STORED: 'The API returns FALLBACK_STORED.',
      KAFKA_TIMEOUT_1: 'The first response times out. Kafka receipt is unknown.',
      KAFKA_TIMEOUT_2: 'The same eventId is retried without a confirmed result.',
      KAFKA_TIMEOUT_3: 'The third timeout moves processing to fallback storage.',
      FALLBACK_SAME_EVENT_ID: 'The NOT_PUBLISHED row retains order-placed:{orderId}:v1.',
      FALLBACK_READY: 'A NOT_PUBLISHED row from direct failure is eligible for relay.',
      RELAY_CLAIM_RACE: 'relay-a claims the row while relay-b receives an empty result.',
      RELAY_KAFKA_SEND: 'The claim owner sends the stored payload to Kafka.',
      RELAY_KAFKA_CONFIRMED: 'Kafka confirms the relayed event.',
      MARK_PUBLISHED: 'The repository verifies claim ownership, records PUBLISHED, and clears the claim.',
      RELAY_CLAIM_1: 'relay-a claims the first relay attempt.',
      RELAY_FAILURE_1: 'The first failure records relayRetryCount=1 and FAILED.',
      RELAY_CLAIM_2: 'relay-b claims the row after nextAttemptAt.',
      RELAY_FAILURE_2: 'The second failure records relayRetryCount=2.',
      RELAY_CLAIM_3: 'relay-c starts the last permitted retry.',
      RELAY_DEAD_LETTERED: 'The third failure moves the row to DEAD_LETTER and stops automatic processing.',
      DIRECT_ATTEMPTS_FAILED: 'All three direct Kafka attempts fail.',
      FALLBACK_INSERT_FAILED: 'The event_publications write also fails, so the API returns FALLBACK_STORE_FAILED.',
      GRACE_PERIOD_ELAPSED: 'The order becomes eligible after reconcilerGrace.',
      RECONCILER_ANTI_JOIN: 'A SQL cutoff and anti-join find orders without publication data.',
      RECONSTRUCT_EVENT: 'The reconciler rebuilds OrderPlacedEvent and its stable eventId from orders.',
      UPSERT_RECONSTRUCTED: 'A NOT_PUBLISHED row is upserted and becomes relay-eligible.',
    },
    controls: {
      previous: 'Previous step',
      next: 'Next step',
      reset: 'Restart',
      play: 'Play',
      pause: 'Pause',
      step: 'Current step',
      layer: 'Active layer',
    },
    metricLabels: {
      apiStatus: 'API result',
      orderRows: 'orders',
      publicationRows: 'event_publications',
      kafkaEvents: 'Kafka events',
      directAttempts: 'direct attempts',
      relayRetries: 'relay retries',
      publicationStatus: 'publication state',
      claimOwner: 'claim owner',
      unknown: 'unknown',
      none: 'none',
    },
    claimNote: 'A claim prevents two workers from processing the same row at the same time. Duplicate publication is still possible if the process stops after Kafka confirms but before PostgreSQL records PUBLISHED.',
    lifecycleTitle: 'Keep publication states separate from the claim-processing state',
    lifecycleLead: 'Select a state to inspect its entry condition and next action. CLAIMED is not an EventPublicationStatus enum value.',
    stateDescriptions: {
      NO_ROW: ['Direct Kafka publication succeeded, or missing publication data has not been repaired yet', 'Kafka failure, direct publish disabled, reconciliation'],
      NOT_PUBLISHED: ['Durable publication data waiting for relay', 'relay claim'],
      CLAIMED: ['Processing state while claimedBy and claimedUntil are valid', 'PUBLISHED, FAILED, DEAD_LETTER'],
      FAILED: ['Relay failed but has not reached the retry limit', 'retry after claim expiry or nextAttemptAt'],
      PUBLISHED: ['Kafka confirmation and publishedAt have been recorded', 'terminal'],
      DEAD_LETTER: ['Retry limit reached and operator review is required', 'operator decision'],
    },
    stateLabels: ['Meaning', 'Entry or next action'],
    classesTitle: 'Concrete classes implement each simulator step',
    classesLead: 'The class view includes only public methods and use relationships that map to the scenarios.',
    implementationTitle: 'Exposed separates the order transaction from replayable publication data',
    implementationLead: 'Order writes, eventId upserts, claims, and SQL anti-joins run through different repository methods.',
    implementationItems: [
      ['Order transaction', 'TransactionalOrderWriter.saveOrder() changes only orders. Kafka and event_publications run after commit.'],
      ['Stable eventId', 'OrderPlacedEvent.from(order) uses order-placed:{orderId}:v1. The unique event_id upsert collapses retries and reconstruction into one row.'],
      ['SQL claim', 'claimNextBatch() applies state, nextAttemptAt, claim expiry, ordering, and batch limits in the database.'],
      ['Missing-row query', 'findOrdersWithoutPublicationsCreatedOnOrBefore() uses a cutoff and anti-join to find orders without publication data.'],
    ],
    runTitle: 'Start the related services, then inspect the normal and recovery paths',
    runLead: 'The application uses PostgreSQL and Kafka. Demo admin endpoints are disabled by default.',
    runTabs: ['Related services', 'Application', 'API', 'Relay / Reconcile', 'Tests'],
    runCommands: [
      `docker run --rm --name kafka-outbox-postgres \\
  -e POSTGRES_DB=workshop \\
  -e POSTGRES_USER=postgres \\
  -e POSTGRES_PASSWORD=postgres \\
  -p 5432:5432 postgres:18-alpine

# Start Kafka so the application can reach localhost:9092.`,
      `./gradlew :messaging-kafka-outbox-fallback:bootRun`,
      `curl -s -X POST http://localhost:8080/api/orders \\
  -H 'Content-Type: application/json' \\
  -d '{"customerId":"customer-1001","product":"coffee-beans","quantity":2}'

curl -s http://localhost:8080/api/orders
curl -s http://localhost:8080/api/publications`,
      `# application.yml
workshop:
  kafka-outbox-fallback:
    demo-admin-endpoints-enabled: true

curl -s -X POST http://localhost:8080/api/publications/relay
curl -s -X POST http://localhost:8080/api/publications/reconcile`,
      `./gradlew :messaging-kafka-outbox-fallback:test --max-workers=1

./gradlew :messaging-kafka-outbox-fallback:test \\
  --tests 'io.bluetape4k.workshop.messaging.fallback.KafkaOutboxFallbackFlowTest' \\
  --max-workers=1`,
    ],
    runNotes: [
      'Start PostgreSQL and Kafka first. The Kafka runtime must expose localhost:9092 in the selected deployment environment.',
      'Defaults use jdbc:postgresql://localhost:5432/workshop and localhost:9092.',
      'The normal response uses publicationStatus=PUBLISHED_DIRECT and leaves event_publications empty.',
      'Demo admin endpoints are for the workshop. Do not expose them in production without authentication, authorization, and rate limits.',
      'Tests start PostgreSQL and Kafka through Testcontainers launchers and control KafkaTemplate results per path.',
    ],
    testsTitle: 'Tests cover the normal path, recovery, concurrent claims, and safe query output',
    testDescriptions: {
      transactionOnly: 'order transaction writes only orders',
      directSuccess: 'direct success without a fallback row',
      directFailure: 'NOT_PUBLISHED after three failures',
      timeout: 'bounded response time and fallback storage',
      fallbackFailure: 'visible FALLBACK_STORE_FAILED result',
      relaySuccess: 'PUBLISHED after relay',
      deadLetter: 'DEAD_LETTER after the third failure',
      claimRace: 'one claim winner under concurrency',
      claimTtl: 'expired claims become eligible',
      sqlEligibility: 'ordering and batch limits in SQL',
      reconcile: 'stable eventId reconstruction',
      antiJoin: 'SQL cutoff and anti-join',
      safeQuery: 'no raw payload or sensitive error text',
      metrics: 'direct, fallback, relay, and reconciler metrics',
    },
    limitsTitle: 'The normal path writes less data, but duplicate and recovery risks remain',
    solvedLabel: 'Handled',
    limitLabel: 'Caveat',
    limits: [
      ['Normal-order Outbox write', 'The normal path writes only orders and does not persist publication data.', true],
      ['Republish after Kafka failure', 'A relay claims NOT_PUBLISHED rows and republishes them to Kafka.', true],
      ['Fallback-store failure', 'Reconciliation rebuilds publication data from orders after the grace period.', true],
      ['Delivery after timeout', 'The application cannot determine whether Kafka stored the event.', false],
      ['Exactly-once delivery', 'Claims and stable eventIds do not provide exactly-once delivery.', false],
      ['Consumer deduplication', 'Consumers must prevent duplicate business effects for the same eventId.', false],
    ],
    sourceLinks: ['English README', 'Design document', 'Implementation plan'],
    footer: 'Kafka Outbox Fallback Visual Companion derived from current source and tests',
  },
};

function assertLocale(locale, content) {
  if (!['ko', 'en'].includes(locale)) throw new Error(`Unsupported locale: ${locale}`);
  if (!content.title || !content.description || !content.alternateHref) {
    throw new Error(`Incomplete locale content: ${locale}`);
  }
}

function assertScenarios(value) {
  const expected = [
    'DIRECT_SUCCESS',
    'DIRECT_FAILURE',
    'DIRECT_TIMEOUT',
    'RELAY_RECOVERY',
    'RELAY_DEAD_LETTER',
    'FALLBACK_STORE_FAILURE',
  ];
  if (JSON.stringify(Object.keys(value)) !== JSON.stringify(expected)) {
    throw new Error('Scenario contract does not match the approved design');
  }
}

function assertFinalState(id, scenario) {
  const actual = [
    scenario.final.apiStatus,
    scenario.final.orderRows,
    scenario.final.publicationRows,
    scenario.final.kafkaEvents,
    scenario.final.directAttempts,
    scenario.final.relayRetries,
    scenario.final.publicationStatus,
  ];
  if (JSON.stringify(actual) !== JSON.stringify(expectedFinals[id])) {
    throw new Error(`Final state mismatch: ${id}`);
  }
}

function escapeHtml(value) {
  return String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#039;');
}

function html(locale, content) {
  const sourceHref = `./${source}`;
  const readmeHref = locale === 'ko'
    ? '../../../messaging/kafka-outbox-fallback/README.ko.md'
    : '../../../messaging/kafka-outbox-fallback/README.md';
  const planHref = '../plans/2026-07-30-kafka-outbox-fallback-visual-companion-plan.md';

  const nav = content.nav
    .map(([label, target]) => `<a href="#${target}">${escapeHtml(label)}</a>`)
    .join('');
  const heroFacts = content.heroFacts
    .map(([value, label]) => `<div class="hero-fact"><strong>${escapeHtml(value)}</strong><span>${escapeHtml(label)}</span></div>`)
    .join('');
  const problemCards = content.problemCards
    .map(([title, body, layer]) => `<article class="problem-card layer-${layer}"><span class="layer-dot"></span><h3>${escapeHtml(title)}</h3><p>${escapeHtml(body)}</p></article>`)
    .join('');
  const decisions = content.decisions
    .map(([title, body, selected]) => `<article class="decision ${selected ? 'selected' : ''}"><span>${escapeHtml(selected ? content.selectedLabel : content.rejectedLabel)}</span><h3>${escapeHtml(title)}</h3><p>${escapeHtml(body)}</p></article>`)
    .join('');
  const approachButtons = Object.keys(approaches)
    .map((key) => `<button type="button" class="segment" data-approach="${key}" aria-pressed="${key === 'transactional'}">${escapeHtml(content.approachNames[key])}</button>`)
    .join('');
  const architectureLayers = ['api', 'application', 'persistence', 'messaging', 'recovery']
    .map((layer) => {
      const nodes = architectureNodes
        .filter(([, nodeLayer]) => nodeLayer === layer)
        .map(([name]) => `<div class="architecture-card layer-${layer}"><div><strong>${escapeHtml(name)}</strong><span>${escapeHtml(content.nodeRoles[name])}</span></div></div>`)
        .join('');
      return `<div class="architecture-layer"><h3>${escapeHtml(content.layerNames[layer])}</h3><div class="architecture-nodes">${nodes}</div></div>`;
    })
    .join('');
  const architectureFlows = content.architectureFlows
    .map(([route, label]) => `<li><code>${escapeHtml(route)}</code><span>${escapeHtml(label)}</span></li>`)
    .join('');
  const scenarioButtons = Object.keys(scenarios)
    .map((key, index) => `<button type="button" data-scenario="${key}" aria-pressed="${index === 0}"><span>${String(index + 1).padStart(2, '0')}</span>${escapeHtml(content.scenarioNames[key])}</button>`)
    .join('');
  const layerLegend = Object.entries(content.layerNames)
    .map(([key, label]) => `<span class="legend-item layer-${key}"><i></i>${escapeHtml(label)}</span>`)
    .join('');
  const stateButtons = Object.keys(publicationStates)
    .map((state, index) => `<button type="button" class="state-node state-${state.toLowerCase().replace('_', '-')}" data-state="${state}" aria-pressed="${index === 0}">${state.replace('_', ' ')}</button>`)
    .join('<span class="state-arrow" aria-hidden="true">→</span>');
  const classCards = classes
    .map(([name, layer, methods]) => `<article class="class-card layer-${layer}"><div><span class="class-stereotype">${escapeHtml(content.layerNames[layer])}</span><h3>${escapeHtml(name)}</h3><ul>${methods.map((method) => `<li>${escapeHtml(method)}</li>`).join('')}</ul></div></article>`)
    .join('');
  const implementationItems = content.implementationItems
    .map(([title, body]) => `<article><h3>${escapeHtml(title)}</h3><p>${escapeHtml(body)}</p></article>`)
    .join('');
  const runTabs = content.runTabs
    .map((label, index) => `<button type="button" class="run-tab" data-run="${index}" aria-pressed="${index === 0}">${escapeHtml(label)}</button>`)
    .join('');
  const tests = sourceTests
    .map(([key, test]) => `<tr><td>${escapeHtml(content.testDescriptions[key])}</td><td><code>${escapeHtml(test)}</code></td></tr>`)
    .join('');
  const limits = content.limits
    .map(([title, body, solved]) => `<article class="limit-row ${solved ? 'solved' : 'caveat'}"><span>${escapeHtml(solved ? content.solvedLabel : content.limitLabel)}</span><div><h3>${escapeHtml(title)}</h3><p>${escapeHtml(body)}</p></div></article>`)
    .join('');

  return `<!doctype html>
<html lang="${content.lang}">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="color-scheme" content="light dark">
  <meta name="description" content="${escapeHtml(content.description)}">
  <link rel="icon" href="data:,">
  <title>${escapeHtml(content.title)}</title>
  <script>
    (() => {
      const storageKey = 'starlight-theme';
      const saved = localStorage.getItem(storageKey);
      document.documentElement.dataset.theme =
        saved === 'light' || saved === 'dark'
          ? saved
          : matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
    })();
  </script>
  <style>
    :root {
      --page: #f4f7f8;
      --surface: #ffffff;
      --surface-2: #eaf0f2;
      --text: #14202b;
      --muted: #566778;
      --line: #c9d4dc;
      --code: #edf2f5;
      --focus: #0969da;
      --good: #147d45;
      --warning: #b45d00;
      --bad: #b52f3b;
      --layer-api: #0f8b8d;
      --layer-api-soft: #d9f3f1;
      --layer-application: #2563eb;
      --layer-application-soft: #e2ebff;
      --layer-persistence: #27864b;
      --layer-persistence-soft: #ddf3e5;
      --layer-messaging: #d97706;
      --layer-messaging-soft: #fff0d8;
      --layer-recovery: #b33f8f;
      --layer-recovery-soft: #fae3f3;
      --shadow: 0 12px 34px rgb(31 50 64 / 10%);
    }
    :root[data-theme="light"] {
      color-scheme: light;
    }
    :root[data-theme="dark"] {
      color-scheme: dark;
      --page: #10161b;
      --surface: #182129;
      --surface-2: #202c35;
      --text: #edf4f7;
      --muted: #aebdc7;
      --line: #3a4a55;
      --code: #11191f;
      --focus: #71b7ff;
      --good: #52c987;
      --warning: #ffb457;
      --bad: #ff7c88;
      --layer-api: #3dc2bd;
      --layer-api-soft: #173a3a;
      --layer-application: #77a7ff;
      --layer-application-soft: #1e3359;
      --layer-persistence: #5bd08a;
      --layer-persistence-soft: #1b3b2a;
      --layer-messaging: #ffae42;
      --layer-messaging-soft: #473016;
      --layer-recovery: #eb79c3;
      --layer-recovery-soft: #48223d;
      --shadow: 0 16px 40px rgb(0 0 0 / 32%);
    }
    * { box-sizing: border-box; }
    html { scroll-behavior: smooth; }
    body {
      margin: 0;
      background: var(--page);
      color: var(--text);
      font-family: ${locale === 'ko' ? "'goorm Sans', 'Pretendard', 'Noto Sans KR', sans-serif" : "Inter, ui-sans-serif, system-ui, sans-serif"};
      line-height: 1.65;
      letter-spacing: 0;
    }
    button, a { font: inherit; }
    button { color: inherit; }
    a { color: inherit; }
    h1, h2, h3, h4, p, li, td, strong, span { overflow-wrap: anywhere; }
    code, pre {
      font-family: ${locale === 'ko' ? "'goorm Sans Code', 'D2Coding', ui-monospace, monospace" : "'Comic Mono', ui-monospace, monospace"};
    }
    code { overflow-wrap: anywhere; }
    :focus-visible { outline: 3px solid var(--focus); outline-offset: 3px; }
    .site-header {
      position: sticky;
      top: 0;
      z-index: 20;
      display: flex;
      align-items: center;
      min-height: 3.7rem;
      border-bottom: 1px solid var(--line);
      background: color-mix(in srgb, var(--page) 90%, transparent);
      backdrop-filter: blur(16px);
    }
    .header-inner {
      width: min(1180px, calc(100% - 2rem));
      margin: auto;
      display: flex;
      align-items: center;
      gap: 1.2rem;
    }
    .brand { font-weight: 800; text-decoration: none; white-space: nowrap; }
    .site-nav { display: flex; gap: 1rem; margin-left: auto; }
    .site-nav a, .locale-link {
      color: var(--muted);
      font-size: .9rem;
      font-weight: 700;
      text-decoration: none;
    }
    .site-nav a:hover, .locale-link:hover { color: var(--text); }
    .header-actions { display: flex; gap: .45rem; align-items: center; }
    .theme-toggle {
      width: 2.4rem;
      height: 2.4rem;
      border: 1px solid var(--line);
      border-radius: 6px;
      background: var(--surface);
      cursor: pointer;
      font-size: 1.1rem;
    }
    main { overflow: hidden; }
    section {
      width: min(1180px, calc(100% - 2rem));
      margin: 0 auto;
      padding: 5.2rem 0;
      scroll-margin-top: 4rem;
    }
    section + section { border-top: 1px solid var(--line); }
    .hero {
      min-height: min(78vh, 780px);
      display: grid;
      align-content: center;
      padding-top: 5.5rem;
      padding-bottom: 4rem;
    }
    .kicker {
      margin: 0 0 1rem;
      color: var(--layer-messaging);
      font-weight: 850;
      text-transform: uppercase;
      letter-spacing: .08em;
      font-size: .84rem;
    }
    h1 {
      max-width: 1050px;
      margin: 0;
      font-size: clamp(2.25rem, 5vw, 4.8rem);
      line-height: 1.08;
      letter-spacing: 0;
    }
    .hero-lead {
      max-width: 840px;
      margin: 1.6rem 0 2.2rem;
      color: var(--muted);
      font-size: clamp(1.02rem, 2vw, 1.28rem);
    }
    .hero-facts {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      max-width: 760px;
      border-top: 1px solid var(--line);
      border-bottom: 1px solid var(--line);
    }
    .hero-fact { padding: 1.2rem 1.3rem 1.2rem 0; }
    .hero-fact strong { display: block; font-size: 1.55rem; }
    .hero-fact span { color: var(--muted); font-size: .9rem; }
    .section-heading { max-width: 900px; margin-bottom: 2.3rem; }
    .section-heading h2 {
      margin: 0 0 .8rem;
      font-size: clamp(1.8rem, 3.5vw, 3rem);
      line-height: 1.18;
      letter-spacing: 0;
    }
    .section-heading p { margin: 0; color: var(--muted); font-size: 1.05rem; }
    .problem-grid, .decision-grid, .implementation-grid {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 1rem;
    }
    .problem-grid > *, .decision-grid > *, .implementation-grid > * { min-width: 0; }
    .problem-card, .decision, .implementation-grid article {
      min-height: 10rem;
      padding: 1.45rem;
      border: 1px solid var(--line);
      border-radius: 8px;
      background: var(--surface);
    }
    .problem-card h3, .decision h3, .implementation-grid h3 { margin: .6rem 0 .4rem; }
    .problem-card p, .decision p, .implementation-grid p { margin: 0; color: var(--muted); }
    .layer-dot { display: block; width: 2.5rem; height: .35rem; border-radius: 2px; background: currentColor; }
    .layer-api { color: var(--layer-api); }
    .layer-application { color: var(--layer-application); }
    .layer-persistence { color: var(--layer-persistence); }
    .layer-messaging { color: var(--layer-messaging); }
    .layer-recovery { color: var(--layer-recovery); }
    .decision > span, .limit-row > span {
      display: inline-block;
      color: var(--muted);
      font-size: .75rem;
      font-weight: 850;
      text-transform: uppercase;
      letter-spacing: .05em;
    }
    .decision.selected { border-color: var(--good); box-shadow: inset 4px 0 var(--good); }
    .brainstorm-heading { margin: 3.4rem 0 1.2rem; font-size: 1.45rem; }
    .comparison-tool {
      display: grid;
      grid-template-columns: minmax(15rem, .72fr) minmax(0, 1.28fr);
      gap: 2rem;
      align-items: stretch;
    }
    .segments {
      display: grid;
      gap: .6rem;
      align-content: start;
    }
    .segment {
      min-height: 3.4rem;
      padding: .85rem 1rem;
      border: 1px solid var(--line);
      border-radius: 6px;
      background: var(--surface);
      text-align: left;
      font-weight: 800;
      cursor: pointer;
    }
    .segment[aria-pressed="true"] {
      border-color: var(--layer-messaging);
      box-shadow: inset 4px 0 var(--layer-messaging);
    }
    .approach-summary { min-height: 6rem; margin: 1.2rem 0 0; color: var(--muted); }
    .comparison-metrics {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: .8rem;
    }
    .metric {
      min-height: 7rem;
      padding: 1rem;
      border: 1px solid var(--line);
      border-radius: 6px;
      background: var(--surface);
      display: flex;
      flex-direction: column;
      justify-content: center;
    }
    .metric span { color: var(--muted); font-size: .82rem; font-weight: 700; }
    .metric strong { margin-top: .3rem; font-size: 1.3rem; overflow-wrap: anywhere; }
    .architecture-board { display: grid; gap: .9rem; }
    .architecture-layer {
      display: grid;
      grid-template-columns: 10.5rem minmax(0, 1fr);
      gap: 1rem;
      align-items: stretch;
      padding: .9rem;
      border: 1px solid var(--line);
      border-radius: 8px;
      background: var(--surface);
    }
    .architecture-layer > h3 {
      margin: 0;
      display: flex;
      align-items: center;
      padding: .8rem;
      color: var(--muted);
      font-size: .9rem;
    }
    .architecture-nodes {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(12rem, 1fr));
      gap: .75rem;
    }
    .architecture-card, .class-card {
      min-height: 7.5rem;
      padding: 1rem;
      border: 1px solid currentColor;
      border-radius: 6px;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      text-align: center;
    }
    .architecture-card.layer-api, .class-card.layer-api { background: var(--layer-api-soft); }
    .architecture-card.layer-application, .class-card.layer-application { background: var(--layer-application-soft); }
    .architecture-card.layer-persistence, .class-card.layer-persistence { background: var(--layer-persistence-soft); }
    .architecture-card.layer-messaging, .class-card.layer-messaging { background: var(--layer-messaging-soft); }
    .architecture-card.layer-recovery, .class-card.layer-recovery { background: var(--layer-recovery-soft); }
    .architecture-card strong { display: block; color: var(--text); font-size: .94rem; overflow-wrap: anywhere; }
    .architecture-card span { display: block; margin-top: .35rem; color: var(--muted); font-size: .8rem; }
    .flow-list {
      margin: 1.2rem 0 0;
      padding: 0;
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: .65rem;
      list-style: none;
    }
    .flow-list li {
      padding: .85rem 1rem;
      border-left: 3px solid var(--layer-messaging);
      background: var(--surface);
    }
    .flow-list code { display: block; font-size: .78rem; }
    .flow-list span { color: var(--muted); font-size: .85rem; }
    .simulation-layout {
      display: grid;
      grid-template-columns: 17rem minmax(0, 1fr);
      gap: 1.2rem;
    }
    .scenario-list { display: grid; gap: .5rem; align-content: start; }
    .scenario-list button {
      min-height: 3.5rem;
      display: grid;
      grid-template-columns: 2.2rem 1fr;
      align-items: center;
      gap: .55rem;
      padding: .7rem .85rem;
      border: 1px solid var(--line);
      border-radius: 6px;
      background: var(--surface);
      text-align: left;
      cursor: pointer;
      font-size: .88rem;
      font-weight: 750;
    }
    .scenario-list button span { color: var(--muted); font-size: .72rem; }
    .scenario-list button[aria-pressed="true"] {
      border-color: var(--layer-recovery);
      box-shadow: inset 4px 0 var(--layer-recovery);
    }
    .simulation-workspace { min-width: 0; }
    .scenario-header {
      display: flex;
      justify-content: space-between;
      gap: 1rem;
      align-items: flex-start;
      margin-bottom: 1rem;
    }
    .scenario-header h3 { margin: 0; font-size: 1.35rem; }
    .scenario-header p { margin: .3rem 0 0; color: var(--muted); }
    .sim-controls { display: flex; gap: .45rem; flex-shrink: 0; }
    .icon-button {
      width: 2.7rem;
      height: 2.7rem;
      border: 1px solid var(--line);
      border-radius: 6px;
      background: var(--surface);
      cursor: pointer;
      font-weight: 900;
    }
    .icon-button:disabled { opacity: .4; cursor: not-allowed; }
    .event-track {
      display: grid;
      grid-auto-flow: column;
      grid-auto-columns: minmax(8.5rem, 1fr);
      gap: .55rem;
      overflow-x: auto;
      padding: .25rem .1rem 1rem;
      scrollbar-width: thin;
    }
    .event-step {
      min-height: 5.6rem;
      padding: .8rem;
      border: 1px solid var(--line);
      border-radius: 6px;
      background: var(--surface);
      color: var(--muted);
      font-size: .76rem;
      font-weight: 750;
      opacity: .46;
    }
    .event-step.done { opacity: .78; }
    .event-step.current {
      opacity: 1;
      color: var(--text);
      border-color: currentColor;
      box-shadow: 0 0 0 2px color-mix(in srgb, currentColor 22%, transparent);
    }
    .event-step i { display: block; width: 1.8rem; height: .28rem; margin-bottom: .65rem; background: currentColor; }
    .event-step div { overflow-wrap: anywhere; }
    .event-detail {
      min-height: 8.4rem;
      padding: 1.2rem;
      border: 1px solid var(--line);
      border-radius: 8px;
      background: var(--surface);
    }
    .event-detail-head { display: flex; justify-content: space-between; gap: 1rem; }
    .event-detail-head span { color: var(--muted); font-size: .8rem; font-weight: 750; }
    .event-detail h4 { margin: .5rem 0; font-size: 1.05rem; }
    .event-detail p { margin: 0; color: var(--muted); }
    .sim-metrics {
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
      gap: .55rem;
      margin-top: .7rem;
    }
    .sim-metrics .metric { min-height: 5.7rem; padding: .75rem; }
    .sim-metrics .metric strong { font-size: .94rem; }
    .claim-race {
      display: none;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: .7rem;
      margin-top: .7rem;
    }
    .claim-race.visible { display: grid; }
    .claim-worker { padding: .8rem; border: 1px solid var(--line); border-radius: 6px; background: var(--surface); }
    .claim-worker strong, .claim-worker span { display: block; }
    .claim-worker span { margin-top: .25rem; color: var(--muted); font-size: .82rem; }
    .claim-note {
      margin: .75rem 0 0;
      padding: .85rem 1rem;
      border-left: 3px solid var(--warning);
      background: color-mix(in srgb, var(--warning) 10%, transparent);
      color: var(--muted);
      font-size: .86rem;
    }
    .legend { display: flex; flex-wrap: wrap; gap: .8rem; margin-top: 1.2rem; }
    .legend-item { display: inline-flex; gap: .35rem; align-items: center; color: var(--muted); font-size: .78rem; }
    .legend-item i { width: .8rem; height: .8rem; border-radius: 2px; background: currentColor; }
    .state-flow {
      display: flex;
      align-items: center;
      gap: .55rem;
      overflow-x: auto;
      padding: .5rem 0 1rem;
    }
    .state-node {
      min-width: 8.4rem;
      min-height: 3.2rem;
      border: 1px solid var(--line);
      border-radius: 6px;
      background: var(--surface);
      cursor: pointer;
      font-weight: 800;
    }
    .state-node[aria-pressed="true"] { border-color: var(--layer-recovery); box-shadow: inset 0 -4px var(--layer-recovery); }
    .state-arrow { color: var(--muted); }
    .state-detail {
      display: grid;
      grid-template-columns: 10rem 1fr 1fr;
      gap: 1rem;
      align-items: center;
      padding: 1.2rem;
      border: 1px solid var(--line);
      border-radius: 8px;
      background: var(--surface);
    }
    .state-detail strong { font-size: 1.15rem; }
    .state-detail span { display: block; color: var(--muted); font-size: .78rem; }
    .state-detail p { margin: .25rem 0 0; }
    .class-grid {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: .8rem;
    }
    .class-card { min-height: 11rem; color: var(--text); align-items: stretch; text-align: left; }
    .class-card > div { width: 100%; }
    .class-stereotype { color: var(--muted); font-size: .7rem; font-weight: 800; text-transform: uppercase; }
    .class-card h3 { margin: .35rem 0 .7rem; overflow-wrap: anywhere; }
    .class-card ul { margin: 0; padding: .7rem 0 0; border-top: 1px solid var(--line); list-style: none; }
    .class-card li { padding: .16rem 0; color: var(--muted); font-family: ui-monospace, monospace; font-size: .72rem; overflow-wrap: anywhere; }
    .implementation-grid article { min-height: 9rem; }
    .run-tool {
      display: grid;
      grid-template-columns: 13rem minmax(0, 1fr);
      gap: 1rem;
    }
    .run-tabs { display: grid; gap: .45rem; align-content: start; }
    .run-tab {
      min-height: 3rem;
      padding: .6rem .8rem;
      border: 1px solid var(--line);
      border-radius: 6px;
      background: var(--surface);
      text-align: left;
      cursor: pointer;
      font-weight: 750;
    }
    .run-tab[aria-pressed="true"] { border-color: var(--layer-application); box-shadow: inset 4px 0 var(--layer-application); }
    .code-panel { min-width: 0; }
    pre {
      min-height: 18rem;
      margin: 0;
      padding: 1.2rem;
      overflow: auto;
      border: 1px solid var(--line);
      border-radius: 8px;
      background: var(--code);
      color: var(--text);
      font-size: .8rem;
      line-height: 1.6;
      white-space: pre-wrap;
      overflow-wrap: anywhere;
    }
    .run-note { margin: .7rem 0 0; color: var(--muted); font-size: .88rem; }
    .table-wrap { overflow-x: auto; border: 1px solid var(--line); border-radius: 8px; }
    table { width: 100%; border-collapse: collapse; background: var(--surface); font-size: .86rem; }
    th, td { padding: .85rem 1rem; border-bottom: 1px solid var(--line); text-align: left; vertical-align: top; }
    th { color: var(--muted); font-size: .74rem; text-transform: uppercase; }
    th.identifier-heading {
      font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
      text-transform: none;
      letter-spacing: 0;
    }
    tr:last-child td { border-bottom: 0; }
    td:first-child { width: 35%; }
    .limits { display: grid; gap: .65rem; }
    .limit-row {
      display: grid;
      grid-template-columns: 5.5rem minmax(0, 1fr);
      gap: 1rem;
      align-items: start;
      padding: 1rem 1.1rem;
      border: 1px solid var(--line);
      border-radius: 6px;
      background: var(--surface);
    }
    .limit-row.solved { border-left: 4px solid var(--good); }
    .limit-row.caveat { border-left: 4px solid var(--warning); }
    .limit-row h3 { margin: 0; font-size: 1rem; }
    .limit-row p { margin: .2rem 0 0; color: var(--muted); }
    footer {
      width: min(1180px, calc(100% - 2rem));
      margin: 0 auto;
      padding: 2.5rem 0 3.5rem;
      border-top: 1px solid var(--line);
      color: var(--muted);
      font-size: .86rem;
    }
    .footer-links { display: flex; flex-wrap: wrap; gap: 1rem; margin-bottom: .7rem; }
    .footer-links a { color: var(--text); font-weight: 750; }
    @media (max-width: 900px) {
      .site-nav { display: none; }
      .comparison-tool, .simulation-layout { grid-template-columns: 1fr; }
      .scenario-list { grid-template-columns: repeat(2, minmax(0, 1fr)); }
      .architecture-layer { grid-template-columns: 1fr; }
      .architecture-layer > h3 { padding: .15rem; }
      .class-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
      .sim-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); }
    }
    @media (max-width: 620px) {
      .header-inner { width: min(100% - 1rem, 1180px); gap: .6rem; }
      .brand { max-width: 9rem; overflow: hidden; text-overflow: ellipsis; }
      .locale-link { font-size: .78rem; }
      section { width: min(100% - 1.2rem, 1180px); padding: 3.7rem 0; }
      .hero { min-height: 72vh; padding-top: 4.5rem; }
      h1 { font-size: 2.35rem; }
      .hero-facts { grid-template-columns: 1fr; }
      .hero-fact { padding: .7rem 0; border-bottom: 1px solid var(--line); }
      .hero-fact:last-child { border-bottom: 0; }
      .problem-grid, .decision-grid, .implementation-grid, .comparison-metrics,
      .scenario-list, .class-grid, .claim-race, .flow-list { grid-template-columns: 1fr; }
      .scenario-header { display: block; }
      .sim-controls { margin-top: .9rem; }
      .state-detail { grid-template-columns: 1fr; }
      .run-tool { grid-template-columns: 1fr; }
      .run-tabs { grid-template-columns: repeat(2, minmax(0, 1fr)); }
      td:first-child { width: auto; }
      .limit-row { grid-template-columns: 1fr; }
    }
    @media (prefers-reduced-motion: reduce) {
      html { scroll-behavior: auto; }
      *, *::before, *::after { animation-duration: .01ms !important; transition-duration: .01ms !important; }
    }
  </style>
</head>
<body>
  <header class="site-header">
    <div class="header-inner">
      <a class="brand" href="#top">bluetape4k-workshop</a>
      <nav class="site-nav" aria-label="Visual Companion">${nav}</nav>
      <div class="header-actions">
        <a class="locale-link" href="${content.alternateHref}" hreflang="${locale === 'ko' ? 'en' : 'ko'}">${escapeHtml(content.alternate)}</a>
        <button type="button" class="theme-toggle" aria-label="${escapeHtml(content.themeLabel)}" title="${escapeHtml(content.themeLabel)}">◐</button>
      </div>
    </div>
  </header>
  <main id="top">
    <section class="hero">
      <p class="kicker">${escapeHtml(content.kicker)}</p>
      <h1>${escapeHtml(content.title)}</h1>
      <p class="hero-lead">${escapeHtml(content.heroLead)}</p>
      <div class="hero-facts">${heroFacts}</div>
    </section>

    <section id="problem">
      <div class="section-heading"><h2>${escapeHtml(content.problemTitle)}</h2><p>${escapeHtml(content.problemLead)}</p></div>
      <div class="problem-grid">${problemCards}</div>
      <h2 class="brainstorm-heading">${escapeHtml(content.brainstormTitle)}</h2>
      <div class="decision-grid">${decisions}</div>
    </section>

    <section id="comparison">
      <div class="section-heading"><h2>${escapeHtml(content.comparisonTitle)}</h2><p>${escapeHtml(content.comparisonLead)}</p></div>
      <div class="comparison-tool">
        <div>
          <div class="segments">${approachButtons}</div>
          <p class="approach-summary"></p>
        </div>
        <div class="comparison-metrics">
          <div class="metric"><span>${escapeHtml(content.metrics.orderWrites)}</span><strong data-metric="orderWrites"></strong></div>
          <div class="metric"><span>${escapeHtml(content.metrics.kafkaWait)}</span><strong data-metric="kafkaWait"></strong></div>
          <div class="metric"><span>${escapeHtml(content.metrics.publicationRows)}</span><strong data-metric="publicationRows"></strong></div>
          <div class="metric"><span>${escapeHtml(content.metrics.reconciliation)}</span><strong data-metric="reconciliation"></strong></div>
          <div class="metric"><span>${escapeHtml(content.metrics.deduplication)}</span><strong data-metric="deduplication"></strong></div>
        </div>
      </div>
    </section>

    <section id="architecture">
      <div class="section-heading"><h2>${escapeHtml(content.architectureTitle)}</h2><p>${escapeHtml(content.architectureLead)}</p></div>
      <div class="architecture-board">${architectureLayers}</div>
      <ul class="flow-list">${architectureFlows}</ul>
    </section>

    <section id="simulation" data-source="${source}" data-baseline="${baseline}">
      <div class="section-heading"><h2>${escapeHtml(content.simulationTitle)}</h2><p>${escapeHtml(content.simulationLead)}</p></div>
      <div class="simulation-layout">
        <div class="scenario-list">${scenarioButtons}</div>
        <div class="simulation-workspace">
          <div class="scenario-header">
            <div><h3 class="scenario-title"></h3><p class="scenario-reason"></p></div>
            <div class="sim-controls">
              <button type="button" class="icon-button" data-action="previous" aria-label="${escapeHtml(content.controls.previous)}" title="${escapeHtml(content.controls.previous)}">←</button>
              <button type="button" class="icon-button" data-action="play" aria-label="${escapeHtml(content.controls.play)}" title="${escapeHtml(content.controls.play)}">▶</button>
              <button type="button" class="icon-button" data-action="reset" aria-label="${escapeHtml(content.controls.reset)}" title="${escapeHtml(content.controls.reset)}">↺</button>
              <button type="button" class="icon-button" data-action="next" aria-label="${escapeHtml(content.controls.next)}" title="${escapeHtml(content.controls.next)}">→</button>
            </div>
          </div>
          <div class="event-track"></div>
          <div class="event-detail">
            <div class="event-detail-head"><span data-current-step></span><span data-current-layer></span></div>
            <h4 data-event-key></h4>
            <p data-event-detail></p>
          </div>
          <div class="claim-race">
            <div class="claim-worker"><strong>relay-a</strong><span>CLAIMED</span></div>
            <div class="claim-worker"><strong>relay-b</strong><span>EMPTY</span></div>
          </div>
          <p class="claim-note" hidden>${escapeHtml(content.claimNote)}</p>
          <div class="sim-metrics">
            ${Object.entries(content.metricLabels).slice(0, 8).map(([key, label]) => `<div class="metric"><span>${escapeHtml(label)}</span><strong data-sim-metric="${key}"></strong></div>`).join('')}
          </div>
        </div>
      </div>
      <div class="legend">${layerLegend}</div>
    </section>

    <section id="lifecycle">
      <div class="section-heading"><h2>${escapeHtml(content.lifecycleTitle)}</h2><p>${escapeHtml(content.lifecycleLead)}</p></div>
      <div class="state-flow">${stateButtons}</div>
      <div class="state-detail">
        <strong data-state-name></strong>
        <div><span>${escapeHtml(content.stateLabels[0])}</span><p data-state-meaning></p></div>
        <div><span>${escapeHtml(content.stateLabels[1])}</span><p data-state-next></p></div>
      </div>
    </section>

    <section id="classes">
      <div class="section-heading"><h2>${escapeHtml(content.classesTitle)}</h2><p>${escapeHtml(content.classesLead)}</p></div>
      <div class="class-grid">${classCards}</div>
    </section>

    <section id="implementation">
      <div class="section-heading"><h2>${escapeHtml(content.implementationTitle)}</h2><p>${escapeHtml(content.implementationLead)}</p></div>
      <div class="implementation-grid">${implementationItems}</div>
    </section>

    <section id="run">
      <div class="section-heading"><h2>${escapeHtml(content.runTitle)}</h2><p>${escapeHtml(content.runLead)}</p></div>
      <div class="run-tool">
        <div class="run-tabs">${runTabs}</div>
        <div class="code-panel"><pre data-code-snippet="shell"><code data-run-code></code></pre><p class="run-note"></p></div>
      </div>
    </section>

    <section id="tests">
      <div class="section-heading"><h2>${escapeHtml(content.testsTitle)}</h2></div>
      <div class="table-wrap"><table><thead><tr><th>${escapeHtml(content.stateLabels[0])}</th><th class="identifier-heading">KafkaOutboxFallbackFlowTest</th></tr></thead><tbody>${tests}</tbody></table></div>
    </section>

    <section id="limits">
      <div class="section-heading"><h2>${escapeHtml(content.limitsTitle)}</h2></div>
      <div class="limits">${limits}</div>
    </section>
  </main>
  <footer>
    <div class="footer-links">
      <a href="${readmeHref}">${escapeHtml(content.sourceLinks[0])}</a>
      <a href="${sourceHref}">${escapeHtml(content.sourceLinks[1])}</a>
      <a href="${planHref}">${escapeHtml(content.sourceLinks[2])}</a>
      <a href="https://github.com/bluetape4k/bluetape4k-workshop">${escapeHtml(content.repoLabel)}</a>
    </div>
    <div>${escapeHtml(content.footer)}</div>
  </footer>
  <script>
    const content = ${JSON.stringify(content)};
    const approaches = ${JSON.stringify(approaches)};
    const scenarios = ${JSON.stringify(scenarios)};
    const publicationStates = ${JSON.stringify(publicationStates)};
    const themeStorageKey = 'starlight-theme';
    let activeApproach = 'transactional';
    let activeScenario = 'DIRECT_SUCCESS';
    let activeState = 'NO_ROW';
    let stepIndex = 0;
    let playTimer = null;

    function approachValue(key, value) {
      if (key === 'kafkaWait' || key === 'reconciliation') return value ? content.metrics.yes : content.metrics.no;
      if (key === 'publicationRows') return value === 'ALL' ? content.metrics.all : content.metrics.failures;
      if (key === 'deduplication') return value === 'REQUIRED' ? content.metrics.required : content.metrics.recommended;
      return String(value);
    }

    function renderApproach() {
      const approach = approaches[activeApproach];
      document.querySelector('[data-metric="orderWrites"]').textContent = approach.orderWrites;
      document.querySelector('[data-metric="kafkaWait"]').textContent = approachValue('kafkaWait', approach.kafkaWaitInRequest);
      document.querySelector('[data-metric="publicationRows"]').textContent = approachValue('publicationRows', approach.publicationRows);
      document.querySelector('[data-metric="reconciliation"]').textContent = approachValue('reconciliation', approach.reconciliation);
      document.querySelector('[data-metric="deduplication"]').textContent = approachValue('deduplication', approach.deduplication);
      document.querySelector('.approach-summary').textContent = content.approachSummaries[activeApproach];
      document.querySelectorAll('[data-approach]').forEach((button) => {
        button.setAttribute('aria-pressed', String(button.dataset.approach === activeApproach));
      });
    }

    function snapshotAt(scenario, index) {
      const snapshot = {
        apiStatus: '—',
        orderRows: 0,
        publicationRows: 0,
        kafkaEvents: 0,
        directAttempts: 0,
        relayRetries: 0,
        publicationStatus: 'NO ROW',
        claimOwner: null,
      };
      scenario.events.slice(0, index + 1).forEach(([, , changes]) => Object.assign(snapshot, changes));
      return snapshot;
    }

    function displayMetric(key, value) {
      if (value === '?') return content.metricLabels.unknown;
      if (value === null || value === '—') return content.metricLabels.none;
      return String(value);
    }

    function stopPlayback() {
      if (playTimer) clearInterval(playTimer);
      playTimer = null;
      const play = document.querySelector('[data-action="play"]');
      play.textContent = '▶';
      play.setAttribute('aria-label', content.controls.play);
      play.title = content.controls.play;
    }

    function renderSimulation() {
      const scenario = scenarios[activeScenario];
      const event = scenario.events[stepIndex];
      const snapshot = snapshotAt(scenario, stepIndex);
      document.querySelector('.scenario-title').textContent = content.scenarioNames[activeScenario];
      document.querySelector('.scenario-reason').textContent = content.scenarioReasons[activeScenario];
      document.querySelector('.event-track').innerHTML = scenario.events.map(([key, layer], index) =>
        '<div class="event-step layer-' + layer + ' ' + (index < stepIndex ? 'done' : '') + ' ' + (index === stepIndex ? 'current' : '') + '">' +
        '<i></i><span>' + (index + 1) + '</span><div>' + key.replaceAll('_', ' ') + '</div></div>'
      ).join('');
      document.querySelector('[data-current-step]').textContent = content.controls.step + ' ' + (stepIndex + 1) + ' / ' + scenario.events.length;
      document.querySelector('[data-current-layer]').textContent = content.controls.layer + ': ' + content.layerNames[event[1]];
      document.querySelector('[data-event-key]').textContent = event[0].replaceAll('_', ' ');
      document.querySelector('[data-event-detail]').textContent = content.eventDetails[event[0]];
      Object.entries(snapshot).forEach(([key, value]) => {
        const target = document.querySelector('[data-sim-metric="' + key + '"]');
        if (target) target.textContent = displayMetric(key, value);
      });
      const claimVisible = activeScenario === 'RELAY_RECOVERY' && event[0] === 'RELAY_CLAIM_RACE';
      document.querySelector('.claim-race').classList.toggle('visible', claimVisible);
      document.querySelector('.claim-note').hidden = !claimVisible;
      document.querySelector('[data-action="previous"]').disabled = stepIndex === 0;
      document.querySelector('[data-action="next"]').disabled = stepIndex === scenario.events.length - 1;
      document.querySelectorAll('[data-scenario]').forEach((button) => {
        button.setAttribute('aria-pressed', String(button.dataset.scenario === activeScenario));
      });
    }

    function selectScenario(id) {
      activeScenario = id;
      stepIndex = 0;
      stopPlayback();
      renderSimulation();
    }

    function nextStep() {
      const last = scenarios[activeScenario].events.length - 1;
      stepIndex = Math.min(stepIndex + 1, last);
      renderSimulation();
      if (stepIndex === last) stopPlayback();
    }

    function previousStep() {
      stepIndex = Math.max(stepIndex - 1, 0);
      stopPlayback();
      renderSimulation();
    }

    function resetScenario() {
      stepIndex = 0;
      stopPlayback();
      renderSimulation();
    }

    function togglePlayback() {
      if (matchMedia('(prefers-reduced-motion: reduce)').matches) {
        nextStep();
        return;
      }
      if (playTimer) {
        stopPlayback();
        return;
      }
      if (stepIndex === scenarios[activeScenario].events.length - 1) stepIndex = 0;
      const play = document.querySelector('[data-action="play"]');
      play.textContent = 'Ⅱ';
      play.setAttribute('aria-label', content.controls.pause);
      play.title = content.controls.pause;
      playTimer = setInterval(nextStep, 900);
      renderSimulation();
    }

    function renderState() {
      const description = content.stateDescriptions[activeState];
      document.querySelector('[data-state-name]').textContent = activeState.replace('_', ' ');
      document.querySelector('[data-state-meaning]').textContent = description[0];
      document.querySelector('[data-state-next]').textContent = description[1];
      document.querySelectorAll('[data-state]').forEach((button) => {
        button.setAttribute('aria-pressed', String(button.dataset.state === activeState));
      });
    }

    function renderRun(index) {
      document.querySelector('[data-run-code]').textContent = content.runCommands[index];
      document.querySelector('.run-note').textContent = content.runNotes[index];
      document.querySelectorAll('.run-tab').forEach((button) => {
        button.setAttribute('aria-pressed', String(Number(button.dataset.run) === index));
      });
    }

    document.querySelectorAll('[data-approach]').forEach((button) => button.addEventListener('click', () => {
      activeApproach = button.dataset.approach;
      renderApproach();
    }));
    document.querySelectorAll('[data-scenario]').forEach((button) => button.addEventListener('click', () => selectScenario(button.dataset.scenario)));
    document.querySelector('[data-action="previous"]').addEventListener('click', previousStep);
    document.querySelector('[data-action="next"]').addEventListener('click', nextStep);
    document.querySelector('[data-action="reset"]').addEventListener('click', resetScenario);
    document.querySelector('[data-action="play"]').addEventListener('click', togglePlayback);
    document.querySelectorAll('[data-state]').forEach((button) => button.addEventListener('click', () => {
      activeState = button.dataset.state;
      renderState();
    }));
    document.querySelectorAll('.run-tab').forEach((button) => button.addEventListener('click', () => renderRun(Number(button.dataset.run))));
    document.querySelector('.theme-toggle').addEventListener('click', () => {
      const next = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
      document.documentElement.dataset.theme = next;
      localStorage.setItem(themeStorageKey, next);
    });

    renderApproach();
    renderSimulation();
    renderState();
    renderRun(0);
  </script>
</body>
</html>`;
}

assertScenarios(scenarios);
Object.entries(scenarios).forEach(([id, scenario]) => assertFinalState(id, scenario));
for (const [locale, content] of Object.entries(locales)) {
  assertLocale(locale, content);
  await writeFile(path.join(root, outputs[locale]), html(locale, content));
}

console.log(`Generated Kafka Outbox Fallback Visual Companion: ${Object.values(outputs).join(', ')}`);
