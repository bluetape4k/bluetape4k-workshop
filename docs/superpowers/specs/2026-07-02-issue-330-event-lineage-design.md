# Issue #330 이벤트 계보 디자인

## 문맥

Issue #330에서는 비즈니스를 모델링하는 고급 그래프 워크숍 사례를 요청합니다.
이벤트 계보 및 감사 재구성. 기존 그래프 예시는 소셜을 포함합니다.
관계, 지식 그래프, 권장 경로, 남용 감지 등
`graph-io-pipeline`import/export. 새 예는 중복되어서는 안 됩니다 #287
`graph-io-pipeline`; 상태가 존재하는 이유와 어떤 업스트림 이벤트가 있는지 가르쳐야 합니다.
배우 또는 결정으로 인해 발생했습니다.

현재 소스 증거:

- `graph/io-pipeline`은 CSV, Jackson3 NDJSON, GraphML import/export 및
  보고서 확인.
- `graph/social-network`, `graph/recommendation`, `graph/knowledge-graph` 및
  `graph/abuser-detection` 직접 `GraphOperations` 도메인 서비스 사용
  TinkerGraph은 기본 테스트용입니다.
- `exposed/javers-approval-workflow` 및 `exposed/javers-persistence-audit`
  이미 JaVers 승인 및 지속성 감사 내역을 가르치고 있습니다. 이 모듈은
  README 산문에서 감사 테이블과 JaVers 사용 사례를 참조하지만 그렇지 않습니다.
  JaVers 또는 데이터베이스 지속성을 포함합니다.
- `bluetape4k-graph` PR #275은(는) 이미 다음에 대한 데이터 카탈로그 계보 예시를 추가했습니다.
  dataset/table/column/job/dashboard 영향. 이 워크숍 모듈은 다음을 사용해야 합니다.
  비즈니스 이벤트, 집계, 행위자, 결정 및 감사 추적 질문
  데이터 자산 계보 대신.

## 목표

`graph/event-lineage`을 결정론적 TinkerGraph 워크숍 모듈로 생성합니다.
학습자가 다음과 같이 대답하도록 하세요.

1. 이 집계 상태를 발생시킨 업스트림 이벤트는 무엇입니까?
2. 어떤 행위자나 결정이 상태 전환을 승인했습니까?
3. 어떤 이후의 사건이 이전 사건을 대체했습니까?
4. 현재 집계 상태를 설명하는 감사 추적은 무엇입니까?
5. 필수 계보 링크가 누락되면 어떻게 됩니까?

## 범위

범위 내:

- 새로운 Gradle 모듈 `:graph-event-lineage`.
- `GraphOperations` 및 TinkerGraph에 구축된 차단 서비스입니다.
- `Event`, `Aggregate`, `Actor` 및 `Decision` 정점에 대한 도메인 스키마입니다.
- `EMITS`, `CAUSED_BY`, `APPROVED_BY` 및 `SUPERSEDES`에 대한 가장자리 레이블입니다.
- Kotlin 테스트 코드의 결정적 시드 픽스처.
- 그래프 구성, 계보 경로 쿼리, 감사 추적 테스트
  재구성, 이벤트 체인 대체, 누락된 링크 동작.
- 그래프 계보와 일반 감사를 설명하는 `README.md` 및 `README.ko.md`
  테이블 및 JaVers 스타일 개체 기록.
- SVG 및 PNG 자산이 포함된 README 아키텍처 및 시퀀스 다이어그램.
- 루트 README/README.ko, repo-local `AGENTS.md`, 연기 스크립트 및 예
  워크플로우 등록.

범위 외:

- 그래프 없음 CSV/NDJSON/GraphML import/export.
- JaVers 저장소 통합이나 데이터베이스 기반 감사 저장소가 없습니다.
- 첫 번째 워크숍에서는 Neo4j/Memgraph 통합 테스트가 없습니다.
- 프로덕션 이벤트 저장소, 아웃박스 릴레이 또는 승인 모델이 없습니다.

## 설계

### 모듈 형태

`graph/event-lineage`은 기존 그래프 도메인 예제 패턴을 따릅니다.

- `schema/EventLineageSchema.kt`은 레이블과 속성 이름을 정의합니다.
- `model/AuditTrail.kt`은 직렬화 가능한 독자 측 쿼리 결과를 정의합니다.
- `service/EventLineageService.kt`은(는) 그래프 수명 주기, 멱등성 정점을 소유합니다.
  생성, 가장자리 생성 및 제한된 순회 방법.
- `src/test/.../seed/EventLineageSeed.kt`은 작은 결정론적 질서를 생성합니다.
  승인 시나리오.
- `EventLineageTinkerGraphTest`은 인메모리에 대해 기본 테스트를 실행합니다.
  `TinkerGraphOperations`.

### 그래프 모델

정점:

- `Event`: `eventId`, `type`, `occurredAt`, `summary`
- `Aggregate`: `aggregateId`, `aggregateType`, `state`, `version`
- `Actor`: `actorId`, `displayName`, `role`
- `Decision`: `decisionId`, `decisionType`, `status`, `reason`

가장자리:

- `EMITS`: 집계 -> 이벤트
- `CAUSED_BY`: 이벤트 -> 업스트림 이벤트
- `APPROVED_BY`: 사건 -> 결정
- `DECIDED_BY`: 결정 -> 배우
- `SUPERSEDES`: 이벤트 -> 이전 이벤트

