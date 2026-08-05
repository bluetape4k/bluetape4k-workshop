# SaaS 사용량 과금 시리즈와 통합 Visualization 구현 계획

> **실행 지침:** `executing-plans`를 사용해 이 계획을 Task 단위로 실행한다. 진행 상태는 체크박스(`- [ ]`)로 추적한다.

**Goal:** 원장형 과금, Event Sourcing, 마이크로서비스 분리를 같은 시나리오로 비교하는 한국어·영어 Visualization을 workshop에 만들고, 이를 연결한 Part 1 한국어·영어 글을 사이트에서 검증한다.

**Architecture:** `bluetape4k-workshop`이 Visualization HTML과 결정적 PNG의 원본을 소유한다. workshop 원본이 병합된 뒤 `bluetape4k.github.io`가 병합 SHA를 고정해 HTML Snapshot을 만들고, Locale별 Part 1 글에서 `#ledger` 화면으로 연결한다. 두 저장소는 별도 Worktree와 커밋을 사용하며, Site 단계는 Workshop Source Merge SHA가 확정된 뒤에만 시작한다.

**Tech Stack:** Node.js ESM, 정적 HTML/CSS/JavaScript, Google Chrome Headless, JSON Manifest, Astro 6, Starlight, MDX

---

## 파일 구조

### Workshop 원본 단계

| 파일 | 책임 |
|---|---|
| `scripts/visual-companions/usage-billing-evolution-model.mjs` | 화면·시나리오·단계·결과의 안정 ID와 Locale 문구를 한곳에서 소유한다. |
| `scripts/generate-usage-billing-evolution-visual-companion.mjs` | 공통 Model을 한국어·영어 독립 HTML로 결정적으로 생성한다. |
| `scripts/validate-usage-billing-evolution-visual-companion.mjs` | Locale 동등성, Fragment, Scenario 결과, Source Link와 Capture 계약을 검증한다. |
| `scripts/capture-usage-billing-evolution-visual-companion.mjs` | Chrome 입력을 고정해 12개 PNG를 두 번 Capture하고 Hash가 같은지 검증한다. |
| `docs/visual-companions/en/usage-billing-evolution.html` | 영어 대화형 Visualization 원본이다. |
| `docs/visual-companions/ko/usage-billing-evolution.html` | 한국어 대화형 Visualization 원본이다. |
| `docs/images/visual-companions/usage-billing-evolution-*.png` | 세 화면 × 두 Locale × Light/Dark의 결정적 대체 이미지다. |
| `docs/visual-companions/manifest.json` | 공개 문서 ID, Source 설계서, Presentation과 Locale Path를 등록한다. |
| `docs/lessons/2026-08-05-usage-billing-visualization-boundaries.md` | 구현에서 확인한 재사용 가능한 시각 자료·게시 경계를 기록한다. |

### Site 게시 단계

| 파일 | 책임 |
|---|---|
| `src/data/visual-companions/repositories.json` | Workshop의 병합 SHA를 Source Ref로 고정한다. |
| `src/data/visual-companions/bluetape4k-workshop.snapshot.json` | Sync 도구가 생성하는 검증된 문서 Snapshot이다. |
| `public/visual-companions/bluetape4k-workshop/usage-billing-evolution/index.html` | 영어 공개 Visualization이다. |
| `public/ko/visual-companions/bluetape4k-workshop/usage-billing-evolution/index.html` | 한국어 공개 Visualization이다. |
| `scripts/copy-usage-billing-evolution-assets.mjs` | 고정 Source Ref의 12개 PNG를 Site Asset 경로로 복사하고 Hash를 기록한다. |
| `public/assets/blog/usage-billing/part1/usage-billing-evolution-*.png` | Part 1에 Embed하는 Locale·Theme별 Workflow 이미지다. |
| `public/assets/blog/usage-billing/part1/usage-billing-part1-hero.png` | Locale이 공유하는 Text-free Hero 이미지다. |
| `src/content/docs/ko/blog/usage-billing-part1-ledger-and-resumable-close.mdx` | 한국어 Part 1 글이다. |
| `src/content/docs/blog/usage-billing-part1-ledger-and-resumable-close.mdx` | 영어 Part 1 글이다. |
| `tests/visual-companions/usage-billing-blog-link.test.mjs` | Locale별 글, PNG와 Visualization Fragment 연결을 검증한다. |
| `docs/lessons/2026-08-05-usage-billing-part1-source-snapshot.md` | Source Merge 이후 Site Snapshot·글 연결에서 얻은 교훈을 기록한다. |

## 실행 원칙

- 이 계획은 기존 사용자 지침에 따라 현재 작업에서 순차 실행한다. 별도 하위 에이전트를 사용하지 않는다.
- Workshop Source 단계가 검증·커밋되기 전에는 Site 파일을 수정하지 않는다.
- Site 단계는 Workshop PR 병합 SHA가 확인되기 전까지 `PENDING`이다.
- PR 생성, 병합과 배포는 계획 실행과 별도의 권한 경계다.
- HTML은 Network 요청, 외부 Font, 시간, 난수와 환경별 문구를 사용하지 않는다.
- 원장형·Event Sourcing·마이크로서비스 화면은 같은 Scenario ID와 불변식 명칭을 사용한다.

### Task 1: Workshop Manifest에서 실패하는 문서 계약을 먼저 고정한다

**Files:**

- Modify: `docs/visual-companions/manifest.json`
- Test: `scripts/validate-visual-companions.mjs`

- [x] **Step 1: Manifest에 아직 존재하지 않는 Locale HTML을 등록한다**

`documents` 마지막에 다음 문서를 추가한다.

