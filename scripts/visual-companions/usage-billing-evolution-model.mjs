export const sourceBaseline = 'da7f2fe3c82c0052f19be8826b63e796907af969';
export const designSource = '2026-08-05-usage-billing-series-visualization-design.md';
export const viewIds = ['ledger', 'event-sourcing', 'microservices'];

export const invariantIds = [
  'SOURCE_EVENT_DEDUP',
  'OCCURRED_AT_PRICE',
  'IMMUTABLE_FINANCIAL_FACT',
  'RESTART_SAFE_RESULT',
  'LINKED_CORRECTION',
];

export const scenarioIds = {
  ledger: ['NORMAL', 'DUPLICATE', 'PRICE_GAP', 'CLOSE_RESTART', 'LATE_USAGE'],
  'event-sourcing': ['APPEND', 'REPLAY', 'SNAPSHOT_RESTORE', 'PROJECTION_REBUILD', 'POISON_EVENT'],
  microservices: ['DELIVERY', 'DUPLICATE', 'BROKER_OUTAGE', 'CONSUMER_RESTART', 'POISON_EVENT', 'CORRECTION'],
};

export const viewModels = {
  ledger: {
    accent: 'cyan',
    stages: [
      { id: 'ingestion', icon: '01', role: 'edge' },
      { id: 'pricing', icon: '02', role: 'policy' },
      { id: 'closing', icon: '03', role: 'process' },
      { id: 'ledger', icon: '04', role: 'authority' },
      { id: 'invoice', icon: '05', role: 'result' },
    ],
    invariants: ['SOURCE_EVENT_DEDUP', 'OCCURRED_AT_PRICE', 'RESTART_SAFE_RESULT', 'IMMUTABLE_FINANCIAL_FACT', 'LINKED_CORRECTION'],
  },
  'event-sourcing': {
    accent: 'violet',
    stages: [
      { id: 'command', icon: '01', role: 'edge' },
      { id: 'event-store', icon: '02', role: 'authority' },
      { id: 'snapshot', icon: '03', role: 'optimization' },
      { id: 'replay', icon: '04', role: 'process' },
      { id: 'projection', icon: '05', role: 'result' },
    ],
    invariants: ['SOURCE_EVENT_DEDUP', 'IMMUTABLE_FINANCIAL_FACT', 'RESTART_SAFE_RESULT', 'LINKED_CORRECTION'],
  },
  microservices: {
    accent: 'amber',
    stages: [
      { id: 'ingestion-service', icon: '01', role: 'edge' },
      { id: 'pricing-service', icon: '02', role: 'policy' },
      { id: 'billing-service', icon: '03', role: 'authority' },
      { id: 'invoicing-service', icon: '04', role: 'result' },
      { id: 'reconciliation-service', icon: '05', role: 'control' },
    ],
    invariants: ['SOURCE_EVENT_DEDUP', 'OCCURRED_AT_PRICE', 'IMMUTABLE_FINANCIAL_FACT', 'RESTART_SAFE_RESULT', 'LINKED_CORRECTION'],
  },
};

const scenario = (events, initial, final, authority, allowedActions) => ({
  events,
  initial,
  final,
  authority,
  allowedActions,
});

