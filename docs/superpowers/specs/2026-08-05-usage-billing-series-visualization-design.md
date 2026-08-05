# SaaS 사용량 과금 블로그 시리즈와 통합 Visualization 설계

날짜: 2026-08-05

원본 저장소: `bluetape4k-workshop`

게시 저장소: `bluetape4k.github.io`
근거 모듈:

- `commerce/usage-metering-billing-ledger`
- `commerce/usage-metering-billing-event-sourcing`
- `commerce/usage-billing-microservices-composition-tests`

## 1. 목적

이 작업은 최근 workshop에 구현된 사용량 과금 예제를 세 편의 블로그 글로 설명하고, 세 구현 단계의 차이를 직접 탐색할 수 있는 하나의 통합 Visualization을 제공한다.

시리즈가 답할 핵심 질문은 다음과 같다.

> 반복해서 도착하고 순서도 뒤바뀌는 사용량을 어떻게 금전적 사실로 확정하고, 장애 이후에도 같은 청구 결과를 재현할 것인가?

독자는 시리즈를 읽은 뒤 다음 내용을 설명할 수 있어야 한다.

1. 사용량 과금에서 `quantity × unitPrice`보다 중복 수집, 시간 기준, 마감 재시작이 어려운 이유
2. 원장형 구현에서 변경 가능한 작업 상태와 변경할 수 없는 금전적 사실을 분리하는 방법
3. Event Sourcing이 재생과 투영 재구축을 제공하는 대신 어떤 운영 비용을 추가하는지
4. 마이크로서비스로 분리했을 때 Kafka의 at-least-once 전달을 Outbox, Inbox, 격리, 보정으로 다루는 방법
5. 어떤 규모와 복구 요구에서 각 단계를 선택해야 하는지

## 2. 중복 방지 경계

기존 블로그 글은 Transactional Outbox, Event Sourcing의 일반 개념, Kafka 장애 복구를 이미 다룬다. 새 시리즈는 해당 패턴을 다시 소개하지 않는다.

| 기존 주제 | 새 시리즈에서의 처리 |
|---|---|
| Transactional Outbox 개념 | 배경 링크만 제공하고 정의와 일반 구현은 반복하지 않는다. |
| Event Sourcing 입문 | 과금 원장과 비교할 때 필요한 선택 기준만 설명한다. |
| Kafka Outbox/Inbox | 과금 서비스 사이의 금전적 사실 전달과 보정에 한정한다. |
| PostgreSQL 권위 모델 | 예약 제어 사례를 반복하지 않고 가격 시점, 마감, 청구 불변식에 적용한다. |
| 범용 Billing Framework | 제공한다고 주장하지 않는다. 예제가 증명하는 경계만 설명한다. |

글과 Visualization은 다음 범위를 명시적으로 제외한다.

- 세금, 환율, 수익 인식, 결제 수납
- graduated tier, volume discount, committed use와 같은 복잡한 가격 공식
- 분산 Exactly-once 보장
- 모든 조직에 적합한 범용 과금 플랫폼
- 구현에 존재하지 않는 운영 자동화나 성능 수치

## 3. 검토한 구성

### 구성 A: Part별 독립 Visualization

각 글에 별도의 대화형 자료를 만든다.

장점:

- 한 화면의 정보량이 작다.
- 각 글만 읽는 독자에게 독립적이다.

단점:

- 같은 사용량·가격·청구 용어를 세 자료에 반복한다.
- 구현 단계가 바뀔 때 무엇이 유지되고 무엇이 추가되는지 비교하기 어렵다.
- 언어와 Theme별 산출물이 불필요하게 늘어난다.

### 구성 B: 시리즈 통합 Visualization

하나의 대화형 자료에서 `원장형 → Event Sourcing → 마이크로서비스` 세 화면을 전환한다. 각 글은 대응하는 고정 fragment로 연결한다.

장점:

- 동일한 시나리오를 세 구현 단계에서 비교할 수 있다.
- 시리즈 전체의 선택 기준이 한 자료에 모인다.
- 공통 용어와 데이터 흐름을 한 번만 관리한다.

단점:

- 한 문서의 정보 구조와 상태 관리가 복잡하다.
- 모바일에서는 세 화면을 한 번에 비교할 수 없으므로 순차 탐색 UI가 필요하다.

### 구성 C: 정적 다이어그램 묶음

각 글에 SVG 기반 아키텍처·시퀀스 다이어그램만 제공한다.

장점:

- 구현과 검증이 단순하다.
- 정확한 연결선과 시간 순서를 표현하기 쉽다.

단점:

- 실패 시나리오와 선택 기준을 독자가 직접 비교할 수 없다.
- 세 구현 단계 사이의 발전 과정을 여러 이미지에서 추론해야 한다.

### 선택

사용자가 승인한 **구성 B**를 채택한다. 정적 SVG를 HTML 안에 감싸는 방식은 사용하지 않는다. 카드, 단계, 비교표, 상태 패널을 DOM으로 구성하고, 시나리오와 단계 선택에 따라 설명이 바뀌는 reader-explorable business workflow로 구현한다.

## 4. 블로그 시리즈

### Part 1 — 원장형 과금

한국어 제목:

> 사용량 과금은 합계 계산이 아니다: 중복 수집부터 재시작 가능한 마감까지

영어 제목:

> Usage Billing Is More Than Summation: From Duplicate Ingestion to Resumable Closing

독자 문제:

- 같은 사용량이 여러 번 도착한다.
- 가격이 서비스 이용 도중 변경된다.
- 월 마감 작업이 중간에 종료될 수 있다.
- 마감 후 도착한 사용량도 청구 이력에서 사라지면 안 된다.

핵심 설명 순서:

1. 실전 시나리오와 범위
2. `occurredAt`과 `receivedAt`의 역할 분리
3. HTTP command replay와 producer event 중복 방지의 차이
4. 반개구간 가격 Timeline과 발생 시점 기준 가격 선택
5. 고정 cutoff와 keyset checkpoint를 사용하는 재시작 가능한 마감
6. 원장·청구서·provenance 불변식
7. 늦은 사용량과 debit/credit 정정
8. 운영 신호와 선택 기준

Visualization 연결: `#ledger`

### Part 2 — Event Sourcing 선택

한국어 제목:

> 과금 원장을 Event Sourcing으로 바꾸면 무엇을 얻고 무엇을 운영해야 하는가

영어 제목:

> What Event Sourcing Adds to a Billing Ledger—and What You Must Operate

독자 문제:

- 과거 상태를 재생하거나 새로운 Projection을 만들고 싶다.
- 이벤트 계약이 바뀌어도 오래된 이력을 읽어야 한다.
- Projection 재구축 중에도 조회를 중단하지 않아야 한다.

핵심 설명 순서:

1. Part 1의 원장형 구현과 같은 불변식
2. Event Envelope, Stream Version과 Hash Chain
3. 멱등 Command와 Optimistic Append
4. Replay, Snapshot과 Reducer 검증
5. Projection Generation을 이용한 무중단 재구축
6. Poison Event, Upcast와 운영 Runbook
7. Event Sourcing을 선택하지 않아도 되는 조건

Visualization 연결: `#event-sourcing`

### Part 3 — 마이크로서비스 분리

한국어 제목:

> 다섯 서비스로 분리한 사용량 과금: 중복 전달, 격리와 보정

영어 제목:

> Usage Billing Across Five Services: Duplicate Delivery, Isolation, and Correction

독자 문제:

- Meter, Usage, Billing, Invoice와 Query의 배포·소유권을 분리하고 싶다.
- Kafka의 중복·역순·일시 중단에도 금전적 사실을 잃지 않아야 한다.
- 잘못된 이벤트 하나가 전체 Consumer를 멈추지 않게 해야 한다.

핵심 설명 순서:

1. 단일 DB Transaction에서 메시지 계약으로 바뀌는 경계
2. 서비스별 PostgreSQL과 소유권
3. Transactional Outbox와 Consumer Inbox
4. 동일 ID·다른 Digest 충돌
5. Poison Event 격리와 Redrive
6. Schema Evolution과 순서 뒤바뀜
7. 사후 Correction과 Query Projection
8. 단계적 분리와 Rollback 기준

Visualization 연결: `#microservices`

## 5. 통합 Visualization

문서 ID는 `usage-billing-evolution`으로 한다.

공개 Route:

- 한국어: `/ko/visual-companions/bluetape4k-workshop/usage-billing-evolution/`
- 영어: `/visual-companions/bluetape4k-workshop/usage-billing-evolution/`

고정 화면 fragment:

- `#ledger`
- `#event-sourcing`
- `#microservices`

페이지를 fragment와 함께 열면 해당 화면을 선택하고, 화면 전환 시 URL fragment도 갱신한다. 알 수 없는 fragment는 `#ledger`로 정규화한다. 브라우저의 뒤로·앞으로 이동으로 화면 선택을 복원한다.

### 5.1 공통 시나리오

세 화면은 같은 시나리오를 사용한다.

1. `tenant-a`의 `api_calls` Meter에 가격 v1이 활성화되어 있다.
2. 가격 변경 시점 전후로 사용량 이벤트가 발생한다.
3. Producer Retry로 같은 `sourceEventId`가 다시 도착한다.
4. 월 마감 Worker가 Batch Commit 직후 종료된다.
5. 마감 뒤 서비스 기간에 속한 늦은 사용량이 도착한다.
6. 운영자가 원본을 수정하지 않고 보정 사실을 추가한다.

화면마다 데이터 저장 방식은 달라도 다음 불변식은 유지한다.

- 같은 생산자 이벤트는 한 번만 금전적 결과에 반영된다.
- 가격은 `occurredAt`에 유효한 Version으로 결정된다.
- 확정된 금전적 사실과 발행된 청구서는 직접 수정하지 않는다.
- 재시작 후에도 중복 금액 없이 같은 결과에 도달한다.
- 보정은 원본을 참조하는 새 사실로 남는다.

### 5.2 화면 1: 원장형 과금

독자 질문:

> 하나의 PostgreSQL 안에서 사용량을 금전적 사실로 안전하게 확정하는 최소 구조는 무엇인가?

상호작용:

- `정상`, `중복 도착`, `가격 누락`, `마감 재시작`, `늦은 사용량` 시나리오를 선택한다.
- `수집`, `가격 선택`, `Batch 처리`, `청구 확정`, `보정` 단계를 순서대로 진행하거나 재시작한다.
- 각 단계에서 현재 상태, PostgreSQL에 남은 권위 자료, 허용된 다음 작업을 표시한다.
- `occurredAt`과 `receivedAt`을 전환해 서로 다른 판단에 사용되는 이유를 비교한다.

표시할 주요 요소:

- Command Receipt
- Usage Event
- Price Version Timeline
- Billing Period와 Close Run
- Ledger Entry
- Invoice Line과 Provenance
- Reconciliation Finding

### 5.3 화면 2: Event Sourcing

독자 질문:

> 같은 과금 불변식을 Event Store로 옮기면 어떤 복구 능력과 운영 책임이 추가되는가?

상호작용:

- 원장형과 Event Sourcing의 책임 차이를 비교한다.
- 이벤트를 Append한 뒤 Aggregate State와 Projection이 각각 어떻게 변하는지 단계별로 본다.
- `Replay`, `Snapshot 복원`, `Projection 재구축`, `Poison Event` 시나리오를 선택한다.
- 기존 Generation과 재구축 Generation을 비교하고 활성 Generation 전환 조건을 확인한다.

표시할 주요 요소:

- Event Stream과 Expected Version
- Event Envelope, Schema Version과 Hash
- Aggregate Reducer
- Snapshot
- Projection Generation
- Poison Event 격리
- Correction Event