```json
{
  "id": "usage-billing-evolution",
  "source": "docs/superpowers/specs/2026-08-05-usage-billing-series-visualization-design.md",
  "status": "approved",
  "public": true,
  "presentation": {
    "mode": "simulation",
    "defaultView": "simulation",
    "views": ["simulation"]
  },
  "locales": {
    "en": {
      "title": "Usage Billing Evolution",
      "html": "docs/visual-companions/en/usage-billing-evolution.html"
    },
    "ko": {
      "title": "SaaS 사용량 과금의 발전 단계",
      "html": "docs/visual-companions/ko/usage-billing-evolution.html"
    }
  }
}
```

- [x] **Step 2: 기존 Validator가 누락된 HTML을 거부하는지 확인한다**

Run:

```bash
node scripts/validate-visual-companions.mjs
```

Expected: FAIL. 다음 두 오류가 포함되어야 한다.

```text
usage-billing-evolution.en.html does not exist
usage-billing-evolution.ko.html does not exist
```

- [x] **Step 3: 실패 상태를 커밋하지 않고 Task 2로 진행한다**

Manifest 변경은 Task 2의 생성 파일과 한 커밋에 포함한다. RED 증거만 실행 기록에 남긴다.

### Task 2: 하나의 구조화 Model에서 한국어·영어 HTML을 생성한다

**Files:**

- Create: `scripts/visual-companions/usage-billing-evolution-model.mjs`
- Create: `scripts/generate-usage-billing-evolution-visual-companion.mjs`
- Create: `docs/visual-companions/en/usage-billing-evolution.html`
- Create: `docs/visual-companions/ko/usage-billing-evolution.html`
- Modify: `docs/visual-companions/manifest.json`
- Test: `scripts/validate-visual-companions.mjs`

- [x] **Step 1: Model의 안정 ID와 공통 불변식을 정의한다**

`usage-billing-evolution-model.mjs`는 다음 상수를 Export한다.

```javascript
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
```

각 Scenario는 `events`, `initial`, `final`, `authority`, `allowedActions`를 갖는다. `events`의 모든 상태 변경은 실제 README·Service·Integration Test에서 확인한 결과만 사용한다.

- [x] **Step 2: Locale 문구가 같은 구조를 사용하도록 정의한다**

```javascript
export const locales = {
  en: {
    lang: 'en',
    title: 'From Usage Events to Correctable Invoices',
    alternateLabel: '한국어',
    alternateHref: '../ko/usage-billing-evolution.html',
    viewNames: {
      ledger: 'Ledger baseline',
      'event-sourcing': 'Event Sourcing',
      microservices: 'Five services',
    },
  },
  ko: {
    lang: 'ko',
    title: '사용량 이벤트를 보정 가능한 청구서로 확정하기',
    alternateLabel: 'English',
    alternateHref: '../en/usage-billing-evolution.html',
    viewNames: {
      ledger: '원장형 기준선',
      'event-sourcing': 'Event Sourcing',
      microservices: '다섯 서비스',
    },
  },
};
```

실제 상대 경로는 생성된 두 HTML의 디렉터리 깊이를 기준으로 검증한다. Site Sync가 GitHub Source Link와 Locale Route를 다시 쓰므로 Mutable Branch URL을 HTML에 직접 넣지 않는다.

- [x] **Step 3: Generator가 독립 실행 가능한 HTML을 만들게 한다**

Generator는 `renderDocument(locale)`과 `writeDocument(locale)`을 제공하고 직접 실행 시 두 Locale을 생성한다.

```javascript
for (const locale of ['en', 'ko']) {
  await writeDocument(locale, renderDocument(locale));
}
```

생성된 HTML은 다음 계약을 포함한다.

```html
<!doctype html>
<html lang="ko">
<meta name="color-scheme" content="light dark">
<section id="simulation"
  data-source="2026-08-05-usage-billing-series-visualization-design.md"
  data-baseline="da7f2fe3c82c0052f19be8826b63e796907af969">
  <nav aria-label="구현 단계">
    <button data-view-id="ledger" aria-pressed="true">원장형 기준선</button>
    <button data-view-id="event-sourcing" aria-pressed="false">Event Sourcing</button>
    <button data-view-id="microservices" aria-pressed="false">다섯 서비스</button>
  </nav>
  <div id="workflow-capture" data-active-view="ledger"></div>
</section>
```

초기화 코드는 `location.hash`를 읽고 `viewIds`에 없는 값은 `ledger`로 바꾼다. Button 선택은 Hash를 갱신하고 `hashchange`는 Browser Back/Forward 상태를 복원한다.

- [x] **Step 4: Theme·Capture·접근성 계약을 구현한다**

Theme 우선순위는 Capture Query, 저장된 `starlight-theme`, 시스템 Theme 순이다.

```javascript
const params = new URLSearchParams(location.search);
const captureTheme = params.get('theme');
const savedTheme = localStorage.getItem('starlight-theme');
const resolvedTheme = captureTheme === 'light' || captureTheme === 'dark'
  ? captureTheme
  : savedTheme === 'light' || savedTheme === 'dark'
    ? savedTheme
    : matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
document.documentElement.dataset.theme = resolvedTheme;
document.documentElement.dataset.capture = params.get('capture') === '1' ? 'true' : 'false';
```

`capture=1`이면 Header·Footer·설명 Section을 숨기고 `#workflow-capture`를 포함한 비교 화면만 1440×900 Viewport 안에 표시한다. `prefers-reduced-motion`과 Capture Mode에서는 Animation과 Transition을 제거한다. 모든 선택기는 Button, `aria-pressed`, Focus Ring을 사용한다.

- [x] **Step 5: Generator를 두 번 실행해 결정적 Source 생성을 확인한다**

Run:

