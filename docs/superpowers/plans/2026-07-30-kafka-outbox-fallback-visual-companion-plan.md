# Kafka Outbox Fallback Visual Companion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 기존 Transactional Outbox와 Kafka-first Fallback을 비교한 뒤 Kafka 장애, relay, reconciliation을 직접 실행할 수 있는 한국어·영어 Visual Companion을 만든다.

**Completion:** PR #694에서 구현·검증한 뒤 `a6180d427b2dbb3586efdebdf33a3ef9ad0fd2e4`로 병합했다.

**Architecture:** 하나의 Node.js 생성 스크립트가 공통 시나리오 데이터, locale별 기술 문장, Theme 대응 HTML/CSS/JavaScript를 결합해 한국어와 영어 HTML을 생성한다. 생성된 문서는 외부 스크립트·스타일·네트워크 요청 없이 동작하며, 저장소 manifest와 기존 validator로 공개 범위와 보안 조건을 검증한다.

**Tech Stack:** Node.js ESM, self-contained HTML/CSS/JavaScript, Playwright, Gradle, Spring Boot 4, Kafka, PostgreSQL, Exposed

---

## 1. 실행 조건과 중단 지점

- 기준 브랜치: `develop`
- 구현 브랜치: `docs/kafka-outbox-fallback-visual-companion`
- 설계 커밋: `adacbaae`
- 설계 문서: `docs/superpowers/specs/2026-07-30-kafka-outbox-fallback-visual-companion-design.md`
- 대상 모듈: `:messaging-kafka-outbox-fallback`
- 작업 유형: Type E 문서 작업
- 애플리케이션 Kotlin 코드와 테스트는 변경하지 않는다.
- 외부 라이브러리와 npm 참조 라이브러리를 추가하지 않는다.
- 구현 결과는 로컬 브라우저에서 먼저 검토한다.
- 사용자 검토 전에는 구현 커밋, Push, PR 생성, 배포를 진행하지 않는다.
- PR #693의 Leader Job Safety Visual Companion은 현재 `develop`에 포함되지 않았다. 새 PR을 만들기
  전에 최신 `origin/develop`로 갱신하고 두 manifest 항목을 모두 보존한다.
- 병합은 PR 생성 및 CI 완료 이후 별도 승인을 받아야 한다.

## 2. 파일 구조

### 추가

- `scripts/generate-kafka-outbox-fallback-visual-companion.mjs`
  - 공통 시나리오 상태
  - 한국어·영어 콘텐츠
  - Theme 대응 HTML/CSS
  - 비교 UI, Architecture Diagram, 시뮬레이션, 상태 모델, Class Diagram
  - 실제 실행과 테스트 근거
  - 두 locale HTML 생성

- `docs/superpowers/specs/2026-07-30-kafka-outbox-fallback-visual-companion.html`
  - 한국어 Visual Companion

- `docs/superpowers/specs/2026-07-30-kafka-outbox-fallback-visual-companion.en.html`
  - 영어 Visual Companion

### 변경

- `docs/visual-companions/manifest.json`
  - `kafka-outbox-fallback` 문서와 한국어·영어 경로 등록

### 검토 후 필요한 경우에만 변경

- `scripts/validate-visual-companions.mjs`
  - 현재 generic 검증으로 새 문서를 검증할 수 없을 때만 보완한다.
  - 특정 문서의 문장이나 CSS selector를 validator에 하드코딩하지 않는다.

## 3. 구현 모델

### 3.1 공통 시나리오 자료형

생성 스크립트는 다음 구조를 사용한다.

```javascript
const scenarios = {
  DIRECT_SUCCESS: {
    events: [
      ['ORDER_STORED', 'orders', 'persistence'],
      ['TRANSACTION_COMMITTED', 'orders', 'persistence'],
      ['KAFKA_CONFIRMED', 'order-events', 'messaging'],
      ['API_PUBLISHED_DIRECT', 'PUBLISHED_DIRECT', 'api'],
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
};
```

각 event는 `[eventKey, stateKey, layerKey]`를 사용한다. locale별 설명은 `eventKey`로 조회한다.
상태 숫자와 단계 수는 locale 객체에 중복하지 않는다.

### 3.2 계층

