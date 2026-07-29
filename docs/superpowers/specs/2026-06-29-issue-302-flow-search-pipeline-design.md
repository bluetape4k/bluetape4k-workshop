# 이슈 302 Flow 파이프라인 설계 검색

## 문제

Issue #302에서는 `bluetape4k-coroutines` Flow 확장이 포함된 실시간 search/autocomplete 파이프라인을 가르치는 워크숍 예시를 요청합니다. 이 예는 학습자가 변경 가능한 쿼리 상태, 임시 `Job` 취소, 외부 설정 읽기 및 광범위한 `try/catch` 블록을 읽을 수 있는 Flow 구성으로 대체하는 데 도움이 됩니다.

예제는 메모리에 있습니다. HTTP, 데이터베이스, 캐시 또는 Testcontainers 인프라를 추가하면 안 됩니다. 학습자를 향한 표면은 `kotlin/`, 이중 언어 README 파일, 소스 기반 다이어그램 및 결정적 코루틴 테스트 아래의 작은 모듈입니다.

## 소스 증거

- 기존 Flow 워크샵 모듈은 `kotlin/flow-extensions-*` 아래에 있으며 작은 인메모리 도메인, 모듈-로컬 README 쌍, JUnit 5 코루틴 테스트 및 루트 README 등록을 사용합니다.
- `bufferingDebounce(timeout)`은 디바운스 기간 동안 항목을 일괄 처리하고 버퍼링된 목록을 내보냅니다. 업스트림이 실패하면 원래 실패를 전파하기 전에 버퍼링된 값을 내보냅니다.
- `withLatestFrom(other)`은 소스 값을 다른 Flow의 최신 항목과 결합합니다. `other`이 적어도 한 번 방출될 때까지는 아무것도 방출하지 않습니다.
- `takeUntil(other)`은 알리미Flow가 방출될 때 소스Flow를 중지합니다.
- `Flow<T>.log(tag)`은 Flow 값 계약을 변경하지 않고 디버그 수명 주기 로깅을 추가합니다. 학습자가 디버그 후크를 실제 서비스에 복사하는 경우가 많기 때문에 `log`을 통과하는 값에는 수정된 문자열 렌더링이 있어야 합니다.
- `flatMapDrop` 및 `flatMapFirst`은 대체 연산자가 아닙니다. 현재 계약은 내부 Flow가 실행되는 동안 새로운 업스트림 값을 무시하는 것입니다.
- Kotlin 표준 `flatMapLatest`은 이미 `kotlin/coroutines` 예시에 나타나 있으며 원하는 자동 완성 대체 계약이 있습니다. 최신 쿼리는 이전의 기내 검색을 취소합니다.

## 설계

`kotlin/flow-extensions-search-pipeline`을 인메모리 Kotlin 모듈로 생성합니다.

주요 유형은 `SearchPipeline`입니다. 다음을 허용합니다.

- `queries: Flow<String>`
- `settings: Flow<SearchSettings>`
- `sessionClosed: Flow<Unit>`

`settings` Flow는 쿼리 이벤트가 수집되기 전에 초기 값을 내보낸 호출자 소유의 hot/state-like Flow입니다. README는 `MutableStateFlow(initialSettings)`을 안전한 설정으로 표시하고 `withLatestFrom(settings)`가 첫 번째 설정 값이 존재할 때까지 쿼리 값을 삭제한다고 설명해야 합니다.

`sessionClosed` Flow는 API 경계에서 콜드일 수 있지만 `SearchPipeline`은(는) 컬렉션당 한 번씩 이를 단일 공유 중지 신호로 정규화해야 합니다. 어댑터 취소 레이스와 외부 터미널 가드는 모두 해당 공유 신호를 관찰하므로 원샷 닫기가 이중 수집되거나 누락되지 않습니다.

파이프라인은 다음을 수행합니다.