```bash
node scripts/generate-usage-billing-evolution-visual-companion.mjs
shasum -a 256 docs/visual-companions/{en,ko}/usage-billing-evolution.html > /tmp/usage-billing-html-first.sha256
node scripts/generate-usage-billing-evolution-visual-companion.mjs
shasum -a 256 docs/visual-companions/{en,ko}/usage-billing-evolution.html > /tmp/usage-billing-html-second.sha256
diff -u /tmp/usage-billing-html-first.sha256 /tmp/usage-billing-html-second.sha256
```

Expected: `diff` output 없음.

- [x] **Step 6: 공통 Visual Companion Validator를 통과한다**

Run:

```bash
node scripts/validate-visual-companions.mjs
```

Expected:

```text
Visual companion validation passed: 5 documents / 10 locale files
```

- [x] **Step 7: HTML과 Manifest를 커밋한다**

```bash
git add \
  scripts/visual-companions/usage-billing-evolution-model.mjs \
  scripts/generate-usage-billing-evolution-visual-companion.mjs \
  docs/visual-companions/en/usage-billing-evolution.html \
  docs/visual-companions/ko/usage-billing-evolution.html \
  docs/visual-companions/manifest.json
git commit
```

Commit intent: `Make billing architecture choices comparable through one reproducible scenario`

### Task 3: Scenario와 Locale 동등성을 자동 검증한다

**Files:**

- Create: `scripts/validate-usage-billing-evolution-visual-companion.mjs`
- Modify: `scripts/visual-companions/usage-billing-evolution-model.mjs`
- Test: `scripts/validate-usage-billing-evolution-visual-companion.mjs`

- [x] **Step 1: 의도적으로 잘못된 Model Fixture가 실패하도록 Validator를 작성한다**

Validator는 다음 함수를 Export한다.

```javascript
export function validateUsageBillingModel({ viewIds, scenarioIds, scenarios, locales }) {
  const errors = [];
  for (const viewId of viewIds) {
    if (!locales.en.viewNames[viewId] || !locales.ko.viewNames[viewId]) {
      errors.push(`missing localized view: ${viewId}`);
    }
    for (const scenarioId of scenarioIds[viewId]) {
      const scenario = scenarios[viewId]?.[scenarioId];
      if (!scenario || scenario.events.length === 0) errors.push(`missing scenario: ${viewId}/${scenarioId}`);
      if (!scenario?.final || !scenario?.authority) errors.push(`incomplete outcome: ${viewId}/${scenarioId}`);
    }
  }
  return errors;
}
```

Script 내부의 최소 실패 Fixture에서 `ledger/NORMAL`을 제거하고 `missing scenario: ledger/NORMAL`이 발생하는지 먼저 확인한다.

- [x] **Step 2: HTML 구조·Fragment·Source Link 검사를 추가한다**

두 HTML 각각에 대해 다음 값을 검사한다.

```javascript
const required = [
  'id="simulation"',
  'id="workflow-capture"',
  'data-view-id="ledger"',
  'data-view-id="event-sourcing"',
  'data-view-id="microservices"',
  'window.addEventListener(\'hashchange\'',
  'document.fonts.ready',
  'window.__USAGE_BILLING_READY__ = true',
];
```

Locale별 HTML은 상대 Locale Link와 설계 문서 Link를 포함해야 한다. `fetch(`, 외부 Script·Stylesheet·Media URL, WebSocket, Form은 0개여야 한다.

- [x] **Step 3: 정상 Model과 생성 HTML을 검증한다**

Run:

```bash
node scripts/validate-usage-billing-evolution-visual-companion.mjs
```

Expected:

```text
Usage billing visualization validation passed: views=3 scenarios=16 locales=2
```

- [x] **Step 4: Validator와 필요한 Model 보정을 커밋한다**

```bash
git add \
  scripts/validate-usage-billing-evolution-visual-companion.mjs \
  scripts/visual-companions/usage-billing-evolution-model.mjs \
  docs/visual-companions/en/usage-billing-evolution.html \
  docs/visual-companions/ko/usage-billing-evolution.html
git commit
```

Commit intent: `Keep every billing scenario and locale bound to the same source model`

### Task 4: 12개 PNG를 결정적으로 Capture한다

**Files:**

- Create: `scripts/capture-usage-billing-evolution-visual-companion.mjs`
- Create: `docs/images/visual-companions/usage-billing-evolution-ledger.en.light.png`
- Create: `docs/images/visual-companions/usage-billing-evolution-ledger.en.dark.png`
- Create: `docs/images/visual-companions/usage-billing-evolution-ledger.ko.light.png`
- Create: `docs/images/visual-companions/usage-billing-evolution-ledger.ko.dark.png`
- Create: `docs/images/visual-companions/usage-billing-evolution-event-sourcing.en.light.png`
- Create: `docs/images/visual-companions/usage-billing-evolution-event-sourcing.en.dark.png`
- Create: `docs/images/visual-companions/usage-billing-evolution-event-sourcing.ko.light.png`
- Create: `docs/images/visual-companions/usage-billing-evolution-event-sourcing.ko.dark.png`
- Create: `docs/images/visual-companions/usage-billing-evolution-microservices.en.light.png`
- Create: `docs/images/visual-companions/usage-billing-evolution-microservices.en.dark.png`
- Create: `docs/images/visual-companions/usage-billing-evolution-microservices.ko.light.png`
- Create: `docs/images/visual-companions/usage-billing-evolution-microservices.ko.dark.png`
- Test: `scripts/capture-usage-billing-evolution-visual-companion.mjs`

- [x] **Step 1: Chrome 실행 환경을 고정한다**

Capture Script는 다음 값을 사용한다.