```javascript
const layers = ['api', 'application', 'persistence', 'messaging', 'recovery'];
```

계층 색상은 CSS custom property로 정의한다.

```css
:root {
  --layer-api: #0f8b8d;
  --layer-application: #2563eb;
  --layer-persistence: #27864b;
  --layer-messaging: #d97706;
  --layer-recovery: #b33f8f;
  --state-failure: #c93b45;
}
```

Light·Dark Theme은 색상값을 별도로 정의하되 같은 의미의 계층에 같은 계열을 사용한다.

### 3.3 시나리오

정확히 다음 여섯 시나리오를 제공한다.

1. `DIRECT_SUCCESS`
2. `DIRECT_FAILURE`
3. `DIRECT_TIMEOUT`
4. `RELAY_RECOVERY`
5. `RELAY_DEAD_LETTER`
6. `FALLBACK_STORE_FAILURE`

`FALLBACK_STORE_FAILURE`의 후반 단계에 reconciliation을 포함한다. 별도 일곱 번째 시나리오를
만들지 않는다.

### 3.4 발행 상태

```javascript
const publicationStates = {
  NO_ROW: ['NOT_PUBLISHED'],
  NOT_PUBLISHED: ['CLAIMED'],
  CLAIMED: ['PUBLISHED', 'FAILED', 'DEAD_LETTER'],
  FAILED: ['CLAIMED'],
  PUBLISHED: [],
  DEAD_LETTER: [],
};
```

`CLAIMED`는 enum 값이 아니라 `claimedBy`와 `claimedUntil`이 설정된 처리 상태라고 화면에
명시한다.

## Task 1: 생성 스크립트 계약과 최소 HTML 출력

**Files:**
- Create: `scripts/generate-kafka-outbox-fallback-visual-companion.mjs`
- Create: `docs/superpowers/specs/2026-07-30-kafka-outbox-fallback-visual-companion.html`
- Create: `docs/superpowers/specs/2026-07-30-kafka-outbox-fallback-visual-companion.en.html`

- [x] **Step 1: 현재 기준 커밋과 source 파일을 고정한다**

Run:

```bash
git rev-parse origin/develop
test -f docs/superpowers/specs/2026-07-30-kafka-outbox-fallback-visual-companion-design.md
```

Expected:

- 40자 Git SHA 출력
- `test` exit code 0

생성 스크립트 상단에 다음 값을 정의한다.

```javascript
const baseline = '<git rev-parse origin/develop 결과>';
const source = '2026-07-30-kafka-outbox-fallback-visual-companion-design.md';
const outputs = {
  ko: 'docs/superpowers/specs/2026-07-30-kafka-outbox-fallback-visual-companion.html',
  en: 'docs/superpowers/specs/2026-07-30-kafka-outbox-fallback-visual-companion.en.html',
};
```

- [x] **Step 2: 출력 계약을 먼저 검사하는 assertion을 작성한다**

생성 스크립트에 다음 함수를 추가한다.

```javascript
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
```

- [x] **Step 3: assertion이 누락된 locale에서 실패하는지 확인한다**

임시로 `locales.en.description`을 생략하고 실행한다.

Run:

```bash
node scripts/generate-kafka-outbox-fallback-visual-companion.mjs
```

Expected:

```text
Error: Incomplete locale content: en
```

- [x] **Step 4: 두 locale의 최소 콘텐츠와 HTML shell을 구현한다**

필수 HTML:

```html
<!doctype html>
<html lang="${content.lang}">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="color-scheme" content="light dark">
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
  <style>/* theme tokens and layout */</style>
</head>
<body>
  <header>...</header>
  <main>
    <section id="overview">...</section>
    <section id="simulation"
      data-source="${source}"
      data-baseline="${baseline}">...</section>
  </main>
</body>
</html>
```

`localStorage.getItem(storageKey)`는 첫 `<style>`보다 앞에 있어야 한다.

- [x] **Step 5: 두 HTML을 생성하고 기본 계약을 확인한다**

Run:

```bash
node scripts/generate-kafka-outbox-fallback-visual-companion.mjs
rg -n '<html lang="(ko|en)"|id="simulation"|data-baseline="[0-9a-f]{40}"' \
  docs/superpowers/specs/2026-07-30-kafka-outbox-fallback-visual-companion*.html
```

