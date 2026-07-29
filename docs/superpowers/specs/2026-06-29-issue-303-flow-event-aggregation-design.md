# Issue 303 Flow 이벤트 집계 디자인

## 문제

Issue #303은(는) 학습자에게 `bluetape4k-coroutines` Flow 확장을 사용하여 시끄러운 주문 이벤트 스트림을 안정적인 읽기 모델 업데이트로 전환하는 방법을 가르치는 워크숍 예제를 요청합니다. 이 예제에서는 변경 가능한 맵, 타이머 플러시 및 수동 previous/current 상태 확인을 읽을 수 있는 Flow 파이프라인으로 대체해야 합니다.

예제는 메모리에 있습니다. HTTP, 데이터베이스, Kafka, 캐시 또는 Testcontainers 인프라를 추가하면 안 됩니다. 학습자를 향한 표면은 `kotlin/`, 이중 언어 README 파일, 생성된 README 다이어그램, 결정론적 코루틴 테스트 및 연기 워크플로 등록 아래의 작은 모듈입니다.

## 소스 증거

- 기존 Flow 워크샵 모듈은 `kotlin/flow-extensions-*` 아래에 있으며 작은 인메모리 도메인, 모듈-로컬 README 쌍, JUnit 5 코루틴 테스트 및 루트 README 등록을 사용합니다.
- GNO 이슈 검색은 이슈 #303를 정확한 GitHub 소스로 반환합니다. GNO 문서 검색에는 이전 이벤트 집계 워크샵 디자인이 없으므로 이 사양은 실시간 이슈, 현재 저장소 예제 및 형제 `bluetape4k-projects` Flow 확장 소스에 의존합니다.
- `chunked(size, partialWindow)`은 `windowed(size, size, partialWindow)`에 위임하고 제한된 개수의 배치를 내보냅니다.
- `windowed(size, step, partialWindow)`은 `size > 0`, `step > 0` 및 `size >= step`의 유효성을 검사합니다. 부분 롤링 창은 꼬리 창을 방출할 수 있습니다.
- `groupBy { key }`은 키당 `GroupedFlow<K, T>`을 내보냅니다. `toGroupItems()`는 각 그룹을 한 번 소비하고 `GroupItem(key, values)`을 반환합니다.
- `scanWith { initial }`은 컬렉션마다 새로운 누산기를 생성하고 누적된 상태 이전에 초기 누산기를 내보냅니다.
- `bufferUntilChanged { key }`은 인접한 등호 실행만 ​​그룹화합니다. 주문 ID를 기준으로 글로벌 그룹화하는 것이 아니라 연속적으로 변경되지 않은 수명 주기 상태를 억제하는 것이 옳습니다.
- `pairwise` 및 `zipWithNext`은 인접한 previous/current 쌍을 방출하며 전환 보고에 적합합니다.
- `Flow<T>.log(tag)`은 투명한 디버그 후크입니다. 이를 통과하는 값은 학습자가 실제 서비스에 로깅을 복사할 수 있으므로 안전한 문자열 렌더링을 가져야 합니다.

## 설계

`kotlin/flow-extensions-event-aggregation`을 인메모리 Kotlin 모듈로 생성합니다.

주요 유형은 `OrderEventAggregationPipeline`입니다. `Flow<OrderEvent>` 입력을 허용하고 별도로 테스트 가능한 작은 파이프라인 기능을 노출합니다.

