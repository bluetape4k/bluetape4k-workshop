# Issue #867/#868 parent stack ecosystem scope repair 7-Tier 검토

## 검토 범위와 기준

대상은 child PR #900이 parent PR #899의 `feat/issue-867-leader-audit-export`
branch에 squash merge된 뒤 발생한 ecosystem reuse scope 실패다. 이번 변경은
`bluetape4k` runtime 동작을 추가하지 않고, parent PR 전체 diff를 manifest의
하나의 follow-up scope에 결속하는 유지보수 작업이다.

검토 기준은 다음과 같다.

- PR #899: `https://github.com/bluetape4k/bluetape4k-workshop/pull/899`
- merged child PR #900: `https://github.com/bluetape4k/bluetape4k-workshop/pull/900`
- 기준 base SHA: `985beb08a0e16bec92dcd68d17bdb7a2e2b2ffc1`
- 기준 parent head SHA: `c4d3ac266c405d72bbf10dcc06afe5d37acae778`
- `.github/scripts/check-ecosystem-reuse.py`
- `.github/scripts/test_check_ecosystem_reuse.py`
- `docs/ecosystem-reuse-train.json`
- GitHub Actions run `33340638604`, job `99335539923`

## 실패 증거와 원인

기존 PR #899의 ecosystem gate는 다음 결과를 남겼다.

| 항목 | 결과 |
| --- | --- |
| base/head | `develop` → `feat/issue-867-leader-audit-export` |
| base/head SHA | `985beb08a0e16bec92dcd68d17bdb7a2e2bffc1` → `c4d3ac266c405d72bbf10dcc06afe5d37acae778` |
| 실패 job | `Validate ecosystem reuse contract` / `99335539923` |
| 정확한 오류 | `PR changed paths must map to exactly one manifest track (found 0)` |
| 동반 checks | CI, Examples, smoke/container, build, wrapper, diagram checks는 PASS |

기존 `issue-867-leader-audit` scope는 #867 경로만, `issue-868-leader-observation`
scope는 child PR #900의 #868 경로만 설명한다. 그러나 child merge 후 parent diff는
두 구현과 두 문서 집합을 함께 포함한다. #867 scope는 #868 lesson/spec를 모두
포함하지 않고, #868 scope는 #867 lesson/spec를 모두 포함하지 않으므로 전체
변경 집합을 만족하는 scope가 0개가 된다. 이는 runtime failure나 flaky test가
아니라 stacked PR의 diff 경계가 바뀐 뒤 manifest를 재평가하지 않은 계약 누락이다.

## 결정과 scope 경계

`docs/ecosystem-reuse-train.json`에 `issue-867-868-leader-stack` follow-up scope를
추가했다.

- `scope_kind`: `child`
- `parent_track`: `P0`
- `expected_base_ref`: `develop`
- `expected_head_ref`: `feat/issue-867-leader-audit-export`
- `base_ref_policy`: `stacked-parent-head`
- `oid_policy`: `rebase-aware`
- `issue_numbers`: `[867, 868]`
- 허용 경로: 두 leader 구현 집합, 두 issue의 spec/plan/lesson/review,
  `docs/coverage-matrix.md`, `docs/lessons/README.md`, manifest, README validator,
  ecosystem checker regression test와 이번 review artifact
- 새 coordinator receipt: `20260831T-issue-867-868-parent-stack-scope`
- scope canonical JSON SHA-256: `f398d2165df3dc6b824574e7b3ac1ed8025c71ce4eb0230efb72646d6aed24d3`

기존 #867/#868 child scope는 각 child PR의 독립 검증 경계로 유지한다. aggregate
scope는 parent PR 전체 diff를 검증할 때만 exact base/head ref로 선택되며, 두 issue의
번호가 함께 기록되는 것은 parent stack의 declared scope를 표현하기 위한 것이다.
checker 로직과 production source를 넓혀 모든 문서 변경을 허용하지 않고, 현재
parent diff와 회귀 검증에 필요한 경로만 열었다.

## 7-Tier 판정

