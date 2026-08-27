# Epic #792 rebase train 전환 교훈

## 2026-08-27 P2 child base 재결속

F1 #815를 `develop`에 rebase merge한 뒤 GitHub는 후속 P2 #821의 base를
`develop`으로 자동 재결속하고, 부모 branch를 삭제했다. manifest의
`F1-P2-02`가 이전 `fix/ecosystem-reuse-field-service-contracts`를 계속
기대하자 PR scope checker는 F1 wildcard와 child path를 동시에 매칭해
`found 2`로 fail-closed했다.

이를 해결하기 위해 child scope에 `base_ref_policy`를 추가했다. 일반 child는
`parent-head`를 사용하고, 부모가 merge된 뒤 rebase 가능한 child만
`repository-base-after-parent-merge`를 fresh coordinator receipt와 함께
사용한다. 후자의 경우에도 `scope_kind=child`, `oid_policy=rebase-aware`,
`expected_base_ref=develop`, exact head ref와 path allowlist를 모두 검사한다.
따라서 checker를 약화하거나 삭제된 parent branch를 되살려 검증을 우회하지
않는다.

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