1. `chunkedActivity(events, chunkSize)`은 `chunked`을 사용하여 제한된 배치 요약을 생성합니다.
2. `rollingActivity(events, size, step)`은 `windowed`을 사용하여 중복되는 활동 요약을 만듭니다.
3. `groupedByOrder(events)`은 `Flow<GroupItem<String, OrderEvent>>`을 반환하고 `events.groupBy { it.orderId }.flatMapMerge { it.toGroupItems() }`를 사용하여 즉시 그룹을 소비합니다. 이것은 주기적 집계 핫 경로가 아닌 유한하고 완성된 스트림 파티셔닝 데모일 뿐입니다.
4. `readModels(events)`은 `scanWith`을 사용하여 주문 ID로 입력된 `OrderReadModel` 지도를 축적합니다. `OrderReadModel.apply`은 명확성을 가르치기 위해 의도적으로 변경 불가능한 스냅샷을 반환합니다. README는 이벤트별 스냅샷 할당 비용을 나타냅니다.
5. `statusRuns(events, orderId)`은 `Flow<OrderStatusRun>`을 반환합니다. 하나의 주문을 필터링하고, `scanWith`를 적용하고, 초기 `NEW` 상태를 삭제하고, `bufferUntilChanged { it.status }`를 호출하고, 방출된 각 `List<OrderState>` 실행을 실행 상태, first/last 버전, first/last 이벤트 시간 및 최종 상태와 함께 하나의 DTO에 매핑합니다.
6. `transitions(events, orderId)`은 `zipWithNext { previous, current -> OrderTransition(...) }` 앞에 `statusRuns(...).map { it.finalState }`을 매핑한 다음 변경되지 않은 상태를 필터링합니다.
7. `audit(events)`은 먼저 각 이벤트를 삭제된 `OrderAuditEntry` 값에 매핑한 다음 `Flow.log("order-event-aggregation")`를 호출합니다. 원시 `OrderEvent` 값은 `Flow.log()`를 통과하지 않습니다.

README는 이러한 기능이 하나의 이벤트 집계 파이프라인으로 구성된다는 점을 가르쳐야 하지만 소스는 학습자가 집중 테스트를 실행하고 한 번에 하나의 Flow 확장을 검사할 수 있도록 이들을 별도로 유지합니다.

## 도메인 모델

- `OrderEvent`: `orderId`, `occurredAt` 및 안전한 이벤트 유형 이름을 사용하는 봉인된 인터페이스입니다.
  - `OrderCreated(orderId, customerId, occurredAt)`
  - `LineAdded(orderId, sku, quantity, occurredAt)`
  - `PaymentAuthorized(orderId, amountCents, occurredAt)`
  - `ShipmentStarted(orderId, carrier, trackingNumber, occurredAt)`
  - `OrderCancelled(orderId, reason, occurredAt)`
- `OrderStatus`: `NEW`, `CREATED`, `PAID`, `SHIPPED`, `CANCELLED`.
- `OrderState`: 하나의 주문 ID에 대한 직렬화 가능 읽기 모델입니다. 상태, 라인 수, 항목 수량, 승인된 금액, 마지막 이벤트 시간 및 버전을 추적합니다.
- `OrderReadModel`: `scanWith`에서 사용하는 직렬화 가능한 집계 맵 래퍼입니다.
- `OrderActivitySummary`: 제한된 배치 및 롤링 창에 대해 직렬화 가능한 DTO입니다. 여기에는 이벤트 수, 주문 ID, 최신 상태, 라인 수, 항목 수량, 금액 및 창 start/end 타임스탬프가 포함됩니다.
- `OrderStatusRun`: 인접한 변경되지 않은 상태 실행에 대해 직렬화 가능한 DTO입니다. 전환이 축소된 상태를 비교할 수 있도록 실행의 최종 상태를 저장합니다.
- `OrderTransition`: previous/current 상태 변경을 위해 직렬화 가능한 DTO.
- `OrderAuditEntry`: 읽기 가능한 test/debug 출력을 위해 직렬화 가능한 DTO.

호출자에게 표시되는 불변성:

- `orderId`, `customerId`, `sku`, `carrier`, `trackingNumber` 및 취소 `reason`는 잘리고 공백이 아니며 경계가 있고 거부 제어 문자입니다.
- `orderId`, `sku`, `carrier` 등 로그에 표시되는 식별자는 요약이나 감사 출력에 표시되기 전에 인쇄 가능한 ASCII 토큰 패턴을 사용합니다.
- `quantity`은(는) 긍정적입니다.
- `amountCents`은(는) 긍정적입니다.
- `occurredAt`은 호출자가 소유한 타임스탬프입니다. 이 예에서는 벽시계 시간을 내부적으로 할당하지 않습니다.
- 입력 이벤트 유형은 전용 생성자와 동반 팩토리 또는 검증된 값-객체 패턴을 사용하므로 기본 데이터 클래스 `copy(...)`를 통해 생성, 트리밍 및 안전한 문자열 렌더링을 우회할 수 없습니다. 디버그 출력에 나타날 수 있는 모든 이벤트 또는 감사 유형에는 명시적으로 수정되거나 삭제된 `toString()`이 있습니다.
- `OrderAuditEntry` 허용되는 필드는 `sequence`, `eventType`, `orderId`, `status`, 개수, 금액, 버전 및 타임스탬프입니다. 원시 `customerId`, `trackingNumber` 또는 취소 `reason`를 저장하지 않습니다.
- 직렬화 가능한 도메인 클래스는 `java.io.Serializable`을 구현하고 `serialVersionUID`을 정의합니다. 직렬화 가능은 여기서 repo 규칙입니다. 이 예제에서는 지속성이나 신뢰할 수 없는 개체 역직렬화를 보여주지 않습니다.

## 상태 전환 규칙

| 현황 | 이벤트 | 다음 상태 | 메모 |
|---|---|---|---|
| `NEW` | `OrderCreated` | `CREATED` | 첫 번째 일반 수명주기 이벤트 |
| `NEW` | `LineAdded` | `CREATED` | 부분적으로 재생된 설비에 대한 관용적인 읽기 모델 구축을 가르칩니다.
| `NEW` 또는 `CREATED` | `PaymentAuthorized` | `PAID` | 중복 결제 이벤트는 `PAID` 유지되고 amount/version 업데이트 |
| `NEW`, `CREATED` 또는 `PAID` | `ShipmentStarted` | `SHIPPED` | 결제 전 잘못된 배송은 읽기 모델 프로젝션 이벤트로 승인되고 명시적으로 테스트됩니다 |
| 취소되지 않은 상태 | `OrderCancelled` | `CANCELLED` | `SHIPPED` | 이후에도 취소는 종료됩니다.
| `CANCELLED` | 나중에 취소되지 않는 이벤트 | `CANCELLED` | 상태는 최종 상태로 유지됩니다. 감사 가시성을 위한 버전 및 마지막 이벤트 시간 향상 |

본 워크숍은 순서가 잘못된 이벤트를 거부하지 않습니다. 안정적인 최신 읽기 모델로 수렴하면서 감사 가시성을 유지하는 프로젝션을 모델링합니다. README는 이벤트가 투영에 들어가기 전에 엄격한 명령측 순서 확인이 적용되어야 함을 말해야 합니다.

## 리소스 소유권 및 스트림 수명

호출자는 수집, 취소, 재생 및 소스 수명을 소유합니다. 모든 테스트와 예제는 유한 또는 재생 제한 스트림을 사용합니다.

`groupedByOrder`은 완성된 유한 스트림용입니다. `groupBy`은 활성 그룹을 유지하고 `toGroupItems()`는 각 그룹의 값을 `List`으로 구체화하기 때문입니다. 학습자는 해당 함수를 경계, TTL/checkpointing 또는 지속성 이벤트 store/outbox 없이 무제한 실시간 수집 기본 요소로 복사해서는 안 됩니다. 주기적 집계는 제한되지 않은 `toGroupItems()`이 아닌 제한 있는 `chunked`, `windowed` 또는 재생 체크포인트를 사용해야 합니다.

복구 의미 체계는 의도적으로 프로세스 로컬입니다. Flow 파이프라인은 콜드이고, `scanWith`은 컬렉션당 새로운 상태를 생성하며, 실패 전 부분 방출은 체크포인트되지 않으며, 복구는 재생 가능한 소스에서 다시 수집하는 것을 의미합니다. 취소 또는 실패하면 이 모듈에 단일 상태가 유지되지 않습니다.

## 거부된 접근법

