# Stacked child merge 뒤 ecosystem reuse parent scope 재결속

## Context

PR #899는 Issue #867의 leader audit export 예제를 parent branch에 올렸고,
child PR #900은 같은 branch를 base로 Issue #868 lease-extension observation을
squash merge했다. child merge 전에는 #867 scope가 parent diff 전체를 설명했지만,
merge 후에는 두 issue의 구현·문서 경로가 함께 parent diff에 들어갔다.

## Decision or Finding

child 단위 follow-up scope를 parent PR 검증에 그대로 재사용할 수 있다는 가정이
틀렸다. 기존 #867/#868 scope는 각각 한 issue의 경로만 허용하므로 parent 전체
diff를 만족하는 scope가 0개가 된다. run `33340638604` / job `99335539923`의
정확한 오류는 다음과 같다.

`PR changed paths must map to exactly one manifest track (found 0)`

parent branch에는 별도의 aggregate `stacked-parent-head` scope가 필요하다. 이
scope는 exact base/head ref와 `rebase-aware` OID 정책을 사용하고, 현재 두 child의
허용 경로와 parent 전용 검토·회귀 문서만 포함한다. scope 변경은 새
`coordinator_scope_receipt`로 승인 흔적을 남긴다.

## Outcome

`issue-867-868-leader-stack` scope와 fresh receipt를 manifest에 추가했다. 실제
parent diff의 leader 경로와 #867/#868 lesson을 함께 넣는 회귀 테스트는 scope
추가 전 RED(`found 0`), 추가 후 GREEN으로 전환됐다. child scope는 독립 PR
검증을 위해 그대로 유지한다.

## Verification

- base SHA `985beb08a0e16bec92dcd68d17bdb7a2e2bffc1`, parent head SHA
  `c4d3ac266c405d72bbf10dcc06afe5d37acae778` 확인
- `test_real_manifest_accepts_parent_diff_after_stacked_child_merge` RED/GREEN
  확인
- manifest canonical scope checksum:
  `f398d2165df3dc6b824574e7b3ac1ed8025c71ce4eb0230efb72646d6aed24d3`
- `python3 .github/scripts/test_check_ecosystem_reuse.py -v` — 96 tests,
  failures 0, skipped 0.
- trusted base manifest를 사용한 ecosystem checker — `PASS ecosystem-reuse
  inventory and train contract`.
- `python3 -m json.tool docs/ecosystem-reuse-train.json`, `git diff --check`와
  `audit-korean-terms.mjs`(3 files, findings 0) — PASS.
- repair push의 첫 hosted smoke run은 released `bluetape4k-leader`의
  `BoundedLeaderAuditExporter.close()` concurrent iteration에서
  `NoSuchElementException`을 보고했지만, `gh run rerun 33342152535 --failed`
  후 smoke/container/Examples Status가 모두 PASS했다. 이번 변경에서는
  upstream source를 수정하지 않고 재현 여부만 기록했다.
- 최종 PR checks: ecosystem, CI, build, examples, smoke/container, diagram,
  wrapper PASS; high-contention은 조건상 SKIPPED.

## Future Guidance

stacked child를 parent branch에 merge할 때마다 다음 순서로 확인한다.

1. `git diff --name-only <repository-base>...<parent-head>`로 parent 전체 변경
   집합을 다시 계산한다.
2. 기존 child scope 각각이 전체 집합을 만족하는지 확인하고, 어느 scope도
   만족하지 않으면 aggregate `stacked-parent-head` scope를 추가한다.
3. 새 scope의 exact ref, OID policy, allowed paths, review artifact와
   `coordinator_scope_receipt`를 함께 갱신한다.
4. checker unit/manifest/PR-scope를 로컬에서 실행한 뒤 push하고 hosted CI를
   새 exact head에서 확인한다.

parent scope가 통과했다는 사실만으로 child issue close나 PR merge를 앞당기지
않는다. 각 외부 상태 전이는 별도 live metadata와 fresh approval을 요구한다.