export const scenarios = {
  ledger: {
    NORMAL: scenario(
      ['usage-accepted', 'price-selected', 'batch-committed', 'invoice-finalized'],
      'period-open', 'invoice-sealed', 'postgresql-ledger', ['inspect-provenance'],
    ),
    DUPLICATE: scenario(
      ['command-replayed', 'source-event-deduplicated'],
      'retry-received', 'one-usage-row', 'command-receipt-and-source-unique', ['replay-response'],
    ),
    PRICE_GAP: scenario(
      ['usage-accepted', 'price-missing', 'validation-failed'],
      'close-running', 'close-failed-validation', 'price-schedule', ['repair-price-gap', 'resume-close'],
    ),
    CLOSE_RESTART: scenario(
      ['batch-selected', 'transaction-committed', 'worker-stopped', 'checkpoint-resumed'],
      'close-running', 'ready-to-finalize', 'close-run-checkpoint', ['process-next', 'finalize'],
    ),
    LATE_USAGE: scenario(
      ['usage-arrived-after-cutoff', 'finding-recorded', 'debit-adjustment-posted'],
      'invoice-finalized', 'linked-adjustment', 'append-only-ledger', ['inspect-finding', 'post-adjustment'],
    ),
  },
  'event-sourcing': {
    APPEND: scenario(
      ['command-received', 'stream-replayed', 'expected-version-checked', 'event-appended'],
      'stream-at-version-n', 'stream-at-version-n-plus-one', 'event-store', ['query-projection'],
    ),
    REPLAY: scenario(
      ['events-loaded', 'hash-chain-verified', 'upcast-applied', 'reducer-folded'],
      'empty-state', 'deterministic-state', 'event-store', ['compare-state-hash'],
    ),
    SNAPSHOT_RESTORE: scenario(
      ['snapshot-verified', 'tail-events-loaded', 'reducer-folded'],
      'verified-snapshot', 'deterministic-state', 'event-store', ['discard-invalid-snapshot', 'replay-genesis'],
    ),
    PROJECTION_REBUILD: scenario(
      ['watermark-captured', 'shadow-generation-built', 'lag-closed', 'alias-switched'],
      'generation-active', 'new-generation-active', 'event-store-and-fenced-generation', ['retire-old-generation', 'rollback-alias'],
    ),
    POISON_EVENT: scenario(
      ['decode-failed', 'event-quarantined', 'generation-failed'],
      'shadow-generation-building', 'healthy-active-preserved', 'event-store-and-quarantine', ['repair-codec', 'start-new-generation'],
    ),
  },
  microservices: {
    DELIVERY: scenario(
      ['usage-accepted', 'outbox-published', 'price-snapshotted', 'charge-posted', 'invoice-issued'],
      'usage-command', 'invoice-issued', 'service-owned-databases', ['trace-correlation'],
    ),
    DUPLICATE: scenario(
      ['message-redelivered', 'inbox-receipt-found', 'handler-skipped'],
      'broker-redelivery', 'one-domain-effect', 'consumer-inbox', ['acknowledge-message'],
    ),
    BROKER_OUTAGE: scenario(
      ['domain-commit', 'outbox-pending', 'relay-retried', 'event-published'],
      'broker-unavailable', 'delivery-recovered', 'transactional-outbox', ['observe-backlog', 'resume-relay'],
    ),
    CONSUMER_RESTART: scenario(
      ['message-claimed', 'consumer-stopped', 'lease-expired', 'message-reprocessed'],
      'handler-in-progress', 'one-domain-effect', 'consumer-inbox-and-domain-db', ['reclaim-message'],
    ),
    POISON_EVENT: scenario(
      ['handler-rejected', 'quarantine-recorded', 'unrelated-stream-progressed'],
      'invalid-event', 'isolated-failure', 'consumer-quarantine', ['submit-redrive-request'],
    ),
    CORRECTION: scenario(
      ['finding-created', 'repair-command-sent', 'adjustment-posted', 'invoice-view-updated'],
      'cross-service-mismatch', 'linked-correction', 'billing-ledger', ['inspect-finding', 'trace-correction'],
    ),
  },
};