1. 변경 가능한 싱글턴 맵과 예약된 플러시를 사용하세요.
   - 스트림 의미를 숨기고, 결정론적 테스트를 더 어렵게 만들고, 문제가 대체하려는 기존 접근 방식을 재현하기 때문에 거부되었습니다.
2. 이벤트 저장소, 아웃박스, Kafka, Redis 스트림 또는 Spring 통합 레이어를 추가합니다.
   - 문제가 인메모리 Flow 확장 예제를 요구하기 때문에 거부되었습니다. README에서는 내구성 있는 구성 요소가 여전히 필요한 시기를 설명합니다.
3. `bufferUntilChanged { orderId }`을 순서 그룹화 프리미티브로 사용하세요.
   - 실제 계약 그룹은 인접해 있는 그룹만 실행되므로 거부됩니다. 올바른 전역 그룹화 예는 `groupBy { orderId }`입니다.
4. 모든 동작을 하나의 큰 `aggregate(events)` 함수로 병합합니다.
   - 학습자는 각 확장이 스트림을 어떻게 변경하는지 확인해야 하고 테스트에는 집중된 실패 증거가 필요하기 때문에 거부되었습니다.

## 위험 및 완화

- **그룹화 혼란**: README 및 테스트에서는 순서 분할을 위한 `groupBy`을 인접한 변경되지 않은 상태 억제를 위한 `bufferUntilChanged`와 명시적으로 구별합니다.
- **초기 `scanWith` 방출 혼란**: `readModels(events)`은 필요한 경우 초기 빈 읽기 모델을 필터링하거나 문서화하고 테스트는 첫 번째 의미 있는 업데이트를 확인합니다.
- **터미널 이벤트 후 상태 회귀**: 도메인 전환 규칙은 `CANCELLED` 터미널을 유지합니다. 이 예에서는 배송된 주문이 paid/created로 반환되지 않습니다.
- **고카디널리티 그룹화 비용**: `groupedByOrder`은 유한 스트림으로만 문서화되어 있습니다. 테스트에는 무한한 핫 경로를 피하면서 삭제된 그룹이 없음을 증명하기 위한 결정론적 유한 고카디널리티 샘플이 포함됩니다.
- **창 및 스냅샷 할당**: README는 `windowed`/`chunked`이 내보낸 목록과 겹치는 창에 유지된 요소를 중복 할당하는 반면 불변 읽기 모델 스냅샷은 명확성을 위해 이벤트별로 할당한다고 명시합니다.
- **타이밍 불안정 테스트**: 테스트는 스케줄러 절전 모드 없이 유한한 `flowOf(...)` 데이터와 `runSuspendTest`을 사용합니다.
- **실패 및 취소 모호성**: 테스트는 업스트림 실패 전파, `groupedByOrder` 예외 형태 및 `CancellationException`을 삼키지 않고 콜렉터 취소를 다룹니다.
- **디버그 데이터 노출**: 감사 DTO는 `Flow.log()` 전에 삭제됩니다. 디버그 지향 `toString()` 출력에서는 고객 ID, 추적 번호 및 취소 이유 값을 피합니다. 테스트에서는 감사 필드 및 렌더링된 디버그 출력에 민감한 문자열이 없다고 검증문합니다.
- **내구성 과잉 검증문**: README에서는 인메모리 Flow 집계가 프로세스 로컬, 재생 가능 또는 테스트 파이프라인용이라고 명시합니다. 내구성 이벤트 store/outbox는 프로세스 간 복구에 계속 필요합니다.
- **다이어그램 드리프트**: 다이어그램은 SVG+PNG로 생성되고 XML 구문 분석, architecture/sequence 유효성 검사기, 형상 감사, 엔드포인트 감사, 밀착 인화 및 전체 크기 육안 검사로 감사됩니다.

## 내구성 있는 인프라 경계

