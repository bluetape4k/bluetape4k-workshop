# Issue #323 - 스프링 모듈리스 모듈 경계 설계

## 문맥

`bluetape4k-workshop` 마일스톤 `1.3.1`에 문제가 포함됨
[#323](https://github.com/bluetape4k/bluetape4k-workshop/issues/323),
모듈 경계에 초점을 맞춘 학습자 대상 Spring Modulith 예제를 요청합니다.
확인.

예시에는 다음이 표시되어야 합니다.

- 4개의 애플리케이션 모듈: `catalog`, `ordering`, `payment` 및
  `notification`.
- 명시적으로 허용되는 종속성 방향.
- 스프링 계수 `ApplicationModules.verify()`를 확인합니다.
- 경계 테스트가 의미가 있음을 입증하는 거부된 직접 종속성입니다.
- 직접 서비스 호출 대신 모듈 간 이벤트 게시.
- 영어 및 한국어 README 파일과 가시성을 설명하는 다이어그램,
  이벤트 계약 및 리팩토링 신호.
- 외부 인프라가 없는 로컬 결정론적 테스트.

## 현재 증거

- 게시 중인 이슈 #323가 열려 있고 `debop`에 할당되어 있으며 문서용으로 라벨이 지정되어 있습니다.
  Spring Boot, 아키텍처 확장 작업 및 마일스톤에 첨부
  `1.3.1`.
- `settings.gradle.kts`은 `spring-modulith/` 아래의 하위 모듈을 자동 등록합니다.
  그래서 `spring-modulith/module-boundaries`은
  `:spring-modulith-module-boundaries`.
- `spring-modulith/jpa-demo`은(는) 이미 사용 중입니다.
  `ApplicationModules.of(SpringModulith::class.java).verify()`, 하지만 그렇지 않습니다.
  허용된 종속성 규칙 또는 의도적으로 유효하지 않은 고정 장치를 분리합니다.
- `spring-modulith/events-deep-dive`은(는) 이미 이벤트 게시를 가르치고 있으며
  `@ApplicationModuleTest`, 경계검증에 중점을 둔 연구실은 아닙니다.
- Spring Modulith 문서는 `ApplicationModules.verify()`을 확인합니다.
  사이클, 내부 유형에 대한 액세스 등을 포함한 모듈 배열 규칙을 확인합니다.
  그리고 의존성을 허용했습니다. Kotlin 모듈은 다음을 사용하여 패키지 메타데이터를 선언할 수 있습니다.
  `@ApplicationModule` 주석이 달린 package-local `@PackageInfo` 클래스 또는
  `@NamedInterface`.
- `exposed-workshop` DDD 모듈리스 경계 수업은 유용한 패턴임이 입증되었습니다.
  유효한 모듈 그래프와 다른 것을 가져오는 테스트 전용 유효하지 않은 고정물
  모듈의 내부 유형이며 Spring Modulith `Violations`에서 실패합니다.

## 승인된 방향

인메모리 모듈을 사용하여 새 `spring-modulith/module-boundaries` 모듈을 만듭니다.
작업 흐름. H2, PostgreSQL, Redis, Kafka 또는 기타 인프라를 사용하지 마십시오.
issue #323는 특히 모듈 가시성 및 이벤트 계약에 관한 것이기 때문에
끈기가 아닙니다.

이 예제에서는 Spring의 이벤트 게시자와 Spring Modulith 모듈을 사용합니다.
메타데이터. 이는 여전히 학습 경로를 결정적으로 유지하면서
아키텍처 규칙: 모듈은 내보낸 API 또는 이벤트를 통해 통신합니다.
내부 패키지에 도달합니다.

## 건축학

유효한 애플리케이션 그래프는 다음과 같습니다.

1. `catalog`은(는) 항목 가용성을 소유하고 이름이 지정된 `catalog :: api`를 내보냅니다.
   읽기 전용 카탈로그 조회를 위한 인터페이스입니다.
2. `ordering`은 `catalog :: api`에만 의존하고 주문 요청을 검증하며
   `ordering.events.OrderPlacedEvent`를 게시합니다.
3. `payment`은 `ordering :: events`에만 의존하며 결제를 기록합니다.
   이벤트 페이로드의 승인.
4. `notification`은 `ordering :: events`에만 의존하며 고객을 기록합니다.
   이벤트 페이로드의 알림.

잘못된 픽스처는 테스트 전용이며 의도적으로 `ordering`을 가져옵니다.
`payment`의 내부 유형입니다. `ApplicationModules.verify()`은(는) 이를 거부해야 합니다.
`Violations`으로 고정합니다.

## 디자인 대안

### 옵션 A - 새로운 인메모리 경계 검증 랩

이것이 선택된 접근 방식입니다.

장점:

- 이슈 #323와 직접 일치합니다.
- 테스트를 빠르고 결정적으로 유지합니다.
- 모듈 가시성을 방해하는 지속성 세부 사항을 방지합니다.
- README 다이어그램이 허용된 종속성과 거부된 가져오기에 집중할 수 있도록 합니다.

단점:

- 트랜잭션 이벤트 게시 또는 이벤트 게시를 보여주지 않습니다.
  기재. 이러한 개념은 인접한 Spring Modulith에서 이미 다루고 있습니다.
  예.

### 옵션 B - 확장 `spring-modulith/jpa-demo`

장점:

- 기존 Spring Modulith 애플리케이션 구조를 재사용합니다.

단점:

- 경계 확인과 JPA 문제를 혼합합니다.
- 학습자가 시연되는 규칙을 분리하는 것이 더 어렵습니다.
- 요청된 내용을 전달하는 대신 이전 예제를 변경할 위험이 있습니다.
  기준 치수.

### 옵션 C - 지속성이 많은 DDD 경계 패턴 복사

장점:

- 이전 `exposed-workshop` 강의와 유사하며 더 많은 내용이 포함될 수 있습니다.
  도메인 논리.

단점:

- 인프라 및 지속성 노이즈를 추가합니다.
- 로컬 결정론적 실행에 대한 이슈 요구 사항과 충돌합니다.

## 모듈 형태

예상 파일:

- `spring-modulith/module-boundaries/build.gradle.kts`
- `spring-modulith/module-boundaries/README.md`
- `spring-modulith/module-boundaries/README.ko.md`
- `spring-modulith/module-boundaries/src/main/kotlin/io/bluetape4k/workshop/spring/modulith/boundaries/`
- `spring-modulith/module-boundaries/src/test/kotlin/io/bluetape4k/workshop/spring/modulith/boundaries/`
- `spring-modulith/module-boundaries/src/test/resources/junit-platform.properties`
- `spring-modulith/module-boundaries/src/test/resources/logback-test.xml`
- README 다이어그램 SVG/PNG `docs/images/readme-diagrams/` 아래 자산

예상 패키지 접두사:

```text
io.bluetape4k.workshop.spring.modulith.boundaries
```

## 모듈 계약

`catalog`:

- `catalog.api.CatalogItemSnapshot`
- `catalog.api.CatalogLookup`
- `catalog.internal.InMemoryCatalogRepository`
- `catalog.api`의 `@NamedInterface("api")` 메타데이터

`ordering`:

- `ordering.OrderingService`
- `ordering.OrderRequest`
- `ordering.events.OrderPlacedEvent`
- `ordering.internal.OrderNumberGenerator`
- `@ApplicationModule(allowedDependencies = ["catalog :: api"])`
- `ordering.events`의 `@NamedInterface("events")` 메타데이터

`payment`:

- `payment.PaymentEventHandler`
- `payment.PaymentLedger`
- `@ApplicationModule(allowedDependencies = ["ordering :: events"])`

`notification`:

- `notification.NotificationEventHandler`
- `notification.NotificationOutbox`
- `@ApplicationModule(allowedDependencies = ["ordering :: events"])`

모든 공개 클래스와 인터페이스에는 간결한 영어 KDoc이 포함되어야 합니다. 공공의
데이터 클래스는 `Serializable`을 구현하고 `serialVersionUID`을 정의해야 합니다.

## 테스트 전략

필수 테스트:

- 유효한 그래프 테스트: `ApplicationModules.of(ModuleBoundariesApplication::class.java).verify()`.
- 잘못된 그래프 테스트: 결제를 가져오는 테스트 전용 애플리케이션 픽스처
  `ordering.internal.LeakyOrderRepository`; 확인이 실패해야 합니다.
  스프링 계수 `Violations`.
- 이벤트 흐름 테스트: 주문을 하면 `OrderPlacedEvent`이 게시됩니다. 결제 및
  알림 핸들러는 모듈 소유의 인메모리 상태를 업데이트하지 않고 업데이트합니다.
  직접 서비스 호출.
- 누락된 카탈로그 항목과 같은 결정론적 검증을 위한 가드 테스트
  양수가 아닌 수량.

유효하지 않은 고정 장치는 테스트 소스 패키지 아래에 있어야 생산이 가능합니다.
모듈 그래프는 깨끗하게 유지됩니다.

## 문서 및 다이어그램

README 파일은 다음을 설명해야 합니다.

- 애플리케이션 모듈 및 내보낸 명명된 인터페이스.
- 허용되는 종속성 방향.
- `internal` 패키지로 직접 가져오기가 거부되는 이유.
- 이벤트 계약이 결합을 줄이는 방법.
- 실패한 경계 테스트가 리팩토링을 안내하는 방법

다이어그램:

- `spring-modulith-module-boundaries-readme-architecture-01`: 계층화된 모듈
  허용된 종속성, 이벤트 계약 및 거부에 대한 범례가 있는 그래프
  고정 장치 가장자리.
- `spring-modulith-module-boundaries-readme-sequence-01`: 주문 배치
  번호가 매겨진 호출 라벨, 이벤트 전달 및 투명한 시퀀스
  거부된 경계 분기.

SVG 및 PNG 파일 모두 `bluetape4k-diagram` 체크리스트를 통과해야 합니다.
repo-local 다이어그램 QA 래퍼 및 전체 크기 PNG 육안 검사.

## 위험

- Kotlin 패키지 메타데이터 클래스는 올바른 패키지에 배치되어야 합니다. 에이
  잘못 배치된 `@ApplicationModule` 또는 `@NamedInterface`은 테스트를 약화시킵니다.
- 생산 패키지를 가져오는 네거티브 픽스처는 실수로 생성될 수 있습니다.
  관련 없는 위반. 테스트 전용 루트 패키지에 자체 포함된 상태로 유지하세요.
- `@EventListener` 처리는 기본적으로 동기식입니다.
  이 결정론적 워크숍. README는 거래 공개를 암시해서는 안 됩니다.
  레지스트리 의미론.
- 다이어그램 스타일 드리프트는 알려진 실패 모드입니다. 시퀀스 다이어그램은 다음을 사용해야 합니다.
  현재 모범 사례 팔레트, 중앙에 있는 카드 텍스트, 일치하는 라인 및
  화살촉 색상, 선 위의 번호가 매겨진 레이블, 둥근 직교 경로,
  투명하게 그룹화된 영역.

## 수락 기준

- `:spring-modulith-module-boundaries:test` 통과.
- `ApplicationModules.verify()`이(가) 유효한 신청서에 합격했습니다.
- 유효하지 않은 고정 장치는 `Violations`과 함께 실패합니다.
- 이벤트 흐름 테스트는 주문 이벤트를 통해 결제 및 알림이 반응함을 입증합니다.
- `README.md` 및 `README.ko.md`은 소스와 동일하며 포함이 생성됩니다.
  SVG 소스가 있는 PNG 다이어그램.
- 루트 README 모듈 카탈로그, 예제 워크플로 및 연기 검증 스크립트
  새 모듈을 포함합니다.
- 다이어그램 QA, README 검증, `./gradlew projects`, 타겟 테스트 및
  `git diff --check` PR 생성 전에 통과하세요.