1. 빈 쿼리 입력을 자르고 필터링합니다.
2. 빠른 타이핑 버스트를 수집하려면 `bufferingDebounce`을 사용하세요.
3. 비어 있지 않은 각 버스트에서 최신 쿼리를 선택합니다.
4. 모든 검색에서 최신 테넌트, 로캘, 모드, 결과 제한 및 기능 플래그를 사용하도록 `withLatestFrom(settings)`을 사용하세요.
5. 자동 완성 대체 의미 체계에는 `flatMapLatest`을 사용하세요. 새로운 쿼리는 이전 기내 검색을 취소합니다.
6. 각 일시 중지 어댑터 검색을 `sessionClosed`에 대해 경주하여 세션을 종료하면 활성 검색이 반환되기 전에 취소됩니다.
7. 취소 인식 어댑터 레이스 후 request/result 흐름에 대한 외부 터미널 가드로 `takeUntil(sessionClosed)`를 사용합니다.
8. 도메인 값이 수정된 `toString()` 출력을 제공한 후에만 `Flow.log()`을 학습 가능한 디버그 후크로 사용하세요. 원시 쿼리 텍스트, 테넌트 ID, 기능 플래그, 소스 메타데이터 및 결과 콘텐츠는 디버그 수명 주기 로그에 표시되어서는 안 됩니다.

검색 어댑터 경계는 명시적입니다. `SearchPipeline`은 `SearchAdapter`에 종속되고 `FakeSearchAdapter`는 구현 예입니다. 가짜 어댑터는 일시 중지를 인식하고, 시뮬레이션된 대기 시간에 `delay`을 사용하고, `Thread.sleep`로 차단하지 않으며, 실제 외부 서비스 없이 취소 및 실패 동작을 입증하기 위한 테스트용 결정적 후크를 노출합니다. 제한된 일반 문자열 일치만으로 고정된 인메모리 카탈로그를 검색합니다. 학습자 제어 정규식을 컴파일하거나, SQL와 유사한 표현식을 평가하거나, 리플렉션을 사용하거나, 스크립트를 실행하거나, 원시 입력에서 동적 쿼리 DSL을 구축해서는 안 됩니다.

## 도메인 모델

- `SearchQuery`: 공백이 아닌 정규화된 쿼리 텍스트로, 공용 팩토리를 통해 잘리고 64자로 제한됩니다.
- `SearchSettings`: 테넌트 ID, 로캘, 검색 모드, 기능 플래그, 결과 제한.
- `SearchRequest`: 요청이 시작될 때의 쿼리와 설정입니다.
- `SearchResult`: 쿼리 텍스트, 설정 스냅샷, 순위가 매겨진 히트 목록 및 소스 메타데이터.
- `SearchHit`: ID, 제목, 점수.
- `SearchMode`: `PREFIX`, `FUZZY`, `EXACT`.

호출자에게 표시되는 불변성:

- `SearchQuery.text`은 잘리고 공백이 아니며 최대 64자입니다. 공공 건설은 정식 트림 값을 저장합니다.
- `SearchSettings.tenantId`은 잘리고 공백이 아니며 최대 64자입니다. 공공 건설은 정식 트림 값을 저장합니다.
- `SearchSettings.resultLimit`은(는) `1..20`에 있습니다. 가짜 어댑터는 반환된 적중을 구체화하기 전에 이 제한을 적용합니다.
- `SearchSettings.featureFlags`에는 `[a-z][a-z0-9-]{1,31}`과 일치하는 소문자 케밥 이름이 최대 8개 포함됩니다.
- `SearchSettings.mode`은 고정 카탈로그 일치만 제어합니다(접두사, 정확한 또는 제한된 퍼지 포함).

직렬화 가능한 도메인 클래스는 `java.io.Serializable`을 구현하고 명시적인 `serialVersionUID`을 정의합니다. `SearchQuery` 및 `SearchSettings`은 생성된 `copy(...)` 메서드를 통해 정규화를 우회할 수 없는 구성 패턴을 사용합니다. 직렬화 가능은 저장소 규칙에만 적용됩니다. 이 예제에서는 지속성이나 신뢰할 수 없는 개체 역직렬화를 보여주지 않습니다. 동일한 유형의 필드는 위치 메소드 매개변수가 아닌 도메인 유형 내에서 이름이 지정된 속성으로 유지됩니다.

