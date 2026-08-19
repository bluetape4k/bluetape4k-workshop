# Issue #525 Field Service Dispatch 설계 리뷰

- 날짜: 2026-08-20
- 대상: `docs/superpowers/specs/2026-08-20-issue-525-field-service-design.md`
- 기준: Issue #525, Epic #523, `bluetape-workflow` Type-A Step 2-R
- 결정: 새 `optimization/field-service-dispatch` 독립 예제와 deterministic fake를
  사용하며, Epic child는 하나의 stacked PR에 종속시키지 않고 `develop` 기반의
  유사 예제 train으로 순차 추가한다.

## 검토 범위와 증거

설계 문서, 현재 `optimization/planning-contracts`의 internal visibility와 callback
DTO/service, 기존 operations console/Exposed/virtual-thread 패턴, 공식 Timefold
Field Service Routing 및 real-time replanning 문서를 대조했다. 모든 lane은 문서와
읽기 전용 저장소 조회만 수행했고, 실제 구현·Gradle·Testcontainers 검증은 아직
시작하지 않았다.

## 독립 관점 결과

| 관점 | 결과 | 핵심 증거와 처분 |
|---|---|---|
| Performance | P0=0, P1=0, P2=6 | 초기 lane이 planner 차원, plan paging/index, CAS/query budget, executor admission, ETag race, benchmark schema를 지적했다. 입력 상한(140-153), plan history/page bound(392-395), CAS chunk/query/`EXPLAIN`(397-400), lifecycle(148-153), composite ETag(359-365), benchmark schema/path(461-470)로 보완했고, 구현 계획에서 수치 검증을 유지한다. |
| Stability | P0=0, P1=0, P2=1 | native lane은 bounded retry 뒤 응답하지 않아 main-session fallback으로 수행했다. outbox replay·event digest 수렴(256-257, 444-446), timeout/cancellation permit 반환(150-153), route 전체 rollback(245-257)을 확인했다. 실제 lease/재시작/컨테이너 검증은 구현 단계로 이연한다. |
| Security | 초기 P1=2 → 수정 후 P0=0, P1=0 | 초기 lane의 DOM XSS와 자유 형식 score/explanation 노출 지적을 반영했다. 구조화 score와 raw text 거부(126-130, 190-193, 318-324), DOM API/`textContent`/CSP/allowlist(353-357), canary 테스트(453-459)를 고정했다. 최신 재검증 lane은 bounded retry 뒤 응답하지 않아 main-session read-back으로 P1 해소를 확인했다. |
| Operator/Ops | P0=0, P1=0, P2=2 | native lane timeout 후 main-session fallback으로 loopback/demo 경계(132-136), bounded queue·shutdown 순서(148-153), benchmark artifact(466-470), migration 범위(401-405, 505-517)를 확인했다. health/readiness와 운영 runbook은 구현 계획의 후속 task로 둔다. |
| Developer/API | P0=0, P1=0, P2=2 | native lane timeout 후 main-session fallback으로 module 경계와 HTTP surface(108-124, 281-306), #524 wire/local envelope 분리(308-332), BOM/모듈 등록 표면(476-487)을 확인했다. 실제 Kotlin symbol/import와 `./gradlew projects`는 계획 승인 후 검증한다. |
| User/caller | P0=0, P1=0, P2=2 | native lane timeout 후 main-session fallback으로 synthetic-only 비목표(62-69), redacted read model(126-130), browser flow/오류 계약(448-459), README locale 갱신(478-485)을 확인했다. 사용 예제와 unsupported production integration 안내는 구현 문서 task에 둔다. |

## 통합 판정

| 우선순위 | 건수 | 통합 처분 |
|---|---:|---|
| P0 | 0 | 없음 |
| P1 | 0 | 없음. worker route 전체 CAS/원자 commit과 local callback preflight 경계가 닫혔다(240-257, 308-332). |
| P2 | 7 | 구현 계획의 bounded query/lock·health/readiness·runbook·Kotlin symbol·caller documentation task로 이연한다. 현재 설계의 수치·실패 계약은 문서에 남겼고, plan review에서 각 항목을 정확한 명령과 파일로 매핑한다. |
| P3 | 0 | 없음 |

### 통합 결정

1. approval은 업무 version을 증가시키지 않고 proposal/audit만 set-based CAS로
   확정한다(240-244).
2. dispatch confirmation은 한 worker route 전체를 CAS하며 `workerScheduleRevision`
   경쟁 시 route 전체를 rollback한다(245-257, 439-446).
3. #524는 Field Service assignment authority가 아니다. local
   `FieldServiceCallbackEnvelope`를 preflight한 뒤 실제 #524 wire DTO에 지원 필드만
   매핑하고, preflight 실패 시 #524 호출과 local 상태 변경을 모두 건너뛴다(308-332).
4. provider/Timefold entitlement가 없는 상태에서 deterministic fake 통과를
   production integration PASS로 승격하지 않는다(505-511).

## Writer SPW-01..05

| 항목 | 상태 | 증거 |
|---|---|---|
| SPW-01 독자·목적·출처·범위 | PASS | 설계 1-43, Issue/Epic 및 공식 Timefold 링크 |
| SPW-02 선택지·경계·실패·수용 기준 | PASS | 설계 71-106, 219-279, 489-501 |
| SPW-03 한국어 기술 문체·용어 | PASS | `audit-korean-terms.mjs`: findings=0 |
| SPW-04 현재 소스·외부 계약 대조 | PASS | #524 internal DTO/service와 wire version 부재를 24-43, 308-332에 반영 |
| SPW-05 read-back·Markdown·공백 | PASS | 설계 전체 read-back 및 `git diff --check` 통과 |

## 남은 범위와 다음 gate

- 현재는 설계 리뷰 단계다. 구현 코드, 테스트, benchmark JSON, `./gradlew projects`,
  module build, Testcontainers, browser smoke evidence는 아직 없다.
- 다음은 사용자 설계 문서 검토 후 `writing-plans`로 구현 계획을 작성하고, 같은
  여섯 관점과 main integration으로 plan review를 수행하는 단계다.
- 이 리뷰는 PR 생성·CI·merge 승인을 의미하지 않는다. 실제 merge는 exact head,
  CI, review/thread, mergeability를 새로 읽은 뒤 별도 승인이 필요하다.

## 최종 상태

`PASS` — P0/P1 없음. P2는 구현 계획에 명시적으로 이연되었으며, 구현 전 설계
문서 검토가 다음 사용자 gate다.