const commonKo = {
  themeLabel: '테마 전환',
  sourceLabel: '설계 문서',
  scenarioLabel: '검증할 시나리오',
  authorityLabel: '최종 권위',
  initialLabel: '시작 상태',
  finalLabel: '관찰 결과',
  actionLabel: '허용된 후속 조치',
  invariantsTitle: '구현 방식이 바뀌어도 유지할 불변식',
  roleNames: { edge: '입력 경계', policy: '정책', process: '재시작 작업', authority: '기록 권위', result: '확정 결과', optimization: '선택적 최적화', control: '검증·복구' },
  invariants: {
    SOURCE_EVENT_DEDUP: ['원천 이벤트 중복 방지', '재시도 횟수와 무관하게 업무 사실은 한 번만 남긴다.'],
    OCCURRED_AT_PRICE: ['발생 시각의 가격', '청구 시각이 아니라 사용 시각의 가격 구간을 선택한다.'],
    IMMUTABLE_FINANCIAL_FACT: ['확정 금액 불변', '확정된 금액은 수정하지 않고 새 보정 기록으로 연결한다.'],
    RESTART_SAFE_RESULT: ['재시작 안전성', '중단 지점부터 다시 실행해도 최종 결과가 같아야 한다.'],
    LINKED_CORRECTION: ['연결된 보정', '늦거나 잘못된 결과는 원본을 가리키는 조정으로 남긴다.'],
  },
};

const commonEn = {
  themeLabel: 'Toggle theme',
  sourceLabel: 'Design source',
  scenarioLabel: 'Scenario to verify',
  authorityLabel: 'Final authority',
  initialLabel: 'Initial state',
  finalLabel: 'Observed result',
  actionLabel: 'Allowed follow-up',
  invariantsTitle: 'Invariants that survive each architecture choice',
  roleNames: { edge: 'Input boundary', policy: 'Policy', process: 'Resumable work', authority: 'Record authority', result: 'Final result', optimization: 'Optional optimization', control: 'Verification and repair' },
  invariants: {
    SOURCE_EVENT_DEDUP: ['Source-event deduplication', 'A business fact is stored once regardless of retry count.'],
    OCCURRED_AT_PRICE: ['Occurrence-time pricing', 'Select the price interval at usage time, not billing time.'],
    IMMUTABLE_FINANCIAL_FACT: ['Immutable finalized money', 'Correct finalized amounts with linked records instead of mutation.'],
    RESTART_SAFE_RESULT: ['Restart-safe outcome', 'Resuming from a checkpoint must converge on the same final result.'],
    LINKED_CORRECTION: ['Linked correction', 'Late or incorrect results point back to their original record.'],
  },
};

