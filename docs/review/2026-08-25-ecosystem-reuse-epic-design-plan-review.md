# ecosystem 재사용 Epic 설계·계획 통합 review

## 검토 범위와 현재 판정

이 문서는 `bluetape4k/bluetape4k-workshop`의 #792 Epic 설계, 실행 계획,
9-track manifest, inventory/checker, 격리 workflow를 검토한 결과다. 현재 P0
reviewed implementation commit은 `68d135a0ce2d3c74996fa9064ea8a3d3ab6c45fe`이며,
PR base는 `287b802faefe9d93a37d230d691c1f7325d41478`다. 이 문서는 P0 foundation과
stack topology를 다루며 Kotlin child 구현, Testcontainers 실행, PR merge는
범위 밖이다.

설계·계획과 P0 계약 수리는 PASS다. 이 문서의 마지막 변경은 위 구현 commit
뒤의 marker-only evidence-tail이며, fresh hosted exact-head read-back 전까지
P0 및 descendant는 `PENDING`이다.
설계 승인, live Epic mutation, merge-ready, merged는 서로 다른 상태다. 자식 PR은
각자 `$bluetape-kotlin-patterns`, 7-Tier review, resolved dependency receipt와
targeted test receipt를 제출해야 한다.

## 7-Tier 결과

| Tier | 판정 | 확인한 계약과 증거 | 남은 범위 |
|---|---|---|---|
| 1. 요구사항·범위 | PASS | fixed 9-track, coordinator/child follow-up 분리, manifest allowlist와 stacked base를 유지했다. | child 구현 범위는 각 PR에서 확인 |
| 2. 계약·자료구조 | PASS | fixed node는 `reviewed-ancestor`만 허용하고, active child는 active parent의 `reviewed_implementation_oid`(또는 exact parent의 `head_oid`)에 결속한다. 단일 review marker, base→ancestor→head 이력, 문서-only tail, follow-up `exact`/`rebase-aware` 필드를 검증한다. R2가 reviewed-ancestor P0를 재부모로 선택할 때 recorded 부모 OID 누락도 fail-closed로 거부한다. | hosted read-back 필요 |
| 3. 경계·보안 | PASS | path traversal/control character, invalid SHA, 정책 외 값과 bootstrap downgrade, overlap, token handoff, self-reference·비선조 marker·manifest/marker 불일치를 fail-closed로 거부한다. | hosted read-back 필요 |
| 4. 정확성·상태 | PASS | 실제 Git commit history와 review artifact tail을 검증하고, receipt/state 전이 및 trusted manifest graph를 보존한다. P2 Kotlin 경로도 pull-request gate에 포함한다. | P0/A1/F1/P2 fresh head 검증 필요 |
| 5. 동시성·자원 | N/A | governance Python/JSON/docs만 변경하며 Kotlin/DB 자원은 건드리지 않는다. | child module runtime에서 별도 검증 |
| 6. 테스트·운영 | PASS (로컬) | checker 68개 테스트, 실제 Git history 회귀, JSON/checker/diff 검증과 root `detekt` 106 actionable tasks를 통과했다. | marker tail 이후 hosted Ecosystem/Examples/CI 재실행 |
| 7. 문서·유지보수 | PASS | self-reference 회피 이유, bounded evidence tail, fresh review/merge approval 분리를 기록했다. | 최신 A1/F1 review artifact 동기화 |

## OID 정책 수리

fixed node는 자체 commit의 SHA를 같은 manifest commit에 기록할 수 없으므로
`oid_policy=reviewed-ancestor`를 사용한다. manifest의
`reviewed_implementation_oid`는 초기 `PLANNED` 상태에서 `null`이며, 각 PR의
review artifact에 다음 단일 machine-readable marker를 evidence-tail commit으로
기록한다.

```text
<!-- marker: reviewed_implementation_oid <40-hex implementation ancestor> -->
```

<!-- reviewed_implementation_oid: 68d135a0ce2d3c74996fa9064ea8a3d3ab6c45fe -->

checker는 marker가 PR base 이후의 ancestor이고 PR head의 선조인지 확인한다.
marker가 PR head와 같거나, 비선조이거나, marker 이후에 review artifact 외의
코드가 바뀌면 실패한다. `exact`와 `rebase-aware`는 follow-up scope에만
허용한다. 따라서 `null` OID를 exact로 가장하거나 임의 SHA를 주입하는 완화는
허용하지 않는다. reviewed-ancestor P0를 R2의 새 부모로 선택하는 경우에는
`parent_oid=P0.reviewed_implementation_oid`를 사용하고, 해당 기록이 없거나
불일치하면 checker가 fail-closed로 거부한다. legacy OID 필드는 계속 `null`로
유지한다.

## Bluetape4k 및 Kotlin 지침

- consumer 예제는 `bluetape4k-dependencies` BOM만 사용하고 개별 Bluetape BOM과
  명시적 버전 pin을 사용하지 않는다.
- A1/F1 및 후속 모듈은 실제 `bluetape4k-assertions` matcher와 ecosystem API를
  우선 사용하고, provider-gap/raw fallback은 inventory와 7-Tier artifact에
  근거를 남긴다.
- governance 변경 자체는 Python/JSON/Markdown 범위이며 Kotlin pattern 위반을
  새로 만들지 않는다.

## 로컬 검증

```text
python3 .github/scripts/test_check_ecosystem_reuse.py -q  # 68 tests PASS
python3 .github/scripts/check-ecosystem-reuse.py \
  --inventory docs/ecosystem-reuse-inventory.md \
  --manifest docs/ecosystem-reuse-train.json --bootstrap \
  --workflow .github/workflows/ecosystem-reuse-gate.yml  # PASS
python3 -m json.tool docs/ecosystem-reuse-train.json  # PASS
./gradlew --no-daemon --no-build-cache --max-workers=1 --no-parallel --rerun-tasks detekt  # 106 actionable tasks PASS
git diff --check  # PASS
```

## 상태·진입 조건

| 상태 | 필수 증거 | 다음 진입 조건 |
|---|---|---|
| P0 contract PASS | `68d135a0`, checker/manifest/local tests, marker-only evidence tail | hosted gate PASS 및 exact-head read-back |
| child READY 전 | exact parent/head read-back, dependencyInsight, Kotlin checklist, 7-Tier, targeted tests | P0/P1=0, skipped/disabled 없음 |
| MERGE_READY | exact head/base/merge-base, checks, reviews/threads, metadata, receipt | 새 merge approval |
| MERGED | 실제 merge SHA와 closeout receipt | 별도 closeout 범위 |

최종 merge, auto-merge, branch 삭제는 수행하지 않는다.