Expected:

- 두 파일 생성
- 각 파일에 올바른 `lang`
- `#simulation`
- 40자 baseline SHA

## Task 2: 설계 비교와 문제 파악

**Files:**
- Modify: `scripts/generate-kafka-outbox-fallback-visual-companion.mjs`
- Regenerate: `docs/superpowers/specs/2026-07-30-kafka-outbox-fallback-visual-companion.html`
- Regenerate: `docs/superpowers/specs/2026-07-30-kafka-outbox-fallback-visual-companion.en.html`

- [x] **Step 1: 비교 데이터 계약을 작성한다**

```javascript
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
```

`orderWrites`는 측정된 성능값이 아니라 구현상 DB 입력 건수라고 설명한다.

- [x] **Step 2: 비교 UI의 초기 상태 검증 코드를 작성한다**

브라우저 스크립트에 다음 invariant를 사용한다.

```javascript
let activeApproach = 'transactional';

function renderApproach() {
  const approach = approaches[activeApproach];
  document.querySelector('[data-metric="orderWrites"]').textContent = approach.orderWrites;
  document.querySelectorAll('[data-approach]').forEach((button) => {
    button.setAttribute('aria-pressed', String(button.dataset.approach === activeApproach));
  });
}
```

- [x] **Step 3: 비교 UI와 문제 파악 섹션을 구현한다**

필수 화면:

- 예제 시나리오
- 기존 Transactional Outbox
- Kafka-first Fallback
- 검토 후 제외한 Redis Streams와 normal-path Outbox 유지
- 채택 조건
- 측정하지 않은 성능 수치를 표시하지 않는 주의 문구

한국어 핵심 문장:

```text
정상 처리에서는 orders만 저장하고 Kafka로 직접 발행한다.
Kafka 발행에 실패하면 event_publications에 재발행 대상을 저장한다.
타임아웃은 Kafka가 이벤트를 받지 않았다는 뜻이 아니다.
```

- [x] **Step 4: 접근 방식 전환을 검증한다**

Playwright 검사:

```javascript
await page.click('[data-approach="kafkaFirst"]');
await expect(page.locator('[data-metric="orderWrites"]')).toHaveText('1');
await expect(page.locator('[data-approach="kafkaFirst"]')).toHaveAttribute('aria-pressed', 'true');
await page.click('[data-approach="transactional"]');
await expect(page.locator('[data-metric="orderWrites"]')).toHaveText('2');
```

- [x] **Step 5: 생성 결과를 갱신한다**

Run:

```bash
node scripts/generate-kafka-outbox-fallback-visual-companion.mjs
```

Expected: exit code 0

## Task 3: Theme 대응 Architecture Diagram과 Class Diagram

**Files:**
- Modify: `scripts/generate-kafka-outbox-fallback-visual-companion.mjs`
- Regenerate: `docs/superpowers/specs/2026-07-30-kafka-outbox-fallback-visual-companion.html`
- Regenerate: `docs/superpowers/specs/2026-07-30-kafka-outbox-fallback-visual-companion.en.html`

- [x] **Step 1: Architecture Diagram 구성 요소를 데이터로 정의한다**

```javascript
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
```

연결 관계:

```javascript
const architectureEdges = [
  ['OrderController', 'PlaceOrderUseCase'],
  ['PlaceOrderUseCase', 'TransactionalOrderWriter'],
  ['TransactionalOrderWriter', 'PostgreSQL.orders'],
  ['PlaceOrderUseCase', 'OrderEventPublisher'],
  ['OrderEventPublisher', 'Kafka.order-events'],
  ['OrderEventPublisher', 'EventPublicationRepository'],
  ['EventPublicationRepository', 'PostgreSQL.event_publications'],
  ['EventPublicationRelay', 'PostgreSQL.event_publications'],
  ['EventPublicationRelay', 'Kafka.order-events'],
  ['PublicationReconciler', 'PostgreSQL.orders'],
  ['PublicationReconciler', 'PostgreSQL.event_publications'],
];
```

- [x] **Step 2: Architecture Card와 연결선을 구현한다**