## 거부된 접근법

1. 오래된 요청을 처리하려면 `flatMapDrop` 또는 `flatMapFirst`을 사용하세요.
   - 현재 검색이 실행되는 동안 실제 계약이 최신 쿼리를 삭제하므로 거부되었습니다. 이는 자동 완성 대체가 아닌 exhaust/drop 의미를 가르치는 것입니다.
2. 수동 `MutableSharedFlow`과 외부 변경 가능 `SearchSettings`을 구현합니다.
   - 문제가 대체하려는 상용구를 재현했기 때문에 거부되었습니다.
3. HTTP/WebSocket 또는 데이터베이스 인프라를 추가합니다.
   - 학습 목표가 통합 배관이 아닌 Flow 구성이기 때문에 거부되었습니다.

## 위험 및 완화

- **대체 의미 체계 드리프트**: README 및 테스트는 `flatMapLatest`을 `flatMapDrop`와 명시적으로 구별합니다.
- **타이밍 불안정 테스트**: 테스트에서는 코루틴 테스트 도우미와 결정적 어댑터 후크를 사용합니다. 취소를 위한 수면 기반 어설션이 없습니다.
- **광범위 핸들러에 의해 무시되는 취소**: 어댑터와 파이프라인은 `runCatching`에서 일시 중지 호출을 래핑하지 않습니다. 테스트를 통해 실제 작업을 취소하고 취소 증거를 확인합니다.
- **설정 경쟁 혼란**: 테스트는 쿼리가 최근에 내보낸 설정과 결합되어 있음을 증명하고, 첫 번째 설정이 내보내기 전에 검색이 시작되지 않음을 증명하고, README이 `withLatestFrom`의 첫 번째 내보내기 동작에 이름을 지정함을 증명합니다.
- **민감한 진단 로그**: ​​도메인 `toString()` 출력은 `Flow.log()`이 사용되기 전에 수정됩니다. 테스트에서는 원시 쿼리, 테넌트 ID, 기능 플래그, 소스 메타데이터 및 결과 제목이 렌더링된 로그 값에 없다고 검증문합니다.
- **버스트 할당 드리프트**: 가벼운 대규모 버스트 스트레스 테스트는 1,000개의 빠른 입력이 절전 기반 타이밍 없이 하나의 어댑터request/result를 생성하는지 확인합니다.
- **다이어그램 드리프트**: 다이어그램은 SVG+PNG로 생성되고 XML, 기하학, 엔드포인트, 유효성 검사기, 밀착 인화 및 전체 크기 육안 검사로 감사됩니다.

## 수락 기준 매핑

| 이슈기준 | 디자인 반응 |
|---|---|
| Runnable/testable 예제에서는 Bluetape4k Flow 확장을 사용합니다 | `SearchPipeline`은 행복한 경로에서 `bufferingDebounce`, `withLatestFrom`, `takeUntil` 및 편집 안전 `Flow.log()`을 사용합니다 |
| Before/After 수동 체인과 확장 체인 | README.md 및 README.ko.md에는 수동 `MutableSharedFlow`/`Job` 취소를 위한 짧은 안티 패턴 기준선이 포함되어 있으며 이를 Bluetape4k-first 체인 |
| 버스트 입력 | 테스트는 버스트 쿼리가 디바운스 일괄 처리의 최신 쿼리로 축소되는지 확인합니다. |
| 대형 burst/backpressure | 테스트는 1,000개의 빠른 입력이 여전히 하나의 어댑터를 생성하는지 확인합니다. request/result |
| 최신 설정 구성 | 테스트는 요청이 쿼리 이전에 내보낸 최신 설정을 사용하는지 확인합니다. |
| 설정 첫 번째 방출 | 테스트는 첫 번째 설정이 방출되기 전에 검색이 시작되지 않았는지 확인하고 README는 시드된 설정을 표시합니다 |
| 세션 중지 | 테스트는 공유 세션 중지를 확인하고 어댑터 경주를 통해 활성 기내 검색을 취소하고 `takeUntil(sharedSessionClosed)` 외부 결과 흐름을 종료합니다 |
| 업스트림 장애 전파 | 테스트는 버퍼링된 값이 처리된 후 원래 업스트림 오류가 전파되는지 확인합니다. |
| 취소 | 테스트는 새로운 쿼리가 도착할 때 `flatMapLatest`가 진행 중인 검색을 취소하는지 확인합니다. |
| 안전한 입력 계약 | 테스트는 query/settings 유효성 검사, 정규 표현식 입력의 리터럴 일치, 결과 제한 제한 및 수정된 디버그 렌더링을 확인합니다.
| README 기능 테이블 | README 파일에는 기능, 아티팩트, 코드 참조 및 이점이 포함됩니다 |
| 유효성 검사 명령 | README 파일에는 모듈 테스트 및 컴파일 명령이 포함됩니다.