export const locales = {
  ko: {
    ...commonKo,
    lang: 'ko',
    title: '사용량 이벤트를 보정 가능한 청구서로 확정하기',
    description: '같은 과금 요구사항을 원장형 기준선, Event Sourcing, 다섯 마이크로서비스로 구현했을 때 데이터 권위와 복구 비용이 어떻게 달라지는지 비교합니다.',
    kicker: 'SaaS Usage Billing · Interactive Architecture',
    alternateLabel: 'English',
    alternateHref: '../en/usage-billing-evolution.html',
    viewNames: { ledger: '원장형 기준선', 'event-sourcing': 'Event Sourcing', microservices: '다섯 서비스' },
    viewSummaries: {
      ledger: 'PostgreSQL 하나에서 중복 수집, 발생 시각 가격, 재시작 가능한 마감, 불변 원장과 청구서를 함께 지킵니다.',
      'event-sourcing': '모든 업무 사실을 이벤트로 보존하고 replay와 projection generation으로 현재 상태를 다시 만듭니다.',
      microservices: '서비스별 데이터 권위를 분리하고 Outbox·Inbox·격리·보정 명령으로 분산 실패를 흡수합니다.',
    },
    stageNames: {
      ingestion: ['사용량 수집', '명령 Receipt + 원천 이벤트 Unique'], pricing: ['가격 선택', '발생 시각 기준 반개구간'], closing: ['월 마감', 'Cutoff + Keyset Checkpoint'], ledger: ['불변 원장', 'CHARGE와 연결된 보정'], invoice: ['청구서', '원장 Provenance와 합계 검증'],
      command: ['명령 처리', 'Replay 후 전이 판단'], 'event-store': ['Event Store', 'Expected Version + Hash Chain'], snapshot: ['Snapshot', '검증 가능한 선택적 Seed'], replay: ['Replay', 'Upcast + 순수 Reducer'], projection: ['Projection', 'Fenced Generation 전환'],
      'ingestion-service': ['Ingestion', 'Source Unique + Outbox'], 'pricing-service': ['Pricing', '불변 가격 Snapshot'], 'billing-service': ['Billing', 'Period + Ledger 권위'], 'invoicing-service': ['Invoicing', 'Invoice Read Model'], 'reconciliation-service': ['Reconciliation', 'Finding + Repair Command'],
    },
    scenarioNames: {
      NORMAL: '정상 월 마감', DUPLICATE: '중복 수집', PRICE_GAP: '가격 구간 누락', CLOSE_RESTART: '마감 도중 재시작', LATE_USAGE: '마감 후 늦은 사용량',
      APPEND: '낙관적 이벤트 추가', REPLAY: '전체 Replay', SNAPSHOT_RESTORE: 'Snapshot 복원', PROJECTION_REBUILD: '무중단 Projection 재구축', POISON_EVENT: '처리 불가능한 이벤트',
      DELIVERY: '정상 서비스 간 전달', BROKER_OUTAGE: 'Broker 장애', CONSUMER_RESTART: 'Consumer 재시작', CORRECTION: '서비스 간 보정',
    },
  },
  en: {
    ...commonEn,
    lang: 'en',
    title: 'Turn Usage Events into Correctable Invoices',
    description: 'Compare how a ledger baseline, Event Sourcing, and five microservices preserve the same billing invariants while changing data authority and recovery cost.',
    kicker: 'SaaS Usage Billing · Interactive Architecture',
    alternateLabel: '한국어',
    alternateHref: '../ko/usage-billing-evolution.html',
    viewNames: { ledger: 'Ledger baseline', 'event-sourcing': 'Event Sourcing', microservices: 'Five services' },
    viewSummaries: {
      ledger: 'One PostgreSQL authority preserves deduplication, occurrence-time pricing, resumable closing, an immutable ledger, and invoices.',
      'event-sourcing': 'Every business fact remains an event; replay and projection generations reconstruct current state.',
      microservices: 'Service-owned authorities absorb distributed failure through Outbox, Inbox, quarantine, and correction commands.',
    },
    stageNames: {
      ingestion: ['Usage ingestion', 'Command receipt + source-event unique'], pricing: ['Price selection', 'Occurrence-time half-open interval'], closing: ['Period close', 'Cutoff + keyset checkpoint'], ledger: ['Immutable ledger', 'Charges and linked corrections'], invoice: ['Invoice', 'Ledger provenance and total check'],
      command: ['Command handling', 'Replay before transition'], 'event-store': ['Event store', 'Expected version + hash chain'], snapshot: ['Snapshot', 'Verified optional seed'], replay: ['Replay', 'Upcast + pure reducer'], projection: ['Projection', 'Fenced generation switch'],
      'ingestion-service': ['Ingestion', 'Source unique + Outbox'], 'pricing-service': ['Pricing', 'Immutable price snapshot'], 'billing-service': ['Billing', 'Period + ledger authority'], 'invoicing-service': ['Invoicing', 'Invoice read model'], 'reconciliation-service': ['Reconciliation', 'Finding + repair command'],
    },
    scenarioNames: {
      NORMAL: 'Normal period close', DUPLICATE: 'Duplicate ingestion', PRICE_GAP: 'Missing price interval', CLOSE_RESTART: 'Restart during close', LATE_USAGE: 'Usage arriving after close',
      APPEND: 'Optimistic event append', REPLAY: 'Full replay', SNAPSHOT_RESTORE: 'Snapshot restore', PROJECTION_REBUILD: 'Online projection rebuild', POISON_EVENT: 'Poison event',
      DELIVERY: 'Normal cross-service delivery', BROKER_OUTAGE: 'Broker outage', CONSUMER_RESTART: 'Consumer restart', CORRECTION: 'Cross-service correction',
    },
  },
};