DOM-native grid와 CSS connector를 사용한다. 연결선은 카드 내부와 계층 제목을 통과하지 않아야 한다.
작은 화면에서는 계층별 세로 흐름으로 전환한다.

카드 정렬:

```css
.architecture-card,
.class-card {
  min-height: 7.5rem;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
}
```

- [x] **Step 3: Class Diagram의 실제 사용 관계를 정의한다**

필수 클래스:

```javascript
const classes = [
  'OrderController',
  'PlaceOrderUseCase',
  'TransactionalOrderWriter',
  'OrderEventPublisher',
  'EventPublicationRepository',
  'EventPublicationRelay',
  'PublicationReconciler',
  'PublicationQueryService',
  'OutboxMetrics',
];
```

표시할 메서드는 설계 문서 12.2절과 일치해야 한다. 전체 생성자 인수와 private 메서드는 생략한다.

- [x] **Step 4: 한국어·영어와 Light·Dark Theme을 검증한다**

각 조합에서 다음 selector의 개수가 같아야 한다.

```javascript
await expect(page.locator('.architecture-card')).toHaveCount(10);
await expect(page.locator('.class-card')).toHaveCount(9);
```

각 카드의 내부 중심 오차를 계산한다.

```javascript
const errors = await page.locator('.architecture-card, .class-card').evaluateAll((cards) =>
  cards.map((card) => {
    const box = card.getBoundingClientRect();
    const content = card.firstElementChild.getBoundingClientRect();
    return Math.abs((box.top + box.bottom) / 2 - (content.top + content.bottom) / 2);
  })
);
if (Math.max(...errors) > 2) throw new Error(`Vertical centering error: ${Math.max(...errors)}px`);
```

- [x] **Step 5: 생성 결과를 갱신한다**

Run:

```bash
node scripts/generate-kafka-outbox-fallback-visual-companion.mjs
```

Expected: exit code 0

## Task 4: 장애 복구 시뮬레이션과 상태 모델

**Files:**
- Modify: `scripts/generate-kafka-outbox-fallback-visual-companion.mjs`
- Regenerate: `docs/superpowers/specs/2026-07-30-kafka-outbox-fallback-visual-companion.html`
- Regenerate: `docs/superpowers/specs/2026-07-30-kafka-outbox-fallback-visual-companion.en.html`

- [x] **Step 1: 여섯 시나리오의 최종 상태를 정의한다**

```javascript
const expectedFinals = {
  DIRECT_SUCCESS: ['PUBLISHED_DIRECT', 1, 0, 1, 1, 0, 'NO ROW'],
  DIRECT_FAILURE: ['FALLBACK_STORED', 1, 1, 0, 3, 0, 'NOT_PUBLISHED'],
  DIRECT_TIMEOUT: ['FALLBACK_STORED', 1, 1, '?', 3, 0, 'NOT_PUBLISHED'],
  RELAY_RECOVERY: ['FALLBACK_STORED', 1, 1, 1, 3, 0, 'PUBLISHED'],
  RELAY_DEAD_LETTER: ['FALLBACK_STORED', 1, 1, 0, 3, 3, 'DEAD_LETTER'],
  FALLBACK_STORE_FAILURE: ['FALLBACK_STORE_FAILED', 1, 1, 0, 3, 0, 'NOT_PUBLISHED'],
};
```

`?`는 불확실한 Kafka 수신 여부를 나타낸다. 숫자 `0`으로 바꾸지 않는다.

- [x] **Step 2: 최종 상태 assertion을 작성한다**

```javascript
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
```

- [x] **Step 3: 이전·다음·초기화·재생을 구현한다**

```javascript
let activeScenario = 'DIRECT_SUCCESS';
let stepIndex = 0;
let playTimer = null;

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
}

function previousStep() {
  stepIndex = Math.max(stepIndex - 1, 0);
  renderSimulation();
}

function resetScenario() {
  stepIndex = 0;
  stopPlayback();
  renderSimulation();
}
```

재생은 마지막 단계에서 정지하며 새 시나리오를 선택하면 timer를 해제한다. `prefers-reduced-motion`
사용자는 자동 재생 대신 단계 이동만 제공한다.

- [x] **Step 4: 동시 claim 비교를 relay 시나리오에 추가한다**