```javascript
const chrome = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const windowSize = '1440,900';
const locales = ['en', 'ko'];
const themes = ['light', 'dark'];
const views = ['ledger', 'event-sourcing', 'microservices'];
```

Chrome Version을 실행 로그에 출력하고 임시 User Data Directory를 `mkdtemp()`로 만든다. Network를 사용하지 않는 `file://` URL에 Capture Theme과 View ID를 조합한 Query·Fragment를 붙인다.

- [x] **Step 2: 같은 입력을 두 디렉터리에 Capture한다**

각 조합에 다음 인수를 사용한다.

```text
--headless=new
--hide-scrollbars
--force-device-scale-factor=1
--window-size=1440,900
--disable-background-networking
--disable-default-apps
--disable-extensions
--disable-sync
--metrics-recording-only
--no-first-run
--screenshot=/tmp/usage-billing-capture/ledger.en.dark.png
```

첫 번째·두 번째 Capture는 서로 다른 임시 User Data Directory를 사용한다.

- [x] **Step 3: Dimensions와 SHA-256이 같은지 확인한다**

Script는 PNG Header에서 `1440×900`을 읽고 두 Capture의 SHA-256을 비교한다. 다르면 파일을 복사하지 않고 다음 형식으로 실패한다.

```text
capture drift: locale=ko theme=dark view=ledger first=FIRST_SHA256 second=SECOND_SHA256
```

같으면 첫 번째 파일을 Canonical Output으로 복사한다.

- [x] **Step 4: 12개 Capture를 생성한다**

Run:

```bash
node scripts/capture-usage-billing-evolution-visual-companion.mjs
```

Expected:

```text
Usage billing captures passed: chrome=151.0.7922.72 assets=12 dimensions=1440x900 deterministic=12/12
```

- [x] **Step 5: PNG를 한 장씩 Full-size로 검사한다**

각 PNG에서 다음 항목을 기록한다.

- 제목·Card·상태 Label 잘림 0
- View별 선택 상태와 Scenario 이름 일치
- Light·Dark Contrast
- 한국어·영어 Font Fallback 정상
- 수평 Overflow 0
- 기술 식별자 오타 0

Contact Sheet는 Pattern Scan에만 사용하고 Full-size 검사 12건을 대체하지 않는다.

- [x] **Step 6: Capture Script와 PNG를 커밋한다**

```bash
git add scripts/capture-usage-billing-evolution-visual-companion.mjs docs/images/visual-companions/usage-billing-evolution-*.png
git commit
```

Commit intent: `Give every billing-series locale a deterministic visual fallback`

### Task 5: Workshop Visual Companion을 Browser에서 검증한다

**Files:**

- Modify when repair is needed: `scripts/visual-companions/usage-billing-evolution-model.mjs`
- Modify when repair is needed: `scripts/generate-usage-billing-evolution-visual-companion.mjs`
- Regenerate: `docs/visual-companions/en/usage-billing-evolution.html`
- Regenerate: `docs/visual-companions/ko/usage-billing-evolution.html`

- [x] **Step 1: 두 Locale HTML을 Local HTTP Server로 제공한다**

Run:

```bash
python3 -m http.server 8765 --directory .
```

Routes:

```text
http://127.0.0.1:8765/docs/visual-companions/en/usage-billing-evolution.html#ledger
http://127.0.0.1:8765/docs/visual-companions/ko/usage-billing-evolution.html#ledger
```

- [x] **Step 2: 세 Fragment와 Browser History를 검증한다**

각 Locale에서 `#ledger`, `#event-sourcing`, `#microservices` 직접 진입을 확인한다. 알 수 없는 `#unknown`은 `#ledger`로 바뀌어야 한다. 화면을 차례로 선택한 뒤 Back/Forward로 선택 상태가 복원되어야 한다.

- [x] **Step 3: Desktop·360px·Theme·Keyboard를 검증한다**

검증 Matrix:

```text
2 locales × 2 themes × 2 viewport classes = 8 render checks
```

각 Check에서 Console Error 0, Page Error 0, 수평 Overflow 0, Focus 누락 0, 숨은 Scenario 0을 확인한다.

- [x] **Step 4: 최종 생성·검증을 다시 실행한다**

```bash
node scripts/generate-usage-billing-evolution-visual-companion.mjs
node scripts/validate-visual-companions.mjs
node scripts/validate-usage-billing-evolution-visual-companion.mjs
node scripts/capture-usage-billing-evolution-visual-companion.mjs
git diff --check
```

Expected: 모든 명령 Exit 0, Capture `12/12`, Diff Check 출력 없음.

### Task 6: Workshop Lesson과 최종 Source 커밋을 만든다

**Files:**

- Create: `docs/lessons/2026-08-05-usage-billing-visualization-boundaries.md`
- Modify: `docs/lessons/README.md` if the repository index includes dated lessons

- [x] **Step 1: 재사용 가능한 교훈을 기록한다**

Lesson은 다음 세 결정을 근거와 함께 기록한다.

1. Manifest의 `simulation`과 HTML 내부의 세 화면 Fragment를 분리해야 기존 Publisher Schema를 확장하지 않는다.
2. 동일 Scenario ID를 세 구현 단계에 적용해야 Architecture 발전 과정을 비교할 수 있다.
3. HTML 원본과 Blog PNG를 같은 Capture 입력에서 만들어야 Locale·Theme별 설명이 어긋나지 않는다.

- [x] **Step 2: 최종 Workshop Diff를 검토한다**

```bash
git status --short
git diff develop...HEAD --stat
git diff develop...HEAD --check
node scripts/validate-visual-companions.mjs
node scripts/validate-usage-billing-evolution-visual-companion.mjs
```