## 문서 및 다이어그램

README 언어 세트:

- `kotlin/flow-extensions-search-pipeline/README.md`
- `kotlin/flow-extensions-search-pipeline/README.ko.md`

다이어그램 자산:

- 시나리오: 타이핑 버스트, 라이브 설정, 세션 종료.
- 아키텍처: UI 쿼리 스트림, Flow 파이프라인, 가짜 검색 어댑터, 결과 스트림.
- 도메인 모델: query/settings/request/result/hit.
- 순서: 버스트 -> 디바운스 -> 설정 가입 -> 검색 대체 -> 중지.

생성된 다이어그램 레이블은 영어를 사용합니다. 우리말 README 산문은 직역이 아닌 자연스럽고 전문적인 우리말이어야 한다.

두 README에는 모두 범위 참고 사항이 포함되어 있습니다. HTTP/WebSocket 없음, database/cache 없음, auth/authz 없음, 프로덕션 순위 없음, 분산 취소 프로토콜 없음, 신뢰할 수 없는 개체 역직렬화 없음. 수동 기준선은 복사 대상이 아닌 안티패턴 대비로 레이블이 지정됩니다. 두 README에는 `search-pipeline` 로그 태그에 대한 diagnostics/operational 메모, 취소 증거 및 리소스 소유권 없음 롤백 모델도 포함되어 있습니다.

## 검증 계획

- `./gradlew :kotlin-flow-extensions-search-pipeline:test --rerun-tasks --console=plain`
- `./gradlew :kotlin-flow-extensions-search-pipeline:compileKotlin :kotlin-flow-extensions-search-pipeline:compileTestKotlin --console=plain`
- `./gradlew projects --console=plain`
- README 유효성 검사기: 패리티, 언어, 아키텍처 다이어그램 유효성 검사기, 시퀀스 다이어그램 유효성 검사기.
- 다이어그램 검증: SVG XML 구문 분석, CairoSVG 렌더링, 형상 감사, 엔드포인트 감사, 밀착 인화지, 전체 크기 PNG 육안 검사.
- `actionlint .github/workflows/Examples.yml` 작업 흐름이 변경된 경우.
- `git diff --check`

## DoD

- 모듈은 기존 `settings.gradle.kts` 자동 포함으로 등록됩니다.
- 테스트에서는 이슈 승인 경로, 안전한 input/logging 경계, 첫 번째 방출 동작 설정, 진행 중인 세션 취소, 대규모 스트레스 증거 및 문서의 `flatMapDrop`/`flatMapLatest` 의미론적 결정을 다룹니다.
- README.md 및 README.ko.md는 소스와 동일하며 사용된 기능 테이블을 포함합니다.
- 다이어그램은 소스 기반이고 읽기 가능하며 현재 `$bluetape4k-diagram` 체크리스트를 통과합니다.
- 연기 작업 흐름의 예에는 `:kotlin-flow-extensions-search-pipeline:test`이 포함됩니다.
- 6-R단계와 7-R단계 검토는 P0 = 0 및 P1 = 0으로 수렴됩니다.