### 5.4 화면 3: 마이크로서비스

독자 질문:

> 하나의 Transaction이 다섯 서비스로 분리되면 어느 실패 경계를 직접 운영해야 하는가?

상호작용:

- `정상 전달`, `중복 전달`, `Broker 중단`, `Consumer 재시작`, `Poison Event`, `Correction` 시나리오를 선택한다.
- Meter, Usage, Billing, Invoice, Query 서비스 사이의 사건 이동을 단계별로 진행한다.
- 서비스별 PostgreSQL, Outbox, Inbox와 Projection의 상태를 함께 표시한다.
- 실패 지점을 선택하면 재시도, 격리, Redrive, 보정 중 어떤 작업이 허용되는지 설명한다.

표시할 주요 요소:

- Meter Service
- Usage Service
- Billing Service
- Invoice Service
- Query Service
- Kafka Topic
- 서비스별 Outbox와 Inbox
- Quarantine과 Redrive
- Query Projection

## 6. 정보 구조와 화면 구성

1. 고정 상단 Navigation
2. 제목과 시리즈 핵심 질문
3. 세 구현 단계 선택기
4. 현재 단계의 선택 기준과 비용 요약
5. 공통 시나리오 Timeline
6. 단계별 Business Workflow
7. 권위 자료와 파생 자료 비교 패널
8. 실패·복구 시나리오 선택기
9. 구현 근거와 테스트 링크
10. 어떤 단계를 선택할지 판단표
11. 블로그 Part와 README 링크

Desktop에서는 Workflow와 상태 패널을 나란히 배치한다. 좁은 화면에서는 Workflow 다음에 상태 패널이 오도록 순서를 유지한다. 세 구현 단계를 동시에 축소해 보여주지 않는다.

## 7. 시각 언어

- 기본 Theme는 사용자 환경을 따르고 Light·Dark를 명시적으로 전환할 수 있게 한다.
- Dark Theme는 기존 Bluetape Visual Companion의 어두운 Canvas, 밝은 Card, 명확한 상태색 체계를 따른다.
- 성공·경고·실패를 색상만으로 구분하지 않고 Label과 Icon을 함께 사용한다.
- 화면별 강조색은 다르게 사용할 수 있지만 같은 개념은 세 화면에서 같은 색과 명칭을 유지한다.
- 카드의 본문 글꼴을 줄여 맞추지 않는다. 긴 설명은 세부 패널로 옮기고 카드에는 상태와 책임만 남긴다.
- 한국어는 `goorm Sans`, 기술 식별자는 `goorm Sans Code`를 사용한다.
- 영어 제목·본문에는 `Architects Daughter`, `Comic Mono` 계열을 적용하되 가독성을 우선한다.

## 8. Locale과 Source 계약

Canonical Source:

- 영어: `docs/visual-companions/en/usage-billing-evolution.html`
- 한국어: `docs/visual-companions/ko/usage-billing-evolution.html`

두 파일은 다음 항목에서 Source-equivalent해야 한다.

- 화면 ID와 순서
- 시나리오 ID와 상태 전이
- 기술 식별자와 오류 결과
- Source 링크와 테스트 링크
- 선택 기준과 제한 사항

독자 대상 문장만 자연스럽게 현지화한다. 한국어 파일에서 영어 문장 구조를 직역하지 않는다.

공통 Data를 별도의 Network 요청으로 읽지 않는다. Deterministic Capture와 GitHub Pages 실행을 위해 각 HTML은 필요한 구조화 Data를 자체 포함하되, 동일한 안정 ID 계약을 검증한다.

## 9. PNG Fallback과 블로그 연결

HTML이 대화형 자료의 원본이다. 블로그와 Markdown에는 HTML을 직접 Embed하지 않는다.

각 화면의 기본 시나리오를 언어·Theme별 PNG로 Capture한다.

