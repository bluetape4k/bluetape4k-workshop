# Fixed child marker 전환 교훈

## 배경

coordinator가 parent를 squash merge한 뒤 child PR을 rebase하면 같은 구현
내용도 새로운 commit OID를 얻는다. 이때 review artifact의
`reviewed_implementation_oid`를 이전 manifest marker와 계속 같아야 한다고
요구하면, 정상적인 child도 `base` 또는 `head`의 선조성 검사에서 탈락한다.

## 원인

기존 fixed-node 계약은 `reviewed-ancestor`와
`reviewed_implementation_oid`를 하나의 값으로만 표현했다. 따라서
“manifest가 승인한 구현”과 “현재 rebase lineage에서 검토된 구현”의 기준이
달라지는 coordinator transition을 표현할 필드가 없었다. stale manifest를
허용하는 방식으로 우회하면 execution scope와 parent/ref 검증까지 무력화된다.

## 결정

- marker binding은 기본값 `manifest`로 두어 기존 manifest의 엄격한 equality를
  보존한다.
- coordinator가 rebase lineage를 승인할 때만 node를 `lineage`로 전환하고,
  top-level `reviewed_marker_transitions`에 `from`, `to`, `receipt_id`,
  `checksum`을 남긴다.
- trusted manifest의 binding에서 시작해 현재 binding으로 끝나는 fresh receipt만
  허용한다. 이전 receipt ID 또는 checksum 재사용은 거부한다.
- binding이 `lineage`여도 `base -> marker -> head`의 실제 Git ancestor와
  marker 이후 review-artifact-only tail은 반드시 유지한다. 코드 변경을 tail에
  숨기거나 marker를 임의로 띄우는 완화는 허용하지 않는다.

## 검증

- hosted run `33001959078`의 `execution scope changed`, `READY -> PLANNED`,
  marker ancestor, path mapping 오류를 원인별로 분리해 기록했다.
- checker 회귀에서 unknown binding, transition 누락/재사용, stale marker가
  있는 rebase lineage, 기존 strict binding을 각각 검사한다.
- `python3 .github/scripts/test_check_ecosystem_reuse.py -v`에서 77개 테스트가
  통과했고, current/trusted manifest와 JSON checker도 PASS했다.

## 다음 적용 시 주의점

1. parent squash 또는 coordinator base 이동 후 child를 검증할 때 먼저 trusted
   manifest의 binding과 receipt를 읽는다.
2. child rebase로 implementation OID가 바뀌면 review artifact marker를 새
   implementation commit으로 갱신하고, 필요한 경우 coordinator가 새
   transition receipt를 발행한다.
3. marker binding만 바꾸거나 기존 execution receipt를 재사용하지 않는다.
4. A1/F1/P2 stacked train은 각 PR의 실제 base/head ref, path scope, review
   tail, hosted exact-head 결과를 순서대로 다시 확인한다.

## 부모 branch 자동 삭제 뒤 실행 전 재계획

A1을 `develop`에 merge하면 GitHub의 자동 branch 삭제와 PR retarget으로 아직
실행되지 않은 F1의 `expected_base_ref`가 stale해질 수 있다. 이 경우 fixed
node를 READY로 올리거나 OID를 미리 채우지 말고, coordinator가 fresh receipt로
`PLANNED` 상태를 유지한 채 `expected_head_ref`/`expected_base_ref`/`parent_track`
중 필요한 ref/parent만 재계획한다. path, task, dependency, marker와 issue
범위는 이 전환에서 바꾸지 않는다.

checker는 baseline/current가 모두 `PLANNED`이고 current receipt가 `PASS`이며
receipt ID/checksum이 새 값일 때만 이 좁은 replan을 허용한다. 이후 child
rebase가 완료되면 실제 marker ancestor와 review-only tail을 fresh child
evidence로 다시 고정한다. 이 순서를 지키면 branch alias를 복구하는 원격
mutation 없이 protected `develop`과 rebase merge를 유지할 수 있다.
