# Issue 304 디자인 - Flow 측정항목 샘플링 워크숍

## 문맥

Issue #304에서는 다음을 수행하는 방법을 알려주는 중간 Flow 확장 예제를 요청합니다.
수동 스케줄러를 구축하지 않고도 시끄러운 측정항목이나 센서 스트림을 샘플링할 수 있습니다. 그만큼
예는 `kotlin/`에 속하며 기존 Flow을 보완해야 합니다.
확장 학습 경로:

- `flow-extensions-search-pipeline`: debounce/session 동작을 중지합니다.
- `flow-extensions-event-aggregation`: 인접 상태 전이 분석.
- 새로운 `flow-extensions-metrics-sampling`: 스로틀 leading/trailing, 델타
  계산, 중요한 변경 감지 및 취소 안전 종료.

모듈은 워크샵 소비자 프로젝트입니다. 저장소의
기존 `bluetape4k-dependencies` BOM 배선이 필요하며 새로운 배선을 도입해서는 안 됩니다.
의존성.

## 리더 문제

서비스는 CPU 활용도, 대기열 깊이,
요청 대기 시간 또는 장치 센서 값. 원시 스트림이 너무 조밀하여
대시보드 또는 운영 로그. 학습자는 의미론적 차이를 확인해야 합니다.
사이:

- 경고 미리보기에 대한 빠른 첫 번째 피드백,
- 대시보드의 안정적인 추적 샘플,
- 추세 분석을 위한 인접 샘플 델타
- 변환 취소 없이 수집을 종료하는 수명 주기 중지 신호
  도메인 오류에 빠졌습니다.

## 결정

작은 도메인 모델과 하나의 모델로 `kotlin/flow-extensions-metrics-sampling`을 추가합니다.
파이프라인 클래스:

- `MetricSample(name, value, timestamp, unit)`
- `MetricDelta(name, unit, previous, current, delta, percentChange)`
- `MetricTrend(delta, direction, significant)`
- `MetricsSamplingPipeline`

프로덕션 경로에서는 Bluetape4k Flow 확장을 직접 사용합니다.

- `throttleLeading` 반응형 첫 번째 피드백을 제공합니다.
- 대시보드 친화적인 후행 샘플의 경우 `throttleTrailing`입니다.
- 인접한 델타의 경우 `pairwise`입니다.
- 수명 주기에 따른 종료의 경우 `takeUntil`.
- `Flow<T>.log()` 의미 단계 이후 수정된 도메인 값을 사용합니다.

README는 수동 timestamp/scheduler 코드를 "이전" 대비로만 사용합니다.
실행 가능한 코드는 스케줄러 소유권, 변경 가능한 마지막 방출 타임스탬프를 방지합니다.
상태 또는 `CancellationException` 주위의 도메인 래퍼입니다.

## 수락 기준

1. 테스트는 결정론적 가상 시간으로 주요 스로틀 동작을 다룹니다.
   샘플.
2. 테스트는 결정론적 가상 시간을 사용하여 후행 스로틀 동작을 다룹니다.
   샘플.
3. 테스트에서는 인접 샘플 델타 계산을 다룹니다.
4. 테스트에는 중요한 변경 사항 감지가 포함됩니다.
5. `takeUntil` 중지 신호 처리를 테스트합니다.
6. 테스트를 통해 수집기 취소가 `CancellationException`으로 전파되고
   업스트림 정리가 실행됩니다.
7. `README.md` 및 `README.ko.md`은 선행 스로틀과 후행 스로틀을 설명합니다.
   비교표.
8. 두 README에는 모두 일반적인 scheduler/timestamp "Before" 스니펫과
   Flow 확장 "이후" 체인.
9. 두 README에는 `Used Bluetape4k features` 테이블이 포함되어 있습니다.
10. 다이어그램은 위에서 아래로의 흐름으로 아키텍처와 라이프사이클을 설명합니다.
    레이어 그룹화 지우기, SVG+PNG 자산, CairoSVG 렌더링, XML 검증,
    커넥터 감사, 밀착 시트 및 전체 크기 육안 검사.
11. 루트 README 로캘 쌍, 예제 워크플로 및 `scripts/smoke-validate.sh`
    새로운 예제를 등록하세요.
12. 로컬 검증에는 대상 테스트, Kotlin compile/test 컴파일,
    비동기 연기 그룹, 오래된 검사, README 유효성 검사기, 작업 흐름 유효성 검사 및
    다이어그램 체크리스트 증거.

## 논골

- Micrometer 레지스트리, Prometheus, Grafana 또는 액추에이터 스택이 없습니다.
- 데이터베이스, Redis, Kafka 또는 외부 인프라 종속성이 없습니다.
- 생산 준비가 완료된 샘플링 엔진이나 배압 정책 추상화가 없습니다.
- 벤치마크 검증문이 없습니다.

## 제약

- Public README 및 KDoc은 영어입니다. 한국어 README는 소스와 동일해야 합니다.
  자연스럽고.
- 새로운 데이터 클래스는 `java.io.Serializable`을 구현하고 정의합니다.
  `serialVersionUID`.
- 검증된 도메인 구성은 다음에 대한 공개 `copy` 우회를 노출해서는 안 됩니다.
  제한된 값.
- 취소는 광범위한 예외 처리로 포착되어서는 안 됩니다.
- 다이어그램 텍스트는 영어이며 `Architects Daughter` / `Comic Mono`을 사용합니다.
- 새 카드는 code/Flow이므로 다이어그램에서는 서비스 아이콘을 사용하지 않습니다.
  책임이지 실제 인프라 서비스는 아닙니다.

## 위험 및 완화

| 위험 | 완화 |
|---|---|
| 시간 기반 테스트가 불안정해짐 | `runSuspendTest` / 코루틴 테스트 가상 시간 및 기존 업스트림 스로틀 패턴을 사용합니다. 진짜 잠을 피하세요. |
| 후행 스로틀 의미는 직관과 다릅니다 | 정확한 방출된 측정항목 names/values을 검증문하고 README에서 지연된 대시보드 균형을 설명합니다. |
| 취소를 실수로 삼켰습니다 | `catch`/`runCatching`을 추가하지 마세요. 수집을 취소하고 업스트림 정리를 확인하는 테스트를 추가합니다. |
| 측정항목 값이 로그의 민감한 라벨을 유출합니다. | 샘플 이름을 짧고 제한적이며 제어 문자 없이 유지하고 안전한 도메인 필드만 렌더링하세요. |
| 새 모듈이 CI/smoke 등록을 놓쳤습니다. | 루트 README, 예제 워크플로 path/test/artifacts, async smoke, all-smoke 및 stale-count를 등록합니다. |
| 다이어그램 실패 시각적 QA 늦게 | 한 번에 하나의 자산을 생성하고, CairoSVG을 사용하여 PNG를 렌더링하고, geometry/endpoint/style 감사를 실행한 다음 전체 크기 PNG 및 밀착 인화를 검사합니다. |

## DoD

- 사양과 계획은 구현 전에 커밋됩니다.
- 테스트는 RED 먼저 작성된 다음 GREEN로 작성됩니다.
- 구현에서는 실행 파일 경로에 필요한 Bluetape4k Flow 확장을 사용합니다.
- 이중 언어 README 및 다이어그램은 학습자에게 친숙하며 소스가 지원됩니다.
- Workflow/smoke/root README 등록이 완료되었습니다.
- PR 생성 전에 추적된 코드 검토 및 학습 아티팩트가 존재합니다.
- PR 메타데이터 미러 이슈 #304 담당자, 마일스톤 및 레이블.
