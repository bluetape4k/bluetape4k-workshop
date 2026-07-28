# Issue #328 Spec And Plan Review

**날짜**: 2026-07-02
**범위**: `leader/backend-comparison-lab` design과 implementation plan
**리뷰 유형**: Step 2-R 및 Step 3-R의 local equivalent

현재 tool contract가 user의 명시 요청 없이는 subagent를 spawn하지 말라고 하므로 native child-agent spawning은 사용하지 않았다. 필요한 동일 관점을 이 세션에서 검토하고 gate artifact로 여기에 기록했다.

## Step 2-R Spec Review

| 관점 | 결과 | 근거 |
|-------------|--------|----------|
| Completeness | PASS | spec은 module purpose, non-goal, deterministic test boundary, README locale parity, diagram, CI registration, acceptance criteria를 정의한다. |
| Source grounding | PASS | spec은 backend behavior를 기존 Redis, ZooKeeper, Kubernetes Lease module, lessons, `bluetape4k-leader` source semantic에 연결한다. |
| Boundary control | PASS | 기본 테스트는 Redis, ZooKeeper, Kubernetes, LocalStack 또는 backend-heavy service를 시작하지 않는다. 기존 runnable module이 실제 integration practice path로 남는다. |
| Learner clarity | PASS | Backend matrix, scenario table, metrics/events table, diagram requirement가 명시적이다. |
| Ecosystem usage | PASS | plan은 root BOM alias, bluetape4k validation helper, bluetape4k assertion, 기존 leader module link를 사용한다. |
| Diagram readiness | PASS | diagram requirement는 전체 bluetape4k diagram checklist, sequence best-practices, CairoSVG rendering, full-size visual inspection을 포함한다. |

## Step 3-R Plan Review

| 관점 | 발견사항 | 해결 |
|-------------|---------|------------|
| Performance | 첫 plan draft가 Redis, ZooKeeper, Kubernetes, Micrometer backend implementation을 deterministic comparison module에 끌어왔다. | Fixed. Production dependency는 이제 core/logging/Spring으로 제한되고 backend module은 linked practice target으로 남는다. |
| Stability | `LeaderBackendCatalog.findById`가 처음에는 `first`를 사용해 `NoSuchElementException`을 노출할 수 있었다. | Fixed. plan은 이제 blank ID를 검증하고 unknown backend ID에는 learner-friendly `IllegalArgumentException`을 던진다. |
| API design | `BackendCapability`가 spec에는 별도 항목으로 있었지만 plan의 `BackendProfile.kt` snippet에는 embedded되어 있었다. | Fixed. plan은 이제 dedicated `BackendCapability.kt`를 생성한다. |
| Code patterns | empty-list validation이 raw `require(...)`를 사용했다. | Fixed. plan은 이제 bluetape4k core의 `requireNotEmpty`를 사용한다. |
| Security/ops | 기본 runtime은 credential과 networked backend를 피한다. | PASS. Kubernetes는 기존 practice module에서 opt-in으로 남는다. |
| Documentation | README와 diagram requirement는 final learner asset을 검증하기에 충분히 상세하다. | PASS. |
| CI scope | Smoke와 Examples workflow update가 포함되어 있다. nightly는 scan 결과 필요할 때만 conditional이다. | PASS. |

## Gate Result

P0 발견사항: 0
P1 수정 후 발견사항: 0
P2 발견사항: 0

spec과 plan은 TDD implementation을 진행해도 된다.