| 우려사항 | 이 Flow 예 | 지속적인 store/outbox 책임 |
|---|---|---|
| 원자적 쓰기 및 게시 | 제공되지 않음 | 도메인 변경을 지속하고 enqueue/publish 원자적으로 |
| 재생 오프셋 | 호출자가 소유한 소스에서만 유한 재생 | offsets/checkpoints을 저장하고 실패 후 재개 |
| 중복 억제 | 투영은 관용적이지만 권위적이지는 않습니다 | 멱등성 키 및 중복 감지 |
| 주문 보증 | Fixture 순서는 하나의 내부에 보존됩니다. Flow | Partition/order은 broker/store |
| 재시도 및 포이즌 처리 | 업스트림 오류가 테스트로 전파됨 | 재시도 정책, 포이즌 큐, 배달 못한 편지 처리 |
| 화해 | 제공되지 않음 | 운영자가 볼 수 있는 조정 및 수리 작업 |

## 런북 및 롤백

롤아웃은 추가됩니다. 하나의 새로운 인메모리 모듈, README 링크, 다이어그램 자산, 연기 스크립트 항목 및 예제 워크플로 항목이 있습니다. 데이터 마이그레이션이나 지속적인 정리가 없습니다.

롤백은 추가된 module/docs/diagram/workflow/script 항목을 되돌린 다음 `./gradlew projects --console=plain`, `./scripts/smoke-validate.sh async`, README image/link 확인 및 `git diff --check`을 확인하는 것입니다.

## 수락 기준 매핑

| 이슈기준 | 디자인 반응 |
|---|---|
| Flow 확장이 4개 이상 포함된 Runnable/testable 예 | 파이프라인은 `chunked`, `windowed`, `groupBy`, `toGroupItems`, `scanWith`, `bufferUntilChanged`, `zipWithNext` 및 `Flow.log()` |
| Before/after README 섹션 | README.md 및 README.ko.md는 가변 맵 + 스케줄러를 Flow 확장 파이프라인과 비교 |
| 테스트 커버 일괄 처리 | `chunkedActivity` 및 `rollingActivity` 테스트는 제한된 요약과 롤링 요약을 확인합니다.
| 표지 그룹화 테스트 | `groupedByOrder` 테스트는 `groupBy`을 사용하여 유한 인터리브된 순서별 파티셔닝과 유한 고카디널리티 그룹화 샘플을 검증합니다 |
| 상태 축적을 다루는 테스트 | `readModels` 테스트는 `scanWith` 읽기 모델 업데이트를 확인합니다 |
| 변경되지 않은 상태 억제를 다루는 테스트 | `statusRuns` 테스트는 `PAID` 이전에 연속적인 `CREATED` 상태 붕괴를 확인합니다 |
| 커버 전환 방출 테스트 | `transitions` 테스트는 생성에서 지불로 배송으로 전환을 확인하거나 전환을 취소합니다 |
| 테스트 커버 안정성 | 테스트는 터미널 상태 수렴, duplicate/out-of-order 이벤트, 업스트림 오류 전파, 수집기 취소 및 결정론적 출력 순서를 확인합니다.
| 잘못된 매개변수를 다루는 테스트 | 테스트에서는 유효하지 않은 `chunkSize`, `size`, `step`, 너무 긴 식별자 및 제어 문자 거부를 확인합니다. |
| README는 범위와 내구성 store/outbox 제한을 설명합니다 | 범위 섹션에는 프로세스-로컬 제한과 지속성 이벤트 store/outbox가 필요한 경우가 명시되어 있습니다.
| README에는 사용된 기능 테이블이 포함되어 있습니다 | 두 README 파일 모두 `Used Bluetape4k features` |

## 문서 및 다이어그램

README 언어 세트:

- `kotlin/flow-extensions-event-aggregation/README.md`
- `kotlin/flow-extensions-event-aggregation/README.ko.md`

다이어그램 자산:

- 시나리오: 시끄러운 주문 이벤트가 요약이 되고 모델 읽기, 전환 및 감사 항목이 됩니다.
- 아키텍처: 이벤트 소스에서 Flow 확장 파이프라인, read-model/debug 출력까지 위에서 아래로 계층화된 흐름.
- 도메인 모델: 이벤트 계층 구조, 읽기 모델, 요약, 전환, 감사 항목.
- 순서: 이벤트 스트림 -> chunk/window/group/scan -> 변경되지 않은 상태 억제 -> 전환 방출.

