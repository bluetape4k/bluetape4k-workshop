# 이벤트 리니지 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**목표:** 비즈니스 이벤트 계보 및 감사 추적 재구성을 위한 결정론적 TinkerGraph 워크숍 예시인 `graph/event-lineage`을 구축합니다.

**아키텍처:** 명시적인 인과관계 및 승인 가장자리를 사용하여 이벤트, 집계, 행위자 및 결정을 모델링하는 집중 그래프 모듈을 추가합니다. 이 서비스는 `GraphOperations`, 제한된 순회, bluetape4k 유효성 검사 도우미, 직렬화 가능한 결과 모델, 이중 언어 README 파일 및 README 다이어그램을 사용합니다.

**기술 스택:** Kotlin/JVM, bluetape4k 그래프 코어, bluetape4k 그래프 TinkerPop, TinkerGraph, JUnit 5, bluetape4k-assertions, CairoSVG, repo-local 다이어그램 QA, GitHub 작업 예제 워크플로.

---

## 파일 맵

- `graph/event-lineage/build.gradle.kts` 만들기: 컨테이너가 없는 새로운 그래프 모듈입니다.
- `graph/event-lineage/src/main/kotlin/io/bluetape4k/workshop/graph/eventlineage/schema/EventLineageSchema.kt` 만들기: 정점 및 모서리 레이블 정의.
- `graph/event-lineage/src/main/kotlin/io/bluetape4k/workshop/graph/eventlineage/model/AuditTrail.kt` 생성: 직렬화 가능한 결과 모델.
- `graph/event-lineage/src/main/kotlin/io/bluetape4k/workshop/graph/eventlineage/service/EventLineageService.kt` 생성: 그래프 수명 주기, vertex/edge 생성, 계보 쿼리.
- `graph/event-lineage/src/test/kotlin/io/bluetape4k/workshop/graph/eventlineage/seed/EventLineageSeed.kt` 생성: 결정론적 테스트 시나리오.
- `graph/event-lineage/src/test/kotlin/io/bluetape4k/workshop/graph/eventlineage/AbstractEventLineageTest.kt` 생성: 공유 행동 테스트.
- `graph/event-lineage/src/test/kotlin/io/bluetape4k/workshop/graph/eventlineage/EventLineageTinkerGraphTest.kt`: TinkerGraph 테스트 바인딩을 만듭니다.
- `graph/event-lineage/src/test/resources/junit-platform.properties` 및 `logback-test.xml` 생성: 리소스 기본값을 테스트합니다.
- `graph/event-lineage/README.md` 및 `README.ko.md`: 학습자용 이중 언어 문서를 만듭니다.
- `docs/images/readme-diagrams/graph-event-lineage-readme-architecture-01.svg/png` 및 `graph-event-lineage-readme-sequence-01.svg/png`: README 다이어그램을 만듭니다.
- `README.md`, `README.ko.md`, `AGENTS.md`, `scripts/smoke-validate.sh`, `.github/workflows/Examples.yml` 수정: 모듈 등록 및 CI 적용 범위.
- `docs/review/2026-07-02-issue-330-event-lineage-code-review.md` 및 `docs/lessons/2026-07-02-issue-330-event-lineage.md` 만들기: 검토 및 수업 증거.

## 작업 1: 실패한 동작 테스트 추가

**복잡성:** 중간
**스킬:** `bluetape4k-code-patterns`, `test-driven-development`, `ecc-kotlin-testing`

**파일:**

- 생성: `graph/event-lineage/build.gradle.kts`
- 생성: `graph/event-lineage/src/test/kotlin/io/bluetape4k/workshop/graph/eventlineage/AbstractEventLineageTest.kt`
- 생성: `graph/event-lineage/src/test/kotlin/io/bluetape4k/workshop/graph/eventlineage/EventLineageTinkerGraphTest.kt`
- 생성: `graph/event-lineage/src/test/kotlin/io/bluetape4k/workshop/graph/eventlineage/seed/EventLineageSeed.kt`
- 생성: `graph/event-lineage/src/test/resources/junit-platform.properties`
- 생성: `graph/event-lineage/src/test/resources/logback-test.xml`

- [ ] **1단계: 테스트 종속성이 있는 모듈 빌드 파일 추가**

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
}

tasks.test {
    useJUnitPlatform()
}