`RELAY_RECOVERY`에서 claim 단계가 선택되면 두 작업자를 표시한다.

```javascript
const claimWorkers = [
  { id: 'relay-a', result: 'CLAIMED' },
  { id: 'relay-b', result: 'EMPTY' },
];
```

화면에는 “한 작업자만 같은 row를 확보한다”와 “Kafka 발행 성공 후 DB 반영 전에 중단되면 중복
발행은 여전히 가능하다”를 함께 표시한다.

- [x] **Step 5: 대화형 상태 모델을 구현한다**

상태 버튼을 선택하면 다음 정보를 표시한다.

- enum 상태 여부
- 진입 조건
- 허용되는 다음 상태
- 운영 조치

`CLAIMED` 설명:

```text
EventPublicationStatus 값이 아니다. claimedBy와 claimedUntil이 유효한 동안의 처리 상태다.
```

- [x] **Step 6: 모든 시나리오의 마지막 단계를 검증한다**

Playwright 반복:

```javascript
for (const scenario of Object.keys(expectedFinals)) {
  await page.click(`[data-scenario="${scenario}"]`);
  while (await page.locator('[data-action="next"]').isEnabled()) {
    await page.click('[data-action="next"]');
  }
  await expect(page.locator('[data-metric="publicationStatus"]'))
    .toHaveText(expectedFinals[scenario][6]);
}
```

- [x] **Step 7: 생성 결과를 갱신한다**

Run:

```bash
node scripts/generate-kafka-outbox-fallback-visual-companion.mjs
```

Expected: exit code 0

## Task 5: 실제 구현, 실행 절차, 테스트 근거

**Files:**
- Modify: `scripts/generate-kafka-outbox-fallback-visual-companion.mjs`
- Regenerate: `docs/superpowers/specs/2026-07-30-kafka-outbox-fallback-visual-companion.html`
- Regenerate: `docs/superpowers/specs/2026-07-30-kafka-outbox-fallback-visual-companion.en.html`

- [x] **Step 1: 소스 경로와 클래스명을 다시 확인한다**

Run:

```bash
rg -n '^class |^data class |^enum class |^object ' \
  messaging/kafka-outbox-fallback/src/main/kotlin
```

Expected:

- `PlaceOrderUseCase`
- `TransactionalOrderWriter`
- `OrderEventPublisher`
- `EventPublicationRepository`
- `EventPublicationRelay`
- `PublicationReconciler`
- `OrderTable`
- `EventPublicationTable`

- [x] **Step 2: 설정값을 소스와 application.yml에서 확인한다**

Run:

```bash
rg -n 'direct-publish|relay-|reconciler-|max-payload|demo-admin' \
  messaging/kafka-outbox-fallback/src/main/resources/application.yml \
  messaging/kafka-outbox-fallback/src/main/kotlin
```

Expected: 설계 문서 14절의 설정 키와 기본값 일치

- [x] **Step 3: 실제 실행 명령을 검증한다**

관련 서비스:

```bash
docker run --rm --name kafka-outbox-postgres \
  -e POSTGRES_DB=postgres -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres -p 5432:5432 postgres:18-alpine
```

Kafka 명령은 현재 `application.yml`의 broker endpoint와 저장소의 Kafka 실행 방식에 맞춰 작성한다.
추측한 Docker 환경 변수나 image tag를 사용하지 않는다.

애플리케이션:

```bash
./gradlew :messaging-kafka-outbox-fallback:bootRun
```

API:

```bash
curl -s -X POST http://localhost:8080/api/orders \
  -H 'Content-Type: application/json' \
  -d '{"customerId":"customer-1001","product":"coffee-beans","quantity":2}'

curl -s http://localhost:8080/api/publications
```

- [x] **Step 4: demo admin endpoint 실행 조건을 표시한다**

```yaml
workshop:
  kafka-outbox-fallback:
    demo-admin-endpoints-enabled: true
```

```bash
curl -s -X POST http://localhost:8080/api/publications/relay
curl -s -X POST http://localhost:8080/api/publications/reconcile
```

운영 환경에는 인증, 권한, 요청 제한 없이 노출하지 않는다고 설명한다.

- [x] **Step 5: 실제 테스트명을 데이터로 추가한다**

