# #888 native graph algorithm 실행 관찰 Step 3-R 계획 review

## 검토 범위

- 설계: `docs/superpowers/specs/2026-09-05-issue-888-native-algorithm-observation-design.md`
- 계획: `docs/superpowers/plans/2026-09-05-issue-888-native-algorithm-observation-plan.md`
- 기준: Issue #888, 현재 `graph/abuser-detection`, `bluetape4k-graph 2.0.0`
- 관점: performance, stability, security, operator/ops, developer/API,
  user/caller와 main-session 통합

## findings와 처분

| 우선순위 | 관점 | finding | 계획 반영 |
|---|---|---|---|
| P1 | 안정성 | observer 일반 예외와 `CancellationException`의 처리가 테스트에 분리되지 않았다. | 일반 예외는 raw Throwable 없이 경고하고 결과를 유지하며 cancellation은 재전파하는 blocking/suspend 테스트를 추가했다. |
| P1 | 안정성 | `cancelAndJoin`만으로는 cancellation 전파를 증명하지 못한다. | job cancelled 상태와 `assertFailsWith<CancellationException>`을 사용하고 callback 직전/동시 취소 경계를 명시했다. |
| P2 | 성능 | suspend Flow가 PageRank를 한 번만 만들고 수집하는지 검증이 모호했다. | `verify(exactly = 1)`과 `topK`, 결과 수·정렬 parity를 고정했다. |
| P2 | 안정성 | 같은 fallback 값 20개는 호출 귀속 race를 검출하지 못한다. | `AUTO`/`JVM_ONLY`를 교차 실행하고 barrier와 observer event cardinality를 검증한다. |
| P2 | 보안 | observer Throwable을 함께 로깅하면 exception message나 stack trace가 노출될 수 있다. | 안정적인 고정 message만 기록하고 raw Throwable/provider ID는 로깅하지 않는다. |
| P2 | 운영 | 설계에 backend 마지막 실행 상태를 사용한다는 오래된 문장이 남았다. | backend last state를 읽지 않는 호출별 selector 계약으로 수정했다. |
| P2 | evidence | 디렉터리 단위 staging이 unrelated 파일을 포함할 수 있다. | exact 파일 staging과 `git diff --cached --name-only` 확인을 추가했다. |
| P3 | 보안 | provider ID 경계 테스트가 전체 제어문자와 64자 허용점을 포괄하지 않았다. | CR/LF, NUL, tab, 기타 제어문자, 대문자, 65자 거부와 64자 허용을 계획했다. |

## Step 3-R 필수 검사

| 검사 | 결과 | 근거 |
|---|---|---|
| spec·DoD와 task 매핑 | PASS | 모델, blocking, suspend, 문서/guard, 검증, PR 7개 task |
| 구현 가능한 순서 | PASS | 기준선 분리 → model RED/green → blocking → suspend → docs → full verify |
| 후행 산출물 선행 의존 없음 | PASS | 각 RED가 바로 다음 최소 구현만 요구한다. |
| 성공·실패·edge·concurrency·coroutine·backend capability | PASS | policy 3종, malicious ID, single-call, barrier, cancellation, Tinker/Neo4j/Memgraph |
| 구체적인 검증 명령 | PASS | targeted/full Gradle, integration, detekt, docs, stale, actionlint, ecosystem checker |
| README/KDoc/한국어 공개 문서 | PASS | module/root locale pair, public model/service KDoc, lesson/review/PR |
| 새 module 등록 | N/A | 기존 module 확장이며 workflow smoke/full/artifact 누락만 보완한다. |
| Spring Boot/Exposed/JDK preview | N/A | 해당 기술 표면을 변경하지 않는다. |
| cancellation/dispatcher | PASS | upstream suspend backend dispatcher 유지, real cancellation과 observer ordering 검증 |
| 성능·자원 안정성 | PASS | PageRank 1회·topK 전달, 새 thread/resource 없음, benchmark claim 없음 |
| cross-module 중복 | PASS | graph-core selector/observer를 소비하고 재구현하지 않는다. |
| rollback/호환성 | PASS | opt-in API만 추가하며 기존 점수 API와 저장 데이터는 유지한다. |

## 최종 판정

- P0: 0
- P1: 0
- P2: 0
- P3: 0
- 판정: **PASS**

사용자는 다섯 PR이 모두 merge-ready일 때만 승인을 요청하라고 지시했다. 이 명시적
실행 권한에 따라 중간 계획 승인 대기는 두지 않으며, merge는 마지막 fresh exact-head
승인 전까지 보류한다.