Expected: 관련 Source·HTML·PNG·Manifest·Lesson만 변경되고 P0/P1 Finding 0.

- [x] **Step 3: Lesson을 커밋한다**

```bash
git add docs/lessons/2026-08-05-usage-billing-visualization-boundaries.md docs/lessons/README.md
git commit
```

Commit intent: `Preserve the publication boundary behind the billing visualization`

- [x] **Step 4: Workshop 전달 경계를 보고한다**

보고 내용:

- Exact Head SHA
- HTML 2개, PNG 12개, Scenario 16개
- Validator와 Browser Matrix 결과
- Manifest 문서 수와 Locale 수
- Lesson Path
- PR 생성 권한 여부

PR 생성 권한이 없으면 Branch와 Worktree를 보존하고 Site 단계는 `PENDING`으로 둔다.

### Task 7: Workshop Merge SHA를 고정한 Site Worktree를 만든다

**Prerequisite:** Workshop PR이 병합되어 Exact Merge SHA가 확인되어야 한다.

**Files:**

- Modify: `src/data/visual-companions/repositories.json`
- Regenerate: `src/data/visual-companions/bluetape4k-workshop.snapshot.json`
- Regenerate: `public/visual-companions/bluetape4k-workshop/usage-billing-evolution/index.html`
- Regenerate: `public/ko/visual-companions/bluetape4k-workshop/usage-billing-evolution/index.html`

- [ ] **Step 1: Site의 Clean Develop에서 별도 Worktree를 만든다**

```bash
cd /Users/debop/work/bluetape4k/bluetape4k.github.io
/Users/debop/.local/bin/worktree-new docs/usage-billing-part1 --base develop
```

Expected: `.worktrees/docs-usage-billing-part1`이 생성되고 Branch는 `develop`과 분리된다.

- [ ] **Step 2: Live GitHub에서 Workshop Merge SHA를 읽는다**

```bash
workshop_merge_sha=$(gh pr view docs/usage-billing-series-visualization \
  --repo bluetape4k/bluetape4k-workshop \
  --json state,mergeCommit \
  --jq 'select(.state == "MERGED") | .mergeCommit.oid')
test "${#workshop_merge_sha}" -eq 40
```

Expected: 병합되지 않은 Branch면 빈 값 때문에 `test`가 실패하고, 병합된 Branch면 `workshop_merge_sha`가 40자리 Merge SHA다.

- [ ] **Step 3: 병합 SHA의 읽기 전용 Source Worktree를 만든다**

```bash
workshop_root=/Users/debop/work/bluetape4k/bluetape4k-workshop
workshop_source=/Users/debop/work/bluetape4k/bluetape4k-workshop/.worktrees/visual-source-usage-billing
git -C "$workshop_root" fetch origin
git -C "$workshop_root" worktree add --detach "$workshop_source" "$workshop_merge_sha"
test "$(git -C "$workshop_source" rev-parse HEAD)" = "$workshop_merge_sha"
```

Expected: Source Worktree의 `HEAD`가 GitHub에서 읽은 Merge SHA와 정확히 같다.

- [ ] **Step 4: Site Repository Descriptor에 확인한 SHA를 기록한다**

`bluetape4k/bluetape4k-workshop` 항목의 `sourceRef`만 실제 Merge SHA로 바꾼다. 다른 Repository Descriptor는 수정하지 않는다.

- [ ] **Step 5: Visual Companion Snapshot을 동기화한다**

```bash
npm run sync:visual-companions -- \
  --repository bluetape4k/bluetape4k-workshop \
  --source-root /Users/debop/work/bluetape4k/bluetape4k-workshop/.worktrees/visual-source-usage-billing \
  --source-ref "$workshop_merge_sha"
npm run check:visual-companions
node --test tests/visual-companions/*.test.mjs
```

Expected: Workshop 문서 5개와 Locale Asset 10개가 고정 SHA에서 동기화되고 Snapshot Digest가 유효하다.

### Task 8: 고정 Source Ref에서 Blog Workflow PNG를 복사한다

**Files:**

- Create: `scripts/copy-usage-billing-evolution-assets.mjs`
- Create: `public/assets/blog/usage-billing/part1/usage-billing-evolution-*.png`
- Test: `scripts/copy-usage-billing-evolution-assets.mjs`

- [ ] **Step 1: Copy Script가 Source HEAD 불일치를 거부하게 한다**

Script는 `repositories.json`의 Workshop `sourceRef`와 전달받은 Source Root의 `git rev-parse HEAD`가 같은지 먼저 확인한다.

```javascript
if (sourceHead !== descriptor.sourceRef) {
  throw new Error(`VISUAL_SOURCE_REF_MISMATCH: ${sourceHead} != ${descriptor.sourceRef}`);
}
```

- [ ] **Step 2: 12개 입력과 출력 이름을 고정한다**

입력은 Workshop의 `docs/images/visual-companions/usage-billing-evolution-*.png`다. 출력은 `public/assets/blog/usage-billing/part1/` 아래에서 같은 Base Name을 유지한다.

- [ ] **Step 3: 복사 뒤 Size와 SHA-256을 검증한다**

Script는 Source와 Target의 Byte Size와 SHA-256이 같은지 확인하고 다음을 출력한다.

```text
Usage billing assets copied: sourceRef=SOURCE_SHA assets=12 hashMatches=12
```

- [ ] **Step 4: Script를 실행한다**

```bash
node scripts/copy-usage-billing-evolution-assets.mjs \
  --source-root /Users/debop/work/bluetape4k/bluetape4k-workshop/.worktrees/visual-source-usage-billing
```

Expected: `assets=12 hashMatches=12`.

### Task 9: Part 1 Hero를 만든다

**Files:**

