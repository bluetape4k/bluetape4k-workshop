# 스프링 모듈리스 모듈 경계 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> 초능력:하위 에이전트 중심 개발(권장) 또는
> 초능력:이 계획을 작업별로 구현하기 위한 계획 실행. 단계 사용
> 추적을 위한 확인란(`- [ ]`) 구문입니다.

**목표:** 학습자 지향 Spring `:spring-modulith-module-boundaries` 빌드
허용된 모듈 종속성을 검증하고 증명하는 모듈리스 워크숍 예시
모듈 간 통신은 내보낸 API 또는 이벤트를 통해 발생합니다.

**아키텍처:** 4개의 인메모리 애플리케이션 모듈. `ordering`에만 의존함
`catalog :: api`에; `payment` 및 `notification`는 다음에만 의존합니다.
`ordering :: events`. 테스트 전용 유효하지 않은 고정 장치는 `ordering`을 가져옵니다.
`payment`의 내부 유형이며 `ApplicationModules.verify()`에 실패해야 합니다.
`Violations`.

**기술 스택:** Kotlin 2.3, Java 21, Spring Boot 4, 스프링 계수 2.1,
`spring-modulith-starter-core`, `spring-modulith-starter-test`, JUnit 5,
bluetape4k 어설션, 생성된 SVG/PNG README 다이어그램.

---

## 파일 구조

- `spring-modulith/module-boundaries/build.gradle.kts`를 생성합니다.
- `spring-modulith/module-boundaries/README.md`를 생성합니다.
- `spring-modulith/module-boundaries/README.ko.md`를 생성합니다.
- 아래에서 프로덕션 패키지를 생성합니다.
  `spring-modulith/module-boundaries/src/main/kotlin/io/bluetape4k/workshop/spring/modulith/boundaries/`.
- 테스트 패키지 생성
  `spring-modulith/module-boundaries/src/test/kotlin/io/bluetape4k/workshop/spring/modulith/boundaries/`.
- `src/test/resources/junit-platform.properties` 및 `logback-test.xml`를 생성합니다.
- 다이어그램 자산 만들기:
  - `docs/images/readme-diagrams/spring-modulith-module-boundaries-readme-architecture-01.{svg,png}`
  - `docs/images/readme-diagrams/spring-modulith-module-boundaries-readme-sequence-01.{svg,png}`
- 루트 `README.md`, `README.ko.md`, `.github/workflows/Examples.yml` 수정,
  그리고 `scripts/smoke-validate.sh`.

## 작업 1: 뼈대 및 실패한 테스트

**파일:**

- 생성: `spring-modulith/module-boundaries/build.gradle.kts`
- 생성: `spring-modulith/module-boundaries/src/main/kotlin/.../ModuleBoundariesApplication.kt`
- 생성: `spring-modulith/module-boundaries/src/test/kotlin/.../ApplicationModuleBoundaryTest.kt`
- 생성: 테스트 전용 유효하지 않은 픽스쳐 패키지.

- [ ] `spring-modulith-starter-core`을 사용하여 모듈 빌드 종속성을 추가합니다.
  `spring-modulith-starter-test`, Spring Boot 테스트 및 bluetape4k 어설션.
- [ ] 최소한의 Spring Boot 애플리케이션 진입점을 추가합니다.
- [ ] 실패한 유효한 경계 확인 테스트 작성:

```kotlin
ApplicationModules.of(ModuleBoundariesApplication::class.java).verify()
```

- [ ] 실패한 잘못된 픽스처 확인 테스트 작성:

```kotlin
val violations = assertFailsWith<Violations> {
    ApplicationModules.of(
        InvalidBoundaryApplication::class.java,
        ImportOption.Predefined.DO_NOT_INCLUDE_JARS,
    ).verify()
}
violations.message shouldContain "LeakyOrderRepository"
```

- [ ] 달리다
  `./gradlew :spring-modulith-module-boundaries:test --console=plain --max-workers=1`
  구현하기 전에 예상되는 빨간색 결과를 기록합니다.

## 작업 2: 생산 모듈 메타데이터 및 도메인 Flow

**파일:**

- `catalog`, `ordering`, `payment` 및 `notification` 프로덕션 생성
  패키지.
- `@ApplicationModule` 및 `@NamedInterface`에 대한 메타데이터 클래스를 만듭니다.

- [ ] `catalog.api.CatalogLookup`을 구현하고
  `catalog.api.CatalogItemSnapshot`.
- [ ] `catalog.internal.InMemoryCatalogRepository`을 구현합니다.
- [ ] `ordering.OrderRequest`, `ordering.OrderReceipt`을 구현하고
  `ordering.OrderingService`.
- [ ] 내보낸 이벤트로 `ordering.events.OrderPlacedEvent`을 구현합니다.
  계약.
- [ ] `payment.PaymentEventHandler` 및 `PaymentLedger`을 구현합니다.
- [ ] `notification.NotificationEventHandler`을 구현하고
  `NotificationOutbox`.
