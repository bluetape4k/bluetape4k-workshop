# Issue 306 Flow 주제 교량 설계

## 문제

Issue #306는 원시 `callbackFlow`, `MutableSharedFlow`, 수동 채널 닫기 및 암시적 재생 버퍼 대신 bluetape4k 주제 유형을 언제 사용해야 하는지 가르치는 콜백-Flow 브리지 워크샵 예제를 요청합니다.

## 소스 증거

- `SubjectApi<T>`은 `emit`, `emitError`, `complete`, `hasCollectors` 및 `collectorCount`를 노출합니다.
- `PublishSubject`은 컬렉터가 활성화된 후에 방출된 값만 브로드캐스트합니다.
- `BehaviorSubject`은 최신 상태를 유지하고 이를 늦은 수집가에게 보냅니다.
- `ReplaySubject`은 버퍼링된 기록을 늦은 수집자에게 재생합니다.
- `MulticastSubject(expectedCollectorSize)`은 생산자가 진행되기 전에 예상되는 수집기 수가 등록될 때까지 기다립니다.
- `UnicastWorkSubject`은 단일 소비자 대기열에 작업을 저장하고 동시 수집기를 거부합니다.
- `kotlin/` 아래의 기존 워크숍 모듈은 모듈-로컬 README 쌍 및 JUnit 5 테스트와 함께 작은 인메모리 예제를 사용합니다.

## 설계

인메모리 코루틴 예제로 `kotlin/flow-extensions-subject-bridge`을 만듭니다. 기본 유형은 읽기 전용 `Flow` 뷰와 콜백 스타일 쓰기 메서드를 노출하는 `DeviceSubjectBridge`입니다.

- `events`: `PublishSubject`이 지원하는 이벤트 전용 스트림.
- `latestState`: `BehaviorSubject`이 지원하는 최신 상태 스트림.
- `history`: 제한된 `ReplaySubject`에 의해 뒷받침되는 재생 가능한 이벤트 기록.
- `multicastEvents`: `MulticastSubject`이 지원하는 조정된 팬아웃입니다.
- `workItems`: `UnicastWorkSubject`에서 지원하는 단일 소비자 작업 대기열.

브리지는 `publishEvent`, `updateState`, `multicastEvent`, `enqueueWork`, `complete*` 및 `fail*`와 같은 메서드 뒤에 주체 돌연변이를 유지합니다. 이는 애플리케이션 코드 전반에 걸쳐 임의의 Subject 돌연변이를 장려하기보다는 브리지 의미론에 초점을 맞춘 예제를 유지합니다.

## 거부된 접근법

1. Raw `callbackFlow`를 기본 구현으로 사용: 기준으로 유용하지만 채널 연결 뒤에 주제 선택 의미가 숨겨집니다.
2. Subject 인스턴스를 공개 속성으로 직접 노출합니다. 코드는 더 짧지만 README 독자가 애플리케이션 전체의 변경 가능한 핫 스트림에 복사하기가 더 쉽습니다.
3. 실제 WebSocket/file 감시자 SDK 콜백을 사용하십시오. 현실적이지만 관련 없는 인프라가 추가되고 주제 계약을 보기가 더 어려워집니다.

## 위험 및 완화

- 핫 스트림 오용: README는 주제가 기본 아키텍처가 아닌 브리지 도구임을 명시적으로 명시합니다.
- 멀티캐스트 내보내기 중단: `multicastEvent` 이전에 `awaitMulticastSubscribers` 호출을 테스트하고 README에서 이 대기 동작을 문서화합니다.
- 모호한 널 터미널 오류: 테스트는 작업 대기열의 `emitError(null)`을 다루고 README는 이것이 `UnicastWorkSubject`에 대한 종료 신호가 아니라고 설명합니다. 정상적인 완료를 위해서는 `complete()`를 사용하세요.
- 동시 수집기 혼동: `UnicastWorkSubject` 테스트는 단일 소비자 동작과 동시 수집기 거부를 보여줍니다.

## 허용 기준 매핑

- 주제 선택 가이드: 모듈 README EN/KO.
- 게시, 동작, 재생, 멀티캐스트, 유니캐스트 예: `DeviceSubjectBridge` 및 테스트.
- Completion/error/null-error 적용 범위: 테스트.
- Before/After callbackFlow 대 주제 설명: README EN/KO.
- 사용된 Bluetape4k 기능 표: README EN/KO.
- 다이어그램: 시나리오, 아키텍처, ERD/domain, 클래스, `docs/images/readme-diagrams` 아래의 시퀀스 자산.