```text
usage-billing-evolution-ledger.en.light.png
usage-billing-evolution-ledger.en.dark.png
usage-billing-evolution-ledger.ko.light.png
usage-billing-evolution-ledger.ko.dark.png

usage-billing-evolution-event-sourcing.*.{light,dark}.png
usage-billing-evolution-microservices.*.{light,dark}.png
```

총 12개 Fallback은 각 Part의 대표 화면만 포함한다. 실패 시나리오별 PNG를 추가로 만들지 않는다.

각 블로그 글은 다음 방식으로 연결한다.

1. 해당 Part의 Locale·Theme에 맞는 PNG를 `<picture>`로 표시한다.
2. 이미지와 인접한 `대화형 시각 자료에서 살펴보기` 또는 `Explore the interactive visualization` 링크가 해당 Locale route와 fragment를 가리킨다.
3. 대표 이미지와 일반 Screenshot은 확대 대상에서 제외하고, 이 기술 Workflow PNG에는 기존 크게 보기 UI를 적용한다.
4. Part 1·2·3의 하단 시리즈 Navigation에서 이전·다음 글과 통합 Visualization을 함께 제공한다.

## 10. Source 근거 Ledger

| Visualization 개념 | 구현·검증 근거 |
|---|---|
| Command replay와 Producer 중복 방지 | `CommandReceiptService`, Usage Source Event Unique Constraint, `CommandReceiptPostgresIntegrationTest` |
| 발생 시점 가격 선택 | `PriceActivationService`, Price Version Repository, `MeteringEndToEndIntegrationTest` |
| 고정 Cutoff와 Checkpoint | `BillingPeriodService`, `BillingCloseService`, Restart Integration Test |
| 원장·청구서·Provenance | `InvoiceService`, Append-only Repository, End-to-end Integration Test |
| 늦은 사용량과 보정 | `AdjustmentService`, `ReconciliationService`, Reconciliation Integration Test |
| Event Append와 Replay | Event Store, Aggregate Reducer, `AggregateReplayTest` |
| Snapshot과 Projection 재구축 | Snapshot Repository, Projection Coordinator, Projection Recovery Integration Test |
| Poison Event와 Upcast | Event Codec Registry, Projection Generation/Recovery Test |
| 서비스별 Outbox/Inbox | Meter·Usage·Billing·Invoice·Query 서비스와 Composition Test Fixture |
| Broker 장애와 재시작 | `BrokerPathRecoveryIntegrationTest`, `OutageIntegrationTest`, `RestartIntegrationTest` |
| Schema Evolution과 Correction | `SchemaEvolutionIntegrationTest`, `CorrectionIntegrationTest` |

구현 단계에서 표의 일반 명칭을 실제 Package·Class·Test 경로로 다시 확인해 Source 링크를 고정한다. 존재하지 않거나 이름이 바뀐 항목은 표현을 수정하며 추정 링크를 만들지 않는다.

## 11. Manifest와 Site Snapshot

workshop의 `docs/visual-companions/manifest.json`에 `usage-billing-evolution` 문서를 등록한다.

- `status`: `approved`
- `public`: `true`
- `presentation.mode`: `simulation`
- `presentation.defaultView`: `simulation`
- `presentation.views`: `simulation`
- Locale별 Title과 HTML Source Path

Manifest의 `views`는 게시 문서 수준의 Presentation 계약이므로 기존 `simulation` 값을 유지한다. `ledger`, `event-sourcing`, `microservices`는 HTML 내부에서 관리하는 안정된 화면 fragment이며 Manifest Schema를 확장하지 않는다.

workshop PR이 병합된 뒤 Site는 병합 Commit SHA를 `sourceRef`로 고정한다. Site의 Visual Companion Sync 도구가 Manifest와 HTML을 가져오고 Snapshot Digest를 생성한다. Blog 글은 변하는 GitHub Branch 파일이 아니라 Site의 Locale route에 연결한다.

Workshop Source PR과 Site Blog PR은 순서가 있는 두 전달 단위다.

