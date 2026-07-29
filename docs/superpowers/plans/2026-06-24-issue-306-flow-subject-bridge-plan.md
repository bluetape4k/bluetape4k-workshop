# Flow 주제교량 실시계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**목표:** 이슈 #306를 새로운 콜백-Flow 주제 브리지 워크샵 예시로 빌드합니다.

**아키텍처:** `kotlin/flow-extensions-subject-bridge` 아래에 인메모리 Kotlin 모듈 하나를 추가합니다. `DeviceSubjectBridge`은 읽기 전용 `Flow` 뷰를 노출하고 주제 돌연변이 메서드를 소유하므로 README 독자는 전역적으로 변경 가능한 스트림을 복사하지 않고도 선택 의미 체계를 배울 수 있습니다.

**기술 스택:** Kotlin 2.4, Java 21, `bluetape4k-coroutines`, `bluetape4k-junit5`, `bluetape4k-assertions`, CairoSVG-렌더링된 README 다이어그램.

---

### 작업 1: 모듈 뼈대 및 도메인 모델

**파일:**
- 생성: `kotlin/flow-extensions-subject-bridge/build.gradle.kts`
- 생성: `kotlin/flow-extensions-subject-bridge/src/main/kotlin/io/bluetape4k/workshop/flow/subject/bridge/DeviceBridgeDomain.kt`
- 생성: `kotlin/flow-extensions-subject-bridge/src/test/resources/junit-platform.properties`
- 생성: `kotlin/flow-extensions-subject-bridge/src/test/resources/logback-test.xml`

- [ ] `flow-extensions-parallel-enrichment`과 일치하는 종속성을 추가합니다.
- [ ] 직렬화 가능한 도메인 레코드 정의: `DeviceEvent`, `DeviceState`, `WorkItem`.
- [ ] `DeviceStatus` 및 `DeviceEventType` 열거형을 정의합니다.

### 작업 2: 브리지 구현

**파일:**
- 생성: `kotlin/flow-extensions-subject-bridge/src/main/kotlin/io/bluetape4k/workshop/flow/subject/bridge/DeviceSubjectBridge.kt`

- [ ] `PublishSubject`, `BehaviorSubject`, `ReplaySubject`, `MulticastSubject` 및 `UnicastWorkSubject`가 포함된 뒤로 읽기 전용 흐름입니다.
- [ ] 이벤트, 상태, 멀티캐스트, 작업, 완료 및 실패 경로에 대한 콜백 스타일 변형 메서드를 추가합니다.
- [ ] 결정적 테스트 및 README 예제를 위한 `awaitEventSubscribers`, `awaitMulticastSubscribers` 및 `awaitWorkSubscriber` 도우미 메서드를 추가합니다.

### 작업 3: 테스트

**파일:**
- 생성: `kotlin/flow-extensions-subject-bridge/src/test/kotlin/io/bluetape4k/workshop/flow/subject/bridge/DeviceSubjectBridgeTest.kt`

- [ ] PublishSubject 활성-구독자 전달을 테스트합니다.
- [ ] BehaviorSubject 최신 상태 재생을 테스트합니다.
- [ ] ReplaySubject 제한된 후기 기록을 테스트합니다.
- [ ] MulticastSubject 두 명의 구독자 전달을 테스트합니다.
- [ ] UnicastWorkSubject 단일 소비자 대기열 동작 및 동시 수집기 거부를 테스트합니다.
- [ ] 정상 완료, 오류 완료 및 `emitError(null)` 무작동 의미를 테스트합니다.

### 작업 4: README 및 루트 인덱스

**파일:**
- 생성: `kotlin/flow-extensions-subject-bridge/README.md`
- 생성: `kotlin/flow-extensions-subject-bridge/README.ko.md`
- 수정: `README.md`
- 수정: `README.ko.md`

- [ ] 언어 스위치를 추가합니다.
- [ ] 다이어그램이 포함된 시나리오, 아키텍처, domain/class/sequence 섹션을 추가합니다.
- [ ] Before/After 및 주제 선택 가이드를 추가합니다.
- [ ] 사용된 Bluetape4k 기능 테이블 및 테스트 명령을 추가합니다.
- [ ] 루트 비동기 및 반응형 테이블에 모듈을 등록합니다.

### 작업 5: 다이어그램 자산

**파일:**
- 생성: `docs/images/readme-diagrams/kotlin-flow-extensions-subject-bridge-readme-scenario-01.svg/png`
- 생성: `docs/images/readme-diagrams/kotlin-flow-extensions-subject-bridge-readme-architecture-01.svg/png`
- 생성: `docs/images/readme-diagrams/kotlin-flow-extensions-subject-bridge-readme-erd-01.svg/png`
- 생성: `docs/images/readme-diagrams/kotlin-flow-extensions-subject-bridge-readme-class-diagram-01.svg/png`
- 생성: `docs/images/readme-diagrams/kotlin-flow-extensions-subject-bridge-readme-sequence-01.svg/png`

- [ ] 영어 라벨을 사용하세요.
- [ ] 스플라인 같은 곡선이 없고 짧고 둥근 모서리가 있는 직교 커넥터를 사용하십시오.
- [ ] CairoSVG을 통해 PNG을 렌더링하고 전체 크기 PNG를 검사합니다.
- [ ] 형상 및 엔드포인트 감사를 실행합니다.

### 작업 6: 검증 및 검토

**파일:**
- 생성: `docs/review/2026-06-24-issue-306-flow-subject-bridge-review.md`
- 생성: `docs/lessons/2026-06-24-issue-306-flow-subject-bridge.md`

- [ ] `./gradlew :kotlin-flow-extensions-subject-bridge:test`를 실행하세요.
- [ ] `./gradlew :kotlin-flow-extensions-subject-bridge:compileKotlin :kotlin-flow-extensions-subject-bridge:compileTestKotlin`를 실행하세요.
- [ ] `./gradlew projects`을 실행하고 모듈 등록을 확인합니다.
- [ ] `git diff --check`를 실행하세요.
- [ ] 다이어그램 XML/render/geometry/endpoint/visual 유효성 검사를 실행합니다.
- [ ] 통합 7계층 검토를 실행하고 P0/P1 = 0을 기록합니다.
