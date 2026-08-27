# Epic #792 rebase train 전환 교훈

## 발견

receipt bootstrap에서 topology component 상한이 8인데 11개 component를 한 번에
승인하면 topology를 등록할 수 없다는 계약 오류를 확인했다. source 변경 전 run을
취소하고 8개 aggregate component로 새 run을 시작해 receipt 체인을 복구했다.

또한 현재 `develop`에는 P0/A1이 이미 통합되어 historical foundation와 A1 branch가
삭제됐는데, manifest의 A2/R1/T1/I1/F1 expected base가 이전 branch를 가리키고
있었다. 삭제된 ref를 되살려 검증을 우회하지 않고 coordinator scope를 현재
semantic branch로 갱신한 뒤, child implementation evidence가 생길 때에만 fixed
node scope를 새 receipt로 전환하기로 했다.

## 결정

1. topology는 helper의 최대 8개 제한을 넘지 않도록 독립 PR train을 aggregate
   component로 묶되, 실제 issue/track 범위는 plan과 manifest에 그대로 보존한다.
2. stale follow-up coordinator ref는 fresh `coordinator_scope_receipt`와 명시적
   allowlist로 갱신한다. fixed node의 planned base ref 재계획은
   `planned_scope_replan_receipts`에 track별 fresh receipt를 기록하고, 실행
   receipt는 `PENDING/null`로 분리한다. 기존 receipt ID/checksum을 재사용하지
   않는다.
3. child branch가 rebase되면 implementation marker, targeted test, CI, review를
   exact new head에서 다시 검증한다. 이전 green 결과는 증거로 재사용하지 않는다.
4. 병합 method는 사용자 지시에 따라 `rebase merge`로 고정한다. `squash`와
   auto-merge는 사용하지 않으며, exact head에 대한 fresh 승인 후에만 실행한다.

## 재발 방지

- init 직후 topology 최대치와 aggregate 설계를 대조한다.
- P0/A1 merge 또는 branch 삭제 후 manifest expected ref를 live GitHub와 비교한다.
- coordinator scope 변경마다 새 receipt ID/checksum, JSON/checker test, review/lesson을 함께 갱신한다.
- 각 PR merge 직전 `mergeStateStatus`, exact head, CI/review/thread, method를 다시 읽는다.

## F2 시작 전 base 재계획

F1과 P2-02를 병합한 뒤 F2의 planned node가 삭제된 F1 branch를 계속
`expected_base_ref`로 가리키는 것을 확인했다. 이미 superseded된 #831을
rebase하거나 재사용하지 않고, coordinator scope에서 F2의 ref만 최신
`develop`으로 바꾸고 새 `planned_scope_replan_receipts.F2`를 기록했다.

이 재계획은 `parent_track=F1`, F2 head/path/task/dependency 범위를 보존하며,
실행 receipt는 `PLANNED/PENDING/null`로 남긴다. 따라서 F2 child의 source 변경,
review, CI, merge evidence는 이 coordinator rebase merge 후 최신 `develop`에서
새로 발행해야 한다.

첫 PR 검증은 coordinator scope의 `expected_head_ref`가 이미 병합된
`chore/ecosystem-reuse-train-closeout`으로 남아 있어 새 PR head를 거부했다.
이 실패는 scope와 PR ref를 함께 exact하게 검증하는 계약이므로, historical
branch를 되살리는 대신 fresh `coordinator_scope_receipt`로 현재 replan
branch를 scope head에 결속했다.