Run:

```bash
rg -n '^    fun `' \
  messaging/kafka-outbox-fallback/src/test/kotlin/io/bluetape4k/workshop/messaging/fallback/KafkaOutboxFallbackFlowTest.kt
```

설계 문서 16절에 열거된 테스트를 한국어와 영어에서 같은 순서로 표시한다.

- [x] **Step 6: 집중 테스트 명령을 실제 Gradle 필터로 검증한다**

Run:

```bash
./gradlew :messaging-kafka-outbox-fallback:test \
  --tests 'io.bluetape4k.workshop.messaging.fallback.KafkaOutboxFallbackFlowTest' \
  --max-workers=1
```

Expected: `BUILD SUCCESSFUL`, 18 tests

README의 `--tests '*direct publish*'`처럼 JUnit display name과 동작 여부가 불확실한 wildcard는
검증 없이 사용하지 않는다.

- [x] **Step 7: 생성 결과를 갱신한다**

Run:

```bash
node scripts/generate-kafka-outbox-fallback-visual-companion.mjs
```

Expected: exit code 0

## Task 6: Manifest와 정적 검증

**Files:**
- Modify: `docs/visual-companions/manifest.json`
- Test: `scripts/validate-visual-companions.mjs`

- [x] **Step 1: validator가 신규 문서 없이 기존 상태에서 통과하는지 확인한다**

Run:

```bash
node scripts/validate-visual-companions.mjs
```

Expected:

```text
Visual companion validation passed: 2 documents / 4 locale files
```

현재 `develop` 문서 수가 달라졌다면 실제 숫자를 기록하고 원인을 확인한다.

- [x] **Step 2: manifest에 신규 문서를 추가한다**

```json
{
  "id": "kafka-outbox-fallback",
  "source": "docs/superpowers/specs/2026-07-30-kafka-outbox-fallback-visual-companion-design.md",
  "status": "approved",
  "public": true,
  "presentation": {
    "mode": "simulation",
    "defaultView": "simulation",
    "views": ["simulation"]
  },
  "locales": {
    "en": {
      "title": "Kafka Outbox Fallback",
      "html": "docs/superpowers/specs/2026-07-30-kafka-outbox-fallback-visual-companion.en.html"
    },
    "ko": {
      "title": "Kafka Outbox Fallback",
      "html": "docs/superpowers/specs/2026-07-30-kafka-outbox-fallback-visual-companion.html"
    }
  }
}
```

- [x] **Step 3: 정적 검증을 실행한다**

Run:

```bash
node scripts/validate-visual-companions.mjs
git diff --check
```

Expected:

```text
Visual companion validation passed: 3 documents / 6 locale files
```

PR #693이 먼저 병합돼 현재 문서 수가 3이면 예상 결과는 `4 documents / 8 locale files`다.

- [x] **Step 4: 생성 재현성을 확인한다**

Run:

```bash
node scripts/generate-kafka-outbox-fallback-visual-companion.mjs
git diff --exit-code -- \
  docs/superpowers/specs/2026-07-30-kafka-outbox-fallback-visual-companion.html \
  docs/superpowers/specs/2026-07-30-kafka-outbox-fallback-visual-companion.en.html
```

Expected: exit code 0

- [x] **Step 5: 금지된 외부 surface가 없는지 확인한다**

Run:

```bash
rg -n '<script[^>]+src=|<link[^>]+stylesheet|fetch\\(|XMLHttpRequest|WebSocket\\(|<form' \
  docs/superpowers/specs/2026-07-30-kafka-outbox-fallback-visual-companion*.html