| Tier | 판정 | 근거 |
| --- | --- | --- |
| T1 요구사항 | PASS | child merge 뒤 parent PR 전체 diff가 하나의 manifest track에 결속되어야 한다는 실패 조건과 repair 목표를 고정했다. |
| T2 설계 | PASS | `stacked-parent-head`와 `rebase-aware`를 사용하고 exact base/head ref, issue 번호, 허용 경로, coordinator receipt를 함께 기록했다. |
| T3 구현 | PASS | 변경은 manifest, checker regression test, review/lesson/index 문서에 한정되며 production Kotlin/dependency 동작은 바꾸지 않는다. |
| T4 테스트 | PASS | aggregate scope가 없을 때의 RED 재현과 scope 추가 후 GREEN 회귀 테스트를 분리해 확인한다. |
| T5 통합 | PASS | #867과 #868 child scope는 그대로 두고 parent 전체 diff만 aggregate scope가 수용한다. exact ref disambiguation으로 단일 scope 선택을 보장한다. |
| T6 운영 | PASS | parent head와 base를 manifest에 고정하고 새 coordinator receipt로 scope 변경의 책임과 재검증 시점을 남긴다. |
| T7 회귀·보안 | PASS | 네트워크·credential·secret·runtime lifecycle을 변경하지 않으며, 실패 receipt와 경로 allowlist만 확장한다. |

## 검증 증거

- 회귀 테스트 RED: aggregate scope 추가 전
  `test_real_manifest_accepts_parent_diff_after_stacked_child_merge`가
  `found 0`으로 실패했다.
- 회귀 테스트 GREEN: aggregate scope 추가 후 동일 테스트가 통과했다.
- `python3 .github/scripts/test_check_ecosystem_reuse.py -v` — 96 tests,
  failures 0, skipped 0.
- trusted base manifest를 사용한 ecosystem checker — `PASS ecosystem-reuse
  inventory and train contract`.
- `python3 -m json.tool docs/ecosystem-reuse-train.json` — PASS.
- `git diff --check` — PASS.
- `audit-korean-terms.mjs` — 3 files, findings 0.
- hosted GitHub Actions는 repair push 뒤 새 exact head에서 재실행해야 한다.

## 남은 위험과 비대상

- 이 scope는 PR #899의 현재 parent head에 대한 경계이며, 새로운 child를 merge한
  뒤에는 전체 diff와 scope를 다시 대조해야 한다.
- `stacked-parent-head`는 parent merge를 의미하지 않는다. PR #899 merge와
  Issue #867/#868 close는 별도의 live review·CI·승인 gate다.
- 기존 child scope를 하나로 합치지 않았으므로 child PR 단위의 재현성과 parent
  PR 단위의 통합 검증을 동시에 유지한다.

## Writer와 한국어 자연스러움 gate

- **SPW-01 PASS:** review 독자, 목적, Issue/PR, SHA, workflow run/job, source
  경로와 미확정 hosted CI gate를 기록했다.
- **SPW-02 PASS:** 실패 증거, 원인, 결정, 허용 경계, 7-tier 판정, 검증, 위험과
  비대상을 포함했다.
- **SPW-03 PASS:** 한국어 기술 register를 사용하고 API/path/command/URL/SHA와
  정확한 오류 문구를 보존했다.
- **SPW-04 PASS:** checker의 matching/ref disambiguation 동작과 실제 parent
  diff 대표 경로를 대조했다.
- **SPW-05 PASS:** 최종 Markdown read-back에서 표·inline code·URL·목록 구조를
  확인하고, 자연스러움 점검 대상 문장을 다시 읽었다.
- **KO-01~KO-07 PASS:** 사실·식별자 보존, hollow claim 제거, 용어 일관성,
  contextual terminology audit 대상 확인을 마쳤다.

## DoD 상태

- [x] 실패 run/job과 정확한 오류를 기록
- [x] aggregate `stacked-parent-head` scope와 fresh receipt를 manifest에 추가
- [x] RED/GREEN 회귀 테스트 추가
- [x] parent 전체 diff 허용 경계와 child scope 독립성 기록
- [ ] repair commit 이후 hosted GitHub Actions 새 run PASS
- [ ] fresh exact-head 승인 후 PR #899 merge

최종 상태: `PENDING` — 로컬 계약 repair는 완료했지만, push 후 새 hosted CI와
parent PR merge gate가 남아 있다.