- Create: `public/assets/blog/usage-billing/part1/usage-billing-part1-hero.png`

- [ ] **Step 1: 인접 Hero Contact Sheet를 만든다**

다음 계열에서 최근 Hero 6개를 같은 크기로 비교한다.

- Image Intelligence
- Clinic Appointment
- JaVers
- Reservation Control Plane

Contact Sheet는 색감, 3D 작업대, Robot Builder, 정보 밀도와 여백을 비교하는 용도다.

- [ ] **Step 2: Text-free Hero를 생성한다**

장면:

```text
Dark polished 3D miniature engineering workbench. Small white-and-blue robot builders route glowing usage-event tokens through a pricing timeline, a checkpointed billing-close station, an append-only ledger, and a sealed invoice archive. PostgreSQL authority is represented by a sturdy central database block. No readable text, no logos, no UI screenshot, no flat infographic.
```

- [ ] **Step 3: 기존 Hero와 같은 크기·형식으로 정규화한다**

PNG Dimension과 압축 형식은 같은 시기의 Blog Hero와 맞춘다. 이미지에 Text, Watermark, Logo, 잘린 Robot, 불필요한 얼굴이나 손이 없어야 한다.

- [ ] **Step 4: Contact Sheet에서 최종 Hero를 검토한다**

검토 항목:

- 기존 시리즈와 같은 완성도
- 원장·마감·청구 흐름이 한 장면에서 구분됨
- Card Thumbnail Crop에서 핵심 장면 유지
- 기술 다이어그램으로 오인되지 않음

### Task 10: 한국어 Part 1을 Source 근거로 작성한다

**Files:**

- Create: `src/content/docs/ko/blog/usage-billing-part1-ledger-and-resumable-close.mdx`

- [ ] **Step 1: Frontmatter와 Hero를 작성한다**

```yaml
---
title: "사용량 과금은 합계 계산이 아니다: 중복 수집부터 재시작 가능한 마감까지"
description: 반복 전달되는 사용량을 발생 시점의 가격으로 평가하고, 중단된 월 마감을 재개하며, 확정된 청구 결과를 원장을 통해 보정하는 방법을 설명합니다.
sidebar:
  order: -202608051200
blog:
  date: 2026-08-05T12:00:00+09:00
  image: /assets/blog/usage-billing/part1/usage-billing-part1-hero.png
  imageAlt: 사용량 이벤트가 가격 타임라인과 재시작 가능한 마감 작업을 거쳐 원장과 봉인된 청구서로 이어지는 어두운 3D 작업대
  cardDescription: "중복 사용량, 가격 변경, 마감 재시작, 늦은 사용량을 하나의 PostgreSQL 권위 모델에서 다루는 방법을 살펴봅니다."
  tags: ["architecture","billing","event-driven","kotlin","postgresql","resilience","spring"]
---
```

- [ ] **Step 2: 독자 문제와 범위를 먼저 설명한다**

첫 세 Section은 다음 순서를 사용한다.

1. API 호출량을 월말에 단순 합산하면 발생하는 실패 시나리오
2. 예제가 다루는 과금 정확성 경계와 다루지 않는 세금·수납·복잡 가격 정책
3. `receivedAt`, `occurredAt`, Posting Period의 서로 다른 시간 역할

- [ ] **Step 3: 두 종류의 중복 방지를 의사코드로 설명한다**

```kotlin
receipt = commandReceipts.acquire(tenant, operation, keyDigest, fingerprint)

when (receipt) {
    is Replay -> return receipt.savedResponse
    is Conflict -> throw IdempotencyConflict()
    is Acquired -> {
        usage = usageEvents.appendIfAbsent(sourceSystem, sourceEventId, payloadDigest)
        commandReceipts.complete(receipt.ownerToken, responseFor(usage))
        return responseFor(usage)
    }
}
```

의사코드 바로 뒤에 HTTP Command Replay와 Producer Event Unique의 차이를 표로 정리한다.

- [ ] **Step 4: 가격 Timeline과 경계 시각을 설명한다**

`[effectiveFrom, effectiveTo)` 구간과 정확한 경계 시각의 v2 선택을 예로 든다. 가격 선택은 `occurredAt`, 마감 포함 여부는 `receivedAt`을 사용한다고 반복해서 고정한다.

- [ ] **Step 5: `#ledger` Workflow를 Embed하고 연결한다**

```mdx
<picture>
  <source media="(prefers-color-scheme: dark)" srcSet="/assets/blog/usage-billing/part1/usage-billing-evolution-ledger.ko.dark.png" />
  <img src="/assets/blog/usage-billing/part1/usage-billing-evolution-ledger.ko.light.png" alt="사용량 수집, 가격 선택, 재시작 가능한 마감, 원장, 청구서와 보정 흐름" loading="lazy" />
</picture>
```

이미지는 기술 Workflow이므로 기존 크게 보기 대상 Class와 `data-diagram-title`을 적용한다. 바로 아래에 다음 Link를 둔다.

```md
[대화형 시각 자료에서 원장형 과금 흐름 살펴보기](/ko/visual-companions/bluetape4k-workshop/usage-billing-evolution/#ledger)
```

- [ ] **Step 6: 재시작 가능한 마감과 청구 불변식을 설명한다**

Batch 의사코드는 다음 순서를 유지한다.

```kotlin
batch = usageEvents.findAfter(run.checkpoint, cutoff = run.cutoffReceivedAt, limit = 200)
for (usage in batch) {
    price = prices.findAt(usage.occurredAt) ?: markUnpriced(usage)
    ledger.appendChargeOnce(usage, price)
}
closeRuns.advanceCheckpoint(run.id, batch.lastKey)
```