```

Expected: no matches

## Task 7: 브라우저 기능·시각 검증

**Files:**
- Verify: `docs/superpowers/specs/2026-07-30-kafka-outbox-fallback-visual-companion.html`
- Verify: `docs/superpowers/specs/2026-07-30-kafka-outbox-fallback-visual-companion.en.html`
- Temporary output: `output/playwright/kafka-outbox-fallback/`

- [x] **Step 1: 로컬 서버를 시작한다**

Run:

```bash
python3 -m http.server 4318 --bind 127.0.0.1
```

Expected:

```text
Serving HTTP on 127.0.0.1 port 4318
```

4318이 사용 중이면 빈 포트를 선택하고 이후 명령에 같은 포트를 사용한다.

- [x] **Step 2: locale·Theme·viewport 조합을 캡처한다**

조합:

| Locale | Theme | Viewport |
|---|---|---|
| 한국어 | Light | 1440×1100 |
| 한국어 | Dark | 390×844 |
| 영어 | Light | 390×844 |
| 영어 | Dark | 1440×1100 |

각 화면을 전체 페이지로 캡처한다.

- [x] **Step 3: 기능 검증을 실행한다**

각 locale에서 확인:

- 두 Outbox 방식 전환
- 여섯 시나리오 선택
- 이전·다음·초기화·재생
- 상태 모델 선택
- 실행 탭 선택
- 한국어·영어 상호 링크
- Theme 전환과 `localStorage` 유지

Expected:

- console error 0
- failed page request 0
- 모든 selector가 단일 대상과 연결

- [x] **Step 4: overflow와 텍스트 중앙 정렬을 측정한다**

```javascript
const overflow = await page.evaluate(() => ({
  page: Math.max(0, document.documentElement.scrollWidth - innerWidth),
  elements: [...document.querySelectorAll('*')]
    .filter((element) => element.scrollWidth > element.clientWidth + 1)
    .map((element) => element.className || element.tagName),
}));
```

Expected:

- page overflow 0
- 의미 없는 element overflow 0
- Architecture/Class Card 수직 중심 오차 2px 이하

- [x] **Step 5: 전체 화면을 직접 검사한다**

확인:

- 카드 안에 카드가 중첩되지 않음
- 계층별 색상이 구분됨
- 긴 한국어·영어 문장이 카드 경계를 침범하지 않음
- 연결선이 카드, 계층 제목, 라벨을 통과하지 않음
- 상태·경고 색상이 Light·Dark Theme에서 모두 읽힘
- 모바일에서 비교 표와 시뮬레이션 조작이 잘리지 않음
- 다음 섹션 일부가 첫 viewport 하단에 보임

- [x] **Step 6: 시각 결함을 수정하고 검증을 반복한다**

수정할 때마다 다음을 다시 실행한다.

```bash
node scripts/generate-kafka-outbox-fallback-visual-companion.mjs
node scripts/validate-visual-companions.mjs
git diff --check
```

Stop condition:

- 네 조합의 시각 결함 0
- console error 0
- page overflow 0
- 카드 수직 중심 오차 2px 이하
- 모든 시나리오 최종 상태 일치

## Task 8: 전체 검증과 로컬 사용자 검토

**Files:**
- Verify all changed files

- [x] **Step 1: 전체 정적 검증을 실행한다**

Run:

```bash
node scripts/generate-kafka-outbox-fallback-visual-companion.mjs
node scripts/validate-visual-companions.mjs
git diff --check
```

Expected: all pass

- [x] **Step 2: 모듈 테스트를 실행한다**

Run:

```bash
./gradlew :messaging-kafka-outbox-fallback:test --max-workers=1
```

Expected: `BUILD SUCCESSFUL`, 18 tests

- [x] **Step 3: 변경 범위를 확인한다**

Run:

```bash
git status --short
git diff --stat
git diff -- \
  docs/visual-companions/manifest.json \
  scripts/generate-kafka-outbox-fallback-visual-companion.mjs