dependencies {
    implementation(libs.bluetape4k.graph.core)
    implementation(libs.bluetape4k.graph.tinkerpop)
    implementation(libs.bluetape4k.logging)

    testImplementation(project(":shared"))
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.assertions)
}
```

- [ ] **2단계: 의도한 공공 서비스에 대한 테스트 작성 API**

테스트 이름에는 그래프 구성, 원인 경로, 감사 추적,
대체된 체인, 누락된 링크, 알 수 없는 ID 및 빈 유효성 검사 사례. 사용
`io.bluetape4k.assertions`만 가능합니다.

- [ ] **3단계: 집중 테스트 실행 및 RED 확인**

달리다:

```bash
./gradlew :graph-event-lineage:test --tests '*EventLineageTinkerGraphTest' --console=plain
```

예상: `EventLineageService`, 스키마 및 모델로 인해 컴파일이 실패합니다.
유형이 아직 존재하지 않습니다. 이것은 필수 TDD 빨간색 증명입니다.

## 작업 2: 이벤트 계보 도메인 모델 및 서비스 구현

**복잡성:** 높음
**스킬:** `bluetape4k-code-patterns`, `ecc-kotlin-patterns`

**파일:**

- 생성: `graph/event-lineage/src/main/kotlin/io/bluetape4k/workshop/graph/eventlineage/schema/EventLineageSchema.kt`
- 생성: `graph/event-lineage/src/main/kotlin/io/bluetape4k/workshop/graph/eventlineage/model/AuditTrail.kt`
- 생성: `graph/event-lineage/src/main/kotlin/io/bluetape4k/workshop/graph/eventlineage/service/EventLineageService.kt`

- [ ] **1단계: 스키마 라벨 정의**

`EventLabel`, `AggregateLabel`, `ActorLabel`, `DecisionLabel`를 생성합니다.
`EmitsLabel`, `CausedByLabel`, `ApprovedByLabel`, `DecidedByLabel` 및
`SupersedesLabel`. 백엔드 중립을 위해 타임스탬프와 버전을 문자열로 저장
기존 워크샵 그래프 예제와 일치하는 그래프 속성.

- [ ] **2단계: 직렬화 가능한 결과 모델 정의**

`LineageNode`, `LineagePath`, `ApprovalEvidence` 및
`AggregateAuditTrail`. 모든 데이터 클래스는 `Serializable`을 구현하고 정의합니다.
`serialVersionUID`.

- [ ] **3단계: 서비스 변경자 및 쿼리 구현**

`GraphOperations` 및 `RecommendationService`의 기존 패턴을 사용합니다.
`initialize`, 멱등성 `addEvent`/`addAggregate`/`addActor`/`addDecision`,
가장자리 변형자, `eventsForAggregate`, `causalPath`, `auditTrailForAggregate`,
`supersededChain` 및 `missingCausalLinks`.

- [ ] **4단계: 집중 테스트 실행 및 GREEN 확인**

달리다:

```bash
./gradlew :graph-event-lineage:test --tests '*EventLineageTinkerGraphTest' --console=plain
```

예상: 테스트가 컴파일되고 통과됩니다.

## 작업 3: 학습자 문서 및 다이어그램 추가

**복잡성:** 높음
**스킬:** `bluetape4k-blog`, `bluetape4k-diagram`

**파일:**

- 생성: `graph/event-lineage/README.md`
- 생성: `graph/event-lineage/README.ko.md`
- 생성: `docs/images/readme-diagrams/graph-event-lineage-readme-architecture-01.svg`
- 생성: `docs/images/readme-diagrams/graph-event-lineage-readme-architecture-01.png`
- 생성: `docs/images/readme-diagrams/graph-event-lineage-readme-sequence-01.svg`
- 생성: `docs/images/readme-diagrams/graph-event-lineage-readme-sequence-01.png`

- [ ] **1단계: README 쌍 쓰기**

README 파일에는 언어 전환, 개요, 그래프 계보가 유용한 경우,
일반 감사 테이블 또는 JaVers이 더 나은 경우 도메인 모델 테이블, 핵심 쿼리
테이블, Kotlin 사용법 조각, 확인 명령 및 참고 항목 링크.

- [ ] **2단계: 아키텍처 다이어그램 그리기**

현재 모범 사례 아키텍처 참조, 계층화된 정적 소유권 사용
보기, 레이어 레이블 지우기, 일관된 카드 텍스트 정렬, 둥근 직교
선 스타일이 다른 경우 연결선 및 범례.

- [ ] **3단계: 시퀀스 다이어그램 그리기**

현재의 모범 사례 시퀀스 참조, 번호가 매겨진 라벨, 음소거된 팔레트,
투명한 `alt` 프레임 본체, 가지별 색상 및 마커 화살촉
일치하는 통화 회선 색상.

- [ ] **4단계: PNG 자산 렌더링 및 검사**

달리다:

```bash
~/.local/bin/cairosvg docs/images/readme-diagrams/graph-event-lineage-readme-architecture-01.svg -o docs/images/readme-diagrams/graph-event-lineage-readme-architecture-01.png -s 2
~/.local/bin/cairosvg docs/images/readme-diagrams/graph-event-lineage-readme-sequence-01.svg -o docs/images/readme-diagrams/graph-event-lineage-readme-sequence-01.png -s 2
```

예상: SVG 구문 분석 성공, PNG 렌더링 및 전체 크기 육안 검사 결과
커넥터, 라벨, 마커, 카드 정렬 또는 시퀀스 스타일 결함이 없습니다.

## 작업 4: 리포지토리 표면에 모듈 등록

**복잡성:** 중간
**스킬:** `bluetape4k-code-patterns`

**파일:**

- 수정: `README.md`
- 수정: `README.ko.md`
- 수정: `AGENTS.md`
- 수정: `scripts/smoke-validate.sh`
- 수정: `.github/workflows/Examples.yml`

- [ ] **1단계: 루트 README 그래프 카탈로그 행 추가**

인메모리 인프라를 갖춘 고급 그래프 모듈로 `graph-event-lineage`을 추가하고
event-lineage/audit-trail 학습결과입니다.

- [ ] **2단계: 연기 검증 범위 추가**

`:graph-event-lineage:test`을 `all-smoke`에 추가합니다. 업데이트 부실 확인이 예상됩니다.
`99`부터 `100`까지의 프로젝트 수입니다.

- [ ] **3단계: 예제 워크플로 경로 및 Smoke 작업 적용 범위 추가**

`graph/event-lineage/**`을 push/PR 경로에 추가하고 포함합니다.
`:graph-event-lineage:test`을 `smoke-examples`에 넣고 테스트 결과를 업로드하세요.
아티팩트 경로.

- [ ] **4단계: 작업흐름 검증 YAML**

달리다:

```bash
actionlint .github/workflows/Examples.yml
rg -n "\\\\'" .github/workflows
```

예상됨: actionlint가 전달되고 이스케이프된 GitHub 표현식 따옴표가 없습니다.

## 작업 5: 확인, 검토, 학습 및 PR

**복잡성:** 높음
**스킬:** `verification-before-completion`, `bluetape4k-diagram`, `bluetape4k-code-patterns`

**파일:**

- 생성: `docs/review/2026-07-02-issue-330-event-lineage-code-review.md`
- 생성: `docs/lessons/2026-07-02-issue-330-event-lineage.md`

- [ ] **1단계: 로컬 확인 실행**

달리다:

```bash
./gradlew :graph-event-lineage:test --no-build-cache --rerun-tasks --console=plain
./gradlew :graph-event-lineage:compileKotlin :graph-event-lineage:compileTestKotlin --warning-mode all --console=plain
./gradlew projects --console=plain
./scripts/smoke-validate.sh all-smoke
./scripts/smoke-validate.sh stale-check
./scripts/smoke-validate.sh diagram-qa
git diff --check
```

- [ ] **2단계: 6-R단계 검토 실행**

성능, 안정성, 보안, operator/Ops, developer/API 및
user/caller 렌즈. `P0=0`, `P1=0` 및 P2/P3 결정을 기록합니다.
`docs/review/2026-07-02-issue-330-event-lineage-code-review.md`.

- [ ] **3단계: PR 이전에 강의 커밋**

컨텍스트를 사용하여 `docs/lessons/2026-07-02-issue-330-event-lineage.md`을 생성하고,
결정, 결과, 검증, 다이어그램 QA 증거, 미래 가드.

- [ ] **4단계: PR 커밋, 푸시, 생성 및 PR 메타데이터 확인**

Lore 예고편으로 커밋하고 기능 분기를 푸시하고 영어 PR를 만듭니다.
종료 #330, 마일스톤 설정 `1.3.1`, 담당자 `debop`, 미러 이슈 라벨 및
실시간 PR 본문 마지막 섹션이 `## DoD Status`인지 확인합니다.

- [ ] **5단계: post-PR 검토 및 CI 게이트 실행**

PR diff 검토를 실행하고 CI을 기다린 후 PR DoD을 업데이트하고 9단계 증거를 다음으로 보고합니다.
사용자. 사용자가 병합을 요청한 후에만 병합합니다.