`DECIDED_BY`은(는) 이슈 #330에 행위자 노드가 필요하고
`APPROVED_BY`은 이벤트를 액터에 직접 연결하지 않고 결정에 연결합니다. 이것
승인 결정 증거를 명시적으로 유지합니다.

### 계약 조회

이 서비스는 소스 지원 메서드를 공개합니다.

- `eventsForAggregate(aggregateId)`은 방출된 이벤트를 결정적으로 반환합니다.
  timestamp/id 주문하세요.
- `causalPath(eventId, rootEventId, maxDepth)`은 다음에서 제한된 경로를 반환합니다.
  현재 사건을 근본 원인으로 되돌립니다.
- `auditTrailForAggregate(aggregateId)` 발생된 이벤트, 원인을 재구성합니다.
  현재 집계에 대한 루트, 승인 결정 및 행위자를 결정합니다.
- `supersededChain(eventId)`는 최신 이벤트부터 `SUPERSEDES` 에지를 따릅니다.
  이전 이벤트.
- `missingCausalLinks(aggregateId)`은 둘 다 포함되지 않은 방출된 이벤트를 반환합니다.
  근본 원인이나 업스트림 원인 증거.

알 수 없는 정점이 누락되면 예외 대신 빈 결과가 반환됩니다. 유효하지 않은
bluetape4k 검증 도우미를 사용하면 빈 ID가 빠르게 실패합니다.

### 다이어그램

두 개의 README 다이어그램이 필요합니다.

- 아키텍처: 도메인 이벤트, 집계 상태,
  actor/decision 증거, 그래프 서비스 및 TinkerGraph 백엔드. 반드시
  커넥터 스타일인 경우 레이어 경계와 가장자리 의미에 대한 범례를 포함합니다.
  다르다.
- 순서: 실제 참여자에 대한 재구성 요청 감사:
  발신자 -> 서비스 -> 그래프 -> aggregate/events -> decisions/actors -> 보고.
  번호가 매겨진 라벨이 있는 현재 모범 사례 시퀀스 스타일을 따라야 합니다.
  투명한 `alt` 프레임, 음소거된 팔레트 및 marker/color 패리티.

## 위험 및 완화

| 위험 | 완화 |
|---|---|
| 데이터 계보 #275 또는 graph-io #287 복제 | business-event/audit-trail 도메인에 초점을 맞추고 graph-io 설비를 추가하지 마세요. |
| 예제를 프로덕션 감사 저장소로 전환 | README는 그래프 계보가 일반 감사 테이블과 JaVers 스냅샷을 대체하는 것이 아니라 보완하는 것을 나타냅니다. |
| 쿼리 순서가 비결정적이 됨 | 독자에게 표시되는 결과를 timestamp/id으로 정렬하고 테스트에서 정확한 출력을 확인합니다. |
| 잘못된 데이터에 대한 그래프 순회 루프 | 방문 경로 확인으로 제한된 BFS; 테스트에는 대체 체인 및 누락된 링크 동작이 포함됩니다. |
| 다이어그램 QA 회귀 | 현재 모범 사례 참조를 사용하고, CairoSVG로 PNG을 렌더링하고, repo-local 다이어그램 QA을 실행하고 전체 크기 육안 검사를 수행합니다. |

## 수락 기준 매핑

| 이슈기준 | 디자인 반응 |
|---|---|
| 루트 `bluetape4k-dependencies` BOM만 사용 | 모듈은 `libs.versions.toml`에서 버전 없는 별칭을 선언합니다. 모듈별 BOM이 없습니다. |
| 테스트를 통해 그래프 구성 확인 | 시드 테스트는 vertex/edge 개수와 속성을 확인합니다. |
| 테스트를 통해 계보 경로 쿼리 확인 | `causalPath` 및 `auditTrailForAggregate` 테스트는 순서가 지정된 경로 내용을 확인합니다. |
| 누락된 링크 동작을 확인하는 테스트 | `missingCausalLinks` 및 알 수 없는 ID 테스트는 결정적인 empty/missing 결과를 ​​나타냅니다. |
| README는 그래프 계보와 감사 테이블을 설명합니다 | README/README.ko에는 비교 섹션과 JaVers/data-table 경계가 포함됩니다. |
| 중복되지 않음 #287 graph-io import/export | graph-io 종속성 또는 CSV/NDJSON/GraphML 워크플로가 없습니다. |

## DoD

- `./gradlew :graph-event-lineage:test --no-build-cache --rerun-tasks --console=plain` 통과.
- `./gradlew :graph-event-lineage:compileKotlin :graph-event-lineage:compileTestKotlin --warning-mode all --console=plain` 통과.
- `./gradlew projects --console=plain`은 `:graph-event-lineage`을 나열합니다.
- `./scripts/smoke-validate.sh all-smoke`은 새 모듈을 포함하고 전달합니다.
- `./scripts/smoke-validate.sh stale-check`은 예상 모듈 수를 보고하며 깨진 README 이미지 링크는 없습니다.
- `./scripts/smoke-validate.sh diagram-qa`은 구체적인 변경 다이어그램 증거를 가지고 통과되었습니다.
- `actionlint .github/workflows/Examples.yml`은 워크플로 편집 후 통과됩니다.
- `git diff --check` 통과.
- 6-R단계 검토에서는 `P0=0`, `P1=0`을 기록합니다.