1. workshop: 설계, HTML, Fallback, Manifest, 검증
2. site: 고정 Source Snapshot, 한국어·영어 블로그 글, Asset Embed, Route 검증

사이트가 병합되지 않은 workshop Branch를 Source로 사용하지 않는다.

## 12. 접근성·반응형·보안

- 모든 화면·시나리오 선택기는 Keyboard로 조작할 수 있어야 한다.
- 선택 상태는 `aria-pressed` 또는 Tab Pattern으로 전달한다.
- Focus Ring을 제거하지 않는다.
- `prefers-reduced-motion`에서는 Animation과 Transition을 비활성화한다.
- 360px 폭에서 수평 Page Overflow가 없어야 한다.
- 외부 Font, CDN, Network Request 없이 실행되어야 한다.
- 실제 Tenant, API Key, Idempotency Key, Usage Payload를 포함하지 않는다.
- URL Fragment에는 안정된 화면 ID 외의 상태를 넣지 않는다.

## 13. 검증 계약

### Source 검증

- 두 HTML의 화면·시나리오·기술 식별자 구조 동등성
- Manifest Schema와 Locale Path
- 모든 Source·README·Test 링크 존재 여부
- 알 수 없는 Fragment의 `ledger` Fallback
- Network 요청과 시간·난수 의존성 부재

### Browser 검증

- 한국어·영어 Route
- Desktop과 360px Narrow Viewport
- Light·Dark·Auto Theme
- Keyboard Navigation과 Focus
- 세 화면 Fragment 직접 진입 및 Back/Forward 복원
- 모든 시나리오의 상태·설명 전환
- Console Error 0, Page Error 0, 수평 Overflow 0

### Deterministic Capture

- Chromium Version, Viewport, Device Scale Factor, Locale, Timezone과 Font를 고정한다.
- `document.fonts.ready`와 명시적 Ready Signal을 기다린다.
- 12개 Fallback을 동일 입력으로 두 번 Capture한다.
- 각 Pair의 Dimensions와 SHA-256이 같아야 한다.

### Site 검증

- Visual Companion Snapshot 검증
- 한국어·영어 Blog Route Build
- Part 수, 제목, 기술 주장, Source 링크와 시리즈 Navigation Locale Parity
- 각 Part의 PNG·HTML Locale/Fragment Routing
- `git diff --check`, Site Test와 Production Build

## 14. 성공 기준

1. 세 글이 같은 문제를 반복하지 않고 원장형, Event Sourcing, 마이크로서비스의 발전 단계를 설명한다.
2. Visualization의 세 화면과 모든 시나리오가 실제 구현·테스트에 근거한다.
3. 각 Part가 해당 Locale의 고정 화면 Fragment로 연결된다.
4. 한국어·영어 HTML과 12개 Fallback이 Source-equivalent하고 결정적으로 재생된다.
5. Dark·Light·Mobile·Keyboard 환경에서 내용이 잘리거나 접근이 막히지 않는다.
6. workshop의 병합 Commit을 Site Snapshot Source로 고정한다.
7. 기존 Outbox·Event Sourcing 글은 참고 자료로 연결하되 동일 설명을 반복하지 않는다.
8. 글과 Visualization 어디에도 범용 과금 플랫폼이나 분산 Exactly-once를 제공한다고 주장하지 않는다.

## 15. 전달 범위

이 설계 승인 이후 구현 계획은 다음 두 단계로 나눈다.

1. workshop Source 단계: 통합 Visualization과 Manifest를 구현하고 검증한다.
2. site Publication 단계: Source Snapshot을 갱신하고 Part 1을 한국어·영어로 작성해 Visualization에 연결한다.

Part 2와 Part 3은 Part 1과 같은 Source·Locale·Navigation 계약을 사용하되 별도 공개 시점을 가질 수 있다. PR 생성, 병합, 배포는 각각 해당 단계의 별도 권한과 최신 검증을 요구한다.