`appendChargeOnce()`와 Checkpoint 갱신이 한 Transaction이라는 점, Commit 전 종료와 Commit 직후 종료의 결과를 나누어 설명한다.

- [ ] **Step 7: 불변 청구서와 늦은 사용량 보정을 설명한다**

다음 등식을 본문에 포함한다.

```text
sum(invoice lines) == invoice total == sum(linked ledger entries)
```

늦은 사용량은 Finalized Period를 다시 열지 않고 현재 Open Posting Period에 `DEBIT_ADJUSTMENT`로 기록한다. Credit은 음수 금액이 아니라 양수 Amount와 `CREDIT_ADJUSTMENT` Direction으로 표현한다.

- [ ] **Step 8: 선택 기준과 Source 링크로 마무리한다**

자료에는 독자가 읽을 대표 Source만 제공한다.

- Module README
- `BillingCloseService`
- `InvoiceService`
- `MeteringEndToEndIntegrationTest`
- 통합 Visualization

Raw Issue·PR·검토 기록은 자료 목록에서 제외한다.

### Task 11: 영어 Part 1을 자연스럽게 현지화한다

**Files:**

- Create: `src/content/docs/blog/usage-billing-part1-ledger-and-resumable-close.mdx`

- [ ] **Step 1: 한국어 구조와 Source Claim을 고정한다**

영어 글의 Heading 수, Code Block, Table, Source Link, 수치와 한계는 한국어와 같아야 한다. 문장을 직역하지 않고 영어 기술 문체로 다시 작성한다.

- [ ] **Step 2: 영어 Frontmatter를 작성한다**

```yaml
---
title: "Usage Billing Is More Than Summation: From Duplicate Ingestion to Resumable Closing"
description: Learn how to deduplicate usage, price it by occurrence time, resume an interrupted billing close, and correct finalized financial results through an append-only ledger.
sidebar:
  order: -202608051200
blog:
  date: 2026-08-05T12:00:00+09:00
  image: /assets/blog/usage-billing/part1/usage-billing-part1-hero.png
  imageAlt: A dark 3D workbench where usage events pass through a pricing timeline and resumable billing close into a ledger and sealed invoice archive
  cardDescription: "See how one PostgreSQL authority handles duplicate usage, changing prices, resumable closing, and late adjustments."
  tags: ["architecture","billing","event-driven","kotlin","postgresql","resilience","spring"]
---
```

- [ ] **Step 3: 영어 Workflow와 Route를 연결한다**

English PNG는 `.en.light.png`, `.en.dark.png`를 사용한다.

```md
[Explore the ledger workflow interactively](/visual-companions/bluetape4k-workshop/usage-billing-evolution/#ledger)
```

- [ ] **Step 4: Locale Parity를 검증한다**

다음 Matrix를 확인한다.

```text
Part count: 1 == 1
Headings: same semantic sections
Code blocks: same identifiers and numbers
Source links: same targets
Visualization: locale-specific PNG and route
Series navigation: same current/planned parts
```

### Task 12: Site Link·Asset·Locale 계약을 테스트한다

**Files:**

- Create: `tests/visual-companions/usage-billing-blog-link.test.mjs`
- Test: `tests/visual-companions/usage-billing-blog-link.test.mjs`

- [ ] **Step 1: Locale별 글과 Route를 읽는 테스트를 작성한다**

```javascript
test('usage billing part 1 links each locale to the ledger visualization', async () => {
  const ko = await readFile('src/content/docs/ko/blog/usage-billing-part1-ledger-and-resumable-close.mdx', 'utf8');
  const en = await readFile('src/content/docs/blog/usage-billing-part1-ledger-and-resumable-close.mdx', 'utf8');

  assert.match(ko, /\/ko\/visual-companions\/bluetape4k-workshop\/usage-billing-evolution\/#ledger/);
  assert.match(en, /\/visual-companions\/bluetape4k-workshop\/usage-billing-evolution\/#ledger/);
  assert.doesNotMatch(ko, /usage-billing-evolution-ledger\.en\./);
  assert.doesNotMatch(en, /usage-billing-evolution-ledger\.ko\./);
});
```

- [ ] **Step 2: 12개 PNG와 두 공개 HTML이 존재하는지 검사한다**

Test는 View 3개 × Locale 2개 × Theme 2개의 Cartesian Product를 만들고 `access()`로 확인한다. 공개 HTML은 EN·KO Route 각각 한 개여야 한다.

- [ ] **Step 3: 테스트를 실행한다**

```bash
node --test tests/visual-companions/usage-billing-blog-link.test.mjs
npm test
```

Expected: 신규 Test PASS, 전체 Visual Companion·Manual·Ecosystem Test PASS.

### Task 13: 한국어 기술 문체와 Site Build를 검증한다

**Files:**

- Modify when correction is needed: `src/content/docs/ko/blog/usage-billing-part1-ledger-and-resumable-close.mdx`
- Modify when parity repair is needed: `src/content/docs/blog/usage-billing-part1-ledger-and-resumable-close.mdx`

- [ ] **Step 1: 사실 검토를 먼저 수행한다**

다음 Claim을 구현·테스트와 다시 대조한다.

- Command Receipt와 Source Event Unique의 역할
- 가격 구간과 `occurredAt`
- Close Cutoff와 `receivedAt`
- Batch Size 200과 Keyset Checkpoint
- `READY_TO_FINALIZE` 조건
- Invoice Total 등식
- Late Usage의 Posting Period와 Adjustment Type
- Stress Test 10,000건은 성능 Benchmark가 아니라 복구 회귀 Test라는 한계

- [ ] **Step 2: 한국어 자연스러움 Checklist를 적용한다**

제거 대상:

- 영어 문장 구조를 따른 긴 관형절
- `~을 제공한다`, `강력한`, `핵심적인` 같은 근거 없는 홍보 문구
- `사용량을 가격화한다`처럼 실무 기술 문서에서 어색한 표현
- `값싼 검사`와 같은 구어적 직역
- 같은 개념에 `마감`, `종료`, `Close`를 무분별하게 혼용하는 문장

유지할 기술어:

- 사용량, 가격 Version, 반개구간, 마감 Cutoff, Keyset Checkpoint
- 원장, 청구서, Provenance, Debit/Credit Adjustment
- Event Sourcing, Outbox, Inbox, Projection

- [ ] **Step 3: 영어 Parity를 다시 맞춘다**

한국어 교정으로 의미·수치·제한 사항이 바뀌었다면 영어 글에도 같은 Claim을 반영한다. 문장 형태까지 동일하게 맞추지는 않는다.

- [ ] **Step 4: Site 검증을 실행한다**

```bash
git diff --check
npm run check:visual-companions
npm test
npm run build
```

Expected: 모든 명령 Exit 0, Astro Build Error 0.

### Task 14: Local Preview와 Browser QA를 수행한다

**Files:**

- No planned file changes; repair only the failed route, asset, prose, or layout source.

- [ ] **Step 1: Preview Server를 실행한다**

```bash
npm run dev -- --host 127.0.0.1 --port 4332
```

검토 Route:

```text
http://127.0.0.1:4332/ko/blog/usage-billing-part1-ledger-and-resumable-close/
http://127.0.0.1:4332/blog/usage-billing-part1-ledger-and-resumable-close/
http://127.0.0.1:4332/ko/visual-companions/bluetape4k-workshop/usage-billing-evolution/#ledger
http://127.0.0.1:4332/visual-companions/bluetape4k-workshop/usage-billing-evolution/#ledger
```

- [ ] **Step 2: Blog Route를 검증한다**

한국어·영어에서 다음을 확인한다.

- Hero, Meta, Heading, Table, Code Block 렌더링
- Workflow 이미지 크게 보기와 제목
- Locale별 대화형 Link
- Source Link 404 없음
- Part 1 Series Navigation
- Console Error 0, 수평 Overflow 0

- [ ] **Step 3: 공개 Visualization Route를 검증한다**

세 Fragment와 Light·Dark·360px를 다시 확인한다. Site Sync가 Source 상대 Link를 고정 GitHub SHA와 Locale Route로 올바르게 바꿨는지 확인한다.

- [ ] **Step 4: 최종 Browser 보정 뒤 Build를 다시 실행한다**

시각 또는 Link 보정이 있었다면 `npm test`와 `npm run build`를 다시 실행한다. 보정 전 Build 결과를 최종 증거로 재사용하지 않는다.

### Task 15: Site Lesson과 전달 커밋을 만든다

**Files:**

- Create: `docs/lessons/2026-08-05-usage-billing-part1-source-snapshot.md`
- Modify: `docs/lessons/README.md` if applicable

- [ ] **Step 1: Source Snapshot 경계 교훈을 기록한다**

Lesson은 다음 내용을 포함한다.

- Blog가 Workshop Branch 파일이 아니라 Site Locale Route를 연결해야 하는 이유
- Workshop Merge SHA와 PNG Hash를 함께 고정하는 이유
- Part별 공개 시점이 달라도 통합 Visualization의 Stable Fragment를 유지하는 방법

- [ ] **Step 2: 최종 Diff와 Locale Parity를 검토한다**

```bash
git status --short
git diff develop...HEAD --stat
git diff develop...HEAD --check
npm run check:visual-companions
npm test
npm run build
```

Expected: 관련 Snapshot·Assets·글·Test·Lesson만 변경되고 P0/P1 Finding 0.

- [ ] **Step 3: Site 변경을 커밋한다**

```bash
git add \
  src/data/visual-companions/repositories.json \
  src/data/visual-companions/bluetape4k-workshop.snapshot.json \
  public/visual-companions/bluetape4k-workshop \
  public/ko/visual-companions/bluetape4k-workshop \
  scripts/copy-usage-billing-evolution-assets.mjs \
  public/assets/blog/usage-billing/part1 \
  src/content/docs/ko/blog/usage-billing-part1-ledger-and-resumable-close.mdx \
  src/content/docs/blog/usage-billing-part1-ledger-and-resumable-close.mdx \
  tests/visual-companions/usage-billing-blog-link.test.mjs \
  docs/lessons/2026-08-05-usage-billing-part1-source-snapshot.md
git commit
```

Commit intent: `Teach resumable billing from an immutable workshop source`

- [ ] **Step 4: 최종 DoD를 보고한다**

보고 내용:

- Workshop Source Merge SHA와 Site Source Ref
- 한국어·영어 Blog Route
- 한국어·영어 Visualization Route와 Fragment
- HTML 2개, PNG 12개, Hero 1개
- Locale Parity와 한국어 교정 결과
- Workshop·Site Test와 Build 결과
- Lesson 2개
- PR·병합·배포·정리의 현재 권한 상태

## 계획 자체 검토 기준

- 설계서 Section 1~15를 Task 1~15 중 하나 이상이 구현한다.
- Workshop Source 단계와 Site 게시 단계의 순서가 뒤바뀌지 않는다.
- Manifest Schema를 확장하지 않고 HTML Fragment로 세 화면을 제공한다.
- 16개 Scenario, 2개 Locale, 3개 화면, 2개 Theme의 수가 모든 Task에서 일치한다.
- 미정 표식, 임의 PR 번호와 임의 Merge SHA가 없어야 한다.
- PR 생성, 병합과 배포는 계획 실행 결과에 자동 포함하지 않는다.