생성된 다이어그램 레이블은 영어를 사용합니다. 아키텍처 흐름은 위에서 아래로 이루어져야 하며, 명확한 레이어 구분을 포함하고, 필수 글꼴을 사용하고, code/domain 개념에 대해 고안된 인프라 아이콘을 피해야 합니다.

두 README 모두 범위 참고 사항을 포함합니다. 즉, 내구성 있는 이벤트 저장소 없음, 아웃박스 없음, 메시지 브로커 없음, 정확히 한 번만 프로세스 간 의미 체계 없음, 재생 검사점 없음, 프로덕션 PII 로깅 없음. 수동 기준선은 복사 대상이 아닌 안티패턴 대비로 레이블이 지정됩니다.

README 범위 참고 사항에는 여기서 `groupBy` + `toGroupItems()`가 유한 또는 재생 제한 완료된 스트림에 대해서만 안전하다는 점도 명시해야 합니다. 장기 실행 프로덕션 스트림에는 내구성 있는 storage/outbox/checkpointing + 제한된 windows/backpressure 전략이 필요합니다. audit/log 경로는 test/debug-only이고 고정 태그 `order-event-aggregation`를 사용하며 민감하지 않은 필드만 포함하고 프로덕션 metrics/tracing을 대체하지 않습니다.

## 검증 계획

- `./gradlew :kotlin-flow-extensions-event-aggregation:test --rerun-tasks --console=plain`
- `./gradlew :kotlin-flow-extensions-event-aggregation:compileKotlin :kotlin-flow-extensions-event-aggregation:compileTestKotlin --console=plain`
- `./gradlew projects --console=plain`
- `./scripts/smoke-validate.sh async`
- CI 등록 확인:
  - `.github/workflows/Examples.yml` 푸시 및 PR 경로 필터에는 `kotlin/flow-extensions-event-aggregation/**`이 포함됩니다.
  - `smoke-examples`은 `:kotlin-flow-extensions-event-aggregation:test`를 실행합니다.
  - 연기 아티팩트 업로드에는 `kotlin/flow-extensions-event-aggregation/build/test-results/test/*.xml` 및 `kotlin/flow-extensions-event-aggregation/build/reports/tests/test/`이 포함됩니다.
  - `scripts/smoke-validate.sh`은 `all-smoke`과 `async` 모두의 작업을 포함합니다.
- README 유효성 검사기와 루트 README 링크 검사.
- 다이어그램 검증: SVG XML 구문 분석, CairoSVG 렌더링, 아키텍처 및 시퀀스 유효성 검사기, 형상 감사, 엔드포인트 감사, 밀착 인화 및 전체 크기 PNG 육안 검사.
- `actionlint .github/workflows/Examples.yml`
- `git diff --check`

## DoD

- 모듈은 기존 `settings.gradle.kts` 자동 포함으로 등록됩니다.
- 테스트에는 일괄 처리, 그룹화, 상태 누적, 변경되지 않은 상태 억제, 전환 방출, 유효하지 않은 도메인 값, 유효하지 않은 파이프라인 매개변수, 최종 상태 수렴, 업스트림 실패 전파, 수집기 취소, 결정론적 순서 지정 및 안전한 audit/debug 렌더링이 포함됩니다.
- README.md 및 README.ko.md는 소스와 동일하며 before/after, 범위 및 기능 테이블 섹션을 포함합니다.
- 다이어그램은 소스 기반이고 읽기 가능하며 위에서 아래로 아키텍처 흐름이 표시되며 현재 `$bluetape4k-diagram` 체크리스트를 통과합니다.
- 연기 작업 흐름 및 스크립트의 예에는 `:kotlin-flow-extensions-event-aggregation:test`이 포함됩니다.
- 6-R단계와 7-R단계 검토는 P0 = 0 및 P1 = 0으로 수렴됩니다.