- [ ] 선언된 명명된 인터페이스 내에서 모든 모듈 간 상호 작용을 유지합니다.
- [ ] 공개 유형에 간결한 영어 KDoc을 추가하고 공개 데이터 클래스를 만듭니다.
  `Serializable`.

## 작업 3: 통합 테스트

**파일:**

- 생성 또는 업데이트:
  `spring-modulith/module-boundaries/src/test/kotlin/.../OrderEventFlowTest.kt`

- [ ] `OrderingService.placeOrder()` 게시를 증명하는 이벤트 흐름 테스트 추가
  `OrderPlacedEvent` 결제 및 알림 모듈 모두 반응 없이 반응합니다.
  직접 서비스 호출.
- [ ] 누락된 카탈로그 항목 및 비양성 항목에 대한 검증 테스트를 추가합니다.
  수량.
- [ ] 대상 테스트를 실행하고 친환경에 필요한 프로덕션 코드만 수정하세요.

## 작업 4: 문서 및 다이어그램

**파일:**

- `spring-modulith/module-boundaries/README.md`를 생성합니다.
- `spring-modulith/module-boundaries/README.ko.md`를 생성합니다.
- 새 자산에 대한 다이어그램 생성 스크립트를 생성하거나 업데이트합니다.
- 아래에서 SVG/PNG 아키텍처 및 시퀀스 다이어그램을 생성합니다.
  `docs/images/readme-diagrams/`.

- [ ] README.md 및 README.ko.md에는 언어 전환, 아키텍처 다이어그램,
  시퀀스 다이어그램, 종속성 규칙, 이벤트 계약 설명 및 실패
  해석.
- [ ] 아키텍처 다이어그램에는 레이어 그룹화, 일관된 카드 정렬,
  solid/event/rejected 가장자리에 대한 범례, 공식 스타일 스프링 모듈리스 레이블,
  둥근 직교 커넥터이며 모호한 커넥터 교차가 없습니다.
- [ ] 시퀀스 다이어그램은 음소거된 팔레트, 중앙에 있는 카드,
  라인 위에 번호가 매겨진 통화 라벨, 통화 라인을 덮지 않는 라벨, 일치
  화살촉 및 선 색상, 둥근 직교 경로, 투명 그룹
  지역 및 레이아웃이 겹치지 않습니다.
- [ ] 두 새 SVG 모두에서 명시적 다이어그램 QA 래퍼를 실행하고 전체를 검사합니다.
  PNG시각적으로요.

## 작업 5: 리포지토리 등록

**파일:**

- `README.md`을 수정하세요.
- `README.ko.md`을 수정하세요.
- `.github/workflows/Examples.yml`을 수정하세요.
- `scripts/smoke-validate.sh`을 수정하세요.

- [ ] 두 로캘의 루트 모듈 카탈로그에 새 모듈을 추가합니다.
- [ ] 예제 워크플로 연기에 새 모듈 경로와 Gradle 작업을 추가합니다.
  적용 범위.
- [ ] `scripts/smoke-validate.sh` `all-smoke`에 새 모듈을 추가하고
  `spring-boot`개 그룹.
- [ ] `./gradlew projects --console=plain` 이후 오래된 프로젝트 수 업데이트
  예상 개수를 증명합니다.

## 작업 6: 확인

증거를 실행하고 기록합니다.

- [ ] `./gradlew :spring-modulith-module-boundaries:test --console=plain --max-workers=1 --rerun-tasks`
- [ ] `./gradlew projects --console=plain`
- [ ] `node scripts/validate-readme-language.mjs`
- [ ] `node scripts/validate-readme-parity.mjs`
- [ ] `node scripts/validate-readme-diagram-qa.mjs docs/images/readme-diagrams/spring-modulith-module-boundaries-readme-architecture-01.svg docs/images/readme-diagrams/spring-modulith-module-boundaries-readme-sequence-01.svg`
- [ ] 생성된 두 다이어그램 모두에 대한 전체 크기 PNG 육안 검사.
- [ ] `bash scripts/smoke-validate.sh stale-check`
- [ ] `actionlint .github/workflows/Examples.yml`
- [ ] `git diff --check`

## 작업 7: 검토, 강의, PR

- [ ] 6-R단계 검토 게이트를 실행하고 P0/P1/P2 문제를 해결하세요.
- [ ] `docs/lessons/2026-07-01-issue-323-module-boundaries.md`을 추가합니다.
  상황, 결정, 결과, 확인 증거 및 향후 에이전트 메모.
- [ ] Lore 커밋 프로토콜을 사용하여 커밋합니다.
- [ ] 분기를 푸시하고 `debop`에 할당된 `develop`에 대해 PR를 만듭니다.
  마일스톤 `1.3.1` 및 미러링된 이슈 라벨이 있습니다.
- [ ] 라이브 PR 메타데이터와 본문을 확인합니다. 최종 Markdown `##` 섹션은 다음과 같아야 합니다.
  `## DoD Status`이세요.
- [ ] 필요한 CI을 기다리고 병합 준비 상태를 보고합니다. 다음 경우가 아니면 병합하지 마세요.
  사용자가 명시적으로 병합을 요청합니다.