```

Expected:

- 설계·계획·생성 스크립트·두 HTML·manifest만 변경
- Kotlin 소스와 테스트 변경 없음

- [x] **Step 4: 로컬 검토 URL을 사용자에게 제공한다**

```text
http://127.0.0.1:4318/docs/superpowers/specs/2026-07-30-kafka-outbox-fallback-visual-companion.html
http://127.0.0.1:4318/docs/superpowers/specs/2026-07-30-kafka-outbox-fallback-visual-companion.en.html
```

함께 보고할 항목:

- 구현한 비교와 시나리오
- 한국어·영어, Light·Dark, 데스크톱·모바일 검증 결과
- 모듈 테스트 결과
- 알려진 제한
- 사용자 검토 전이므로 구현 diff가 아직 커밋되지 않았다는 사실

- [x] **Step 5: 사용자 검토 결과를 반영한다**

수정 요청이 있으면 생성 스크립트를 수정하고 Task 7과 Task 8 검증을 반복한다.

## Task 9: 승인 후 커밋과 PR 준비

**Files:**
- Commit all approved implementation files

- [x] **Step 1: 최신 `origin/develop`와 PR #693 상태를 확인한다**

Run:

```bash
git fetch origin
gh pr view 693 --json state,mergedAt,headRefOid,baseRefName
git log --oneline --decorate -5 origin/develop
```

PR #693이 병합됐다면 현재 브랜치를 최신 `origin/develop`에 rebase하고 manifest의 Leader Job
Safety 항목과 Kafka Outbox Fallback 항목을 모두 보존한다.

- [x] **Step 2: rebase 후 전체 검증을 다시 실행한다**

Run:

```bash
node scripts/generate-kafka-outbox-fallback-visual-companion.mjs
node scripts/validate-visual-companions.mjs
./gradlew :messaging-kafka-outbox-fallback:test --max-workers=1
git diff --check
```

Expected: all pass

- [x] **Step 3: Lore protocol로 구현을 커밋한다**

```bash
git add \
  docs/superpowers/plans/2026-07-30-kafka-outbox-fallback-visual-companion-plan.md \
  docs/superpowers/specs/2026-07-30-kafka-outbox-fallback-visual-companion.html \
  docs/superpowers/specs/2026-07-30-kafka-outbox-fallback-visual-companion.en.html \
  docs/visual-companions/manifest.json \
  scripts/generate-kafka-outbox-fallback-visual-companion.mjs

git commit -m "Show why Kafka-first publication needs durable recovery" \
  -m "Constraint: Compare transactional outbox before simulating Kafka-first failure recovery." \
  -m "Rejected: Lead with the outage simulator | It hides the normal-path trade-off." \
  -m "Confidence: high" \
  -m "Scope-risk: narrow" \
  -m "Directive: Keep timeout outcomes uncertain and preserve consumer deduplication warnings." \
  -m "Tested: visual companion validator; Playwright locale/theme/viewport QA; messaging-kafka-outbox-fallback tests" \
  -m "Not-tested: Production Kafka and PostgreSQL deployment behavior."
```

- [x] **Step 4: Push와 PR은 사용자가 요청한 범위에서만 진행한다**

PR을 만들 때:

- base: `develop`
- head: `docs/kafka-outbox-fallback-visual-companion`
- GitHub 제목과 본문: 영어
- 최종 `##` heading: `## DoD Status`
- PR 생성 후 exact Head, checks, reviews, mergeability를 확인
- 병합은 별도 승인 전까지 진행하지 않음

## 4. 계획 자체 검토

### Spec coverage

- 비교 UI: Task 2
- Architecture Diagram: Task 3
- Class Diagram: Task 3
- 여섯 장애·복구 시나리오: Task 4
- 상태 모델: Task 4
- Exposed와 PostgreSQL 설명: Task 3, Task 5
- 실제 실행: Task 5
- 테스트 근거: Task 5, Task 8
- 한/영·Theme·반응형·접근성: Task 1, Task 3, Task 7
- manifest와 validator: Task 6
- 로컬 사용자 검토: Task 8
- PR #693과 manifest 충돌 방지: Task 9

### Placeholder scan

- 미확정 항목, 후속 구현으로 미룬 항목, 검증되지 않은 성능 주장 없음
- 실제 Kafka Docker 실행 명령은 현재 repository 설정을 확인한 뒤 확정하도록 Task 5에 검증 절차를
  명시했다. 이는 placeholder가 아니라 잘못된 운영 명령을 방지하는 source verification 단계다.

### Type consistency

- 시나리오 ID는 Task 1, Task 4, Task 7에서 동일하다.
- 계층 ID는 `api`, `application`, `persistence`, `messaging`, `recovery`로 통일한다.
- 상태명은 `NO ROW`, `NOT_PUBLISHED`, `CLAIMED`, `FAILED`, `PUBLISHED`,
  `DEAD_LETTER`로 통일한다.
- locale별 파일명은 저장소의 기존 Visual Companion 규칙에 맞춰 한국어 기본 `.html`, 영어
  `.en.html`을 사용한다.
