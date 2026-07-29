# 이슈 555 엄격한 준수 수리 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use inline execution in this session. Steps use checkbox (`- [ ]`) syntax for tracking.

**목표:** 해당 Bluetape Kotlin, Spring, Exposed, 테스트, 모듈, 문서 및 다이어그램 계약을 준수하는 사용량 청구 참조를 만듭니다.

**아키텍처:** 독립적으로 배포 가능한 5개의 서비스 경계와 해당 로컬 JSON 코덱을 유지합니다. 반복되는 낮은 수준의 검증을 작은 서비스별 디코더 도우미로 대체하고, 내구성 있는 모델 직렬화를 명시적으로 만들고, Kafka 상태 전환 시 작업 로깅을 추가합니다. 일반 스테이지 카드 비주얼을 소스 지원 아키텍처 및 시퀀스 다이어그램으로 대체합니다. SVG/PNG 육안 검사는 여전히 유효합니다.

**기술 스택:** Kotlin 2.4, Spring Boot 4, Kafka, PostgreSQL, JetBrains Exposed, `bluetape4k-exposed-jdbc`, Bluetape assertions/logging/Testcontainers, CairoSVG.

---

## 파일 맵

- 수정: `commerce/usage-billing-*-service/` 아래의 5개 서비스 `domain/`, `integration/`, `messaging/`, `application/` 및 `config/` Kotlin 소스.
- 수정: `commerce/usage-billing-*-service/src/test/` 및 `commerce/usage-billing-microservices-composition-tests/src/test/`에서 서비스 및 구성 테스트를 수행합니다.
- 수정: `docs/images/readme-diagrams/usage-billing-microservices-*.svg/png`, `scripts/generate-usage-billing-microservices-diagrams.mjs` 및 두 구성 README 모두.
- 수정: 새로운 증거 이후 `docs/review/2026-07-23-issue-555-usage-billing-microservices-review.md`, 강의 및 PR 본문.

### 작업 1: 엄격한 Kotlin 패턴 회귀 잠금

- [x] 직렬화 가능한 내구성 계약, 공용 봉투의 KDoc, 디코더의 Bluetape 유효성 검사 도우미 및 consumer/quarantine 전환 시 `KLogging`가 필요한 아키텍처 테스트를 추가합니다.
- [x] 추가된 테스트를 실행하고 현재 소스에 대한 오류를 관찰합니다.
- [x] 가장 작은 소스 변경 사항을 구현합니다. `Serializable` 및 `serialVersionUID`, 명명된 검증된 값, `KLogging` 및 페이로드나 자격 증명이 없는 명시적 terminal/replay 로그를 구현합니다.
- [x] `runCatching` 예외 검증문과 순수 `check` 폴링 검증문을 Bluetape 검증 API으로 대체합니다.
- [x] 영향을 받는 각 서비스 테스트와 구성 기본 테스트를 실행합니다.

### 작업 2: Exposed/Spring/module 경계 유지

- [x] 모든 구체적인 지속성 저장소가 여전히 `ExposedJdbcRepository`을 구현하는지 확인합니다. 원시 JDBC/SQL 경로를 유지하지 않습니다.
- [x] Kafka 수신기 실패가 내구성 있는 inbox/quarantine 결정을 유지하고 blocking/coroutine/transaction 소유권 드리프트를 유발하지 않는지 확인합니다.
- [x] 저장소 아키텍처 테스트, `detekt`, `detektTest` 및 순차 구성 통합 매트릭스를 실행합니다.

### 작업 3: 다이어그램 생성기를 소스 지원 자산으로 교체

- [x] 아키텍처와 시간 순서 자산을 분류하고 중복된 표준 자산을 거부하는 실패한 다이어그램 manifest/audit을 추가합니다.
- [x] 일반 `stageDiagram` 템플릿을 하나의 아키텍처 책임 보기와 참가자, 수명선, 활성화, 번호가 매겨진 메시지 및 소스 동작이 분기되는 분기 프레임을 포함하는 4개의 시퀀스 스타일 흐름으로 바꿉니다.
- [x] 명시적 패치를 통해 중복된 사용되지 않은 `usage-billing-microservices-state-01` SVG/PNG를 제거합니다. 표준 `outbox-inbox-state` 자산을 유지합니다.
- [x] 변경된 모든 PNG을 전체 크기로 개별적으로 렌더링하고 검사합니다. 자산별 크기, marker/connector 개수, 소스 경로 및 관찰 내용을 기록합니다.
- [x] 각 관련 자산에 대해 XML, CairoSVG, 텍스트, 마커, 형상, 엔드포인트, 커넥터, 혼합 코너 및 시퀀스 스타일 감사를 실행합니다.

### 작업 4: 증거, 문서 및 PR 진실성 복원

- [x] 수정된 소스 및 다이어그램 세트와 일치하도록 English/Korean README 포함 및 문장을 업데이트하세요.
- [x] 잘못된 다이어그램 원장을 자산별 체크리스트 증거로 교체하고 모든 검사를 나열하십시오.
- [x] 실패 모드로 교훈을 업데이트하세요. 기계적 SVG 검사는 다이어그램 종류나 PNG 가독성을 입증하지 않습니다.
- [x] README 유효성 검사기, workflow/module 오래된 검사, actionlint, `git diff --check` 및 6개 렌즈 인라인 검토를 실행합니다.
- [x] Lore 트레일러로 수리된 스코프를 커밋하고, 정확한 헤드를 밀고, PR #557의 마지막 `## DoD Status`을 새로 고칩니다. 라이브 CI까지 초안을 유지하고 수렴을 검토하세요.

## 검증 순서

1. 엄격한 계약을 맺은 경비원을 대상으로 한 RED/GREEN 단위 테스트입니다.
2. 5가지 서비스 테스트 작업 및 구성 기본 테스트.
3. 순차적 Testcontainers 구성 통합 테스트.
4. 감지 및 architecture/raw-access 검색.
5. 자산별 SVG/PNG 감사 원장 및 전체 크기 육안 검사.
6. README/workflow 확인, `actionlint`, `git diff --check`, 실시간 PR 정확한 머리 검증.

## 위험 및 롤백

- 이 예에서 직렬화는 마커 계약입니다. Java 직렬화 전송이 도입되지 않았습니다. 프레임워크 바인딩이 예기치 않게 변경되는 경우 marker/UID 추가만 되돌립니다.
- Kafka 로깅은 이벤트ID, 테넌트, 이벤트 유형 및 안정적인 결과만 유지해야 합니다. 페이로드를 기록하지 마십시오. 데이터가 공개될 수 있는 경우 로그 필드를 되돌립니다.
- 다이어그램 교체는 표준 파일 이름을 유지하므로 README 링크는 안정적으로 유지됩니다. 쌍을 이루는 SVG에서만 PNG를 재생성하고 SVG/PNG 불일치를 거부합니다.
