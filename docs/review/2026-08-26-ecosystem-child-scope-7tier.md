# Ecosystem child scope governance 7-Tier 검토

## 범위와 현재 판정

이번 Type E lane은 Kotlin 예제의 실행 동작을 변경하지 않고, stacked child PR의
OID 고정 정책을 manifest와 Python checker에 명시하는 governance 변경이다. 기존
9개 fixed track은 유지하며, coordinator-owned #823과 child PR #821을
`follow_up_scopes`로 분리한다.

fixed node는 자체 commit의 SHA를 같은 commit 안에 기록할 수 없으므로
`oid_policy=reviewed-ancestor`를 사용한다. review artifact의
`reviewed_implementation_oid` marker는 PR base 이후의 검토된 구현 ancestor를
가리키며, 현재 PR head까지의 증거 tail은 해당 review artifact 하나만 변경해야
한다. 이 계약은 임의 SHA와 self-reference를 모두 거부하면서 최신 PR head가
검토된 구현의 후속 증거임을 확인한다.

follow-up scope 정책은 다음과 같다.

- `exact`: `base_oid`와 `head_oid`를 각각 40-hex SHA로 고정하고 실제 PR과
  일치하는지 검사한다.
- `rebase-aware`: 두 OID를 반드시 `null`로 두고, rebase 때 stale SHA가 되는
  비교를 생략한다. 대신 exact branch ref, 부모 관계, 변경 경로 allowlist,
  issue, review artifact, coordinator receipt는 계속 fail-closed로 검사한다.

따라서 #821은 `scope_kind: child`를 유지하면서 `oid_policy: rebase-aware`를
사용한다. child를 coordinator로 위장하거나 checker의 ref/path 경계를 완화하지
않는다.

## 7-Tier 점검

| Tier | 점검 결과 | 근거 |
| --- | --- | --- |
| 1. 요구사항·범위 | PASS | fixed 9-track을 늘리지 않고 #822 coordinator lane과 #821 child lane만 별도 scope로 고정했다. |
| 2. 계약·자료구조 | PASS | fixed node의 `reviewed-ancestor`, review marker, ancestor 관계, 단일 문서 evidence tail과 follow-up의 `exact`/`rebase-aware` 필드를 명시한다. |
| 3. 경계·보안 | PASS | path traversal/control character, 잘못된 SHA, 정책 외 값, 중복 ID, scope overlap, 부모 track과 base ref 불일치, marker 중복·누락을 fail-closed로 거부한다. |
| 4. 정확성·상태 | PASS | path 후보가 겹쳐도 exact ref로 하나를 선택한다. fixed node는 base→reviewed ancestor→head 이력과 문서-only tail을 검사하고, follow-up은 정책별 OID 계약을 검사한다. |
| 5. 동시성·자원 | N/A | 실행 중 Kotlin/DB/비동기 자원은 변경하지 않는다. 기존 Testcontainers 직렬화와 child workflow 정책을 유지한다. |
| 6. 테스트·운영 | IN PROGRESS | 실제 Git 저장소의 구현 commit·evidence tail, self-reference, 비선조 marker, 코드 변경 tail 및 malformed marker 회귀를 추가했다. P0/A1/F1/P2 hosted gate는 새 exact head에서 재실행한다. |
| 7. 문서·유지보수 | PASS | self-reference를 피하는 정책 선택, bounded tail, fresh review/approval 분리를 이 문서와 #822에 기록한다. |

## Bluetape4k 및 Kotlin 지침

- 이번 변경 대상은 Python checker, JSON manifest, 검토 문서뿐이므로 Kotlin 구현
  코드는 수정하지 않는다.
- consumer 예제의 `bluetape4k-dependencies` BOM 단일 사용, 개별 Bluetape BOM 및
  명시적 버전 pin 금지 규칙을 변경하지 않는다.
- 실제 Bluetape API 재사용과 `bluetape4k-assertions` 활용 여부는 A1/F1 및
  후속 모듈별 7-Tier review/test evidence에서 계속 확인한다. 이 governance
  contract는 그 evidence의 경로와 stacked ref 경계를 누락 없이 보존한다.

## Coordinator receipt

- `receipt_id`: `20260826T081952Z-c08d5362`
- `checksum`: `d29cccdf41f7873f078da6fe42b03661d918eafc32da3e7fd1ad1071e5c4f239`
- 이 receipt는 기존 follow-up scope coordinator 계약의 기록이다. fixed node의
  reviewed ancestor marker는 각 PR review artifact의 bounded evidence tail에서
  별도로 검증한다.

## 검증 명령과 남은 검증

```text
python3 .github/scripts/test_check_ecosystem_reuse.py -q  # 59 tests PASS
python3 -m json.tool docs/ecosystem-reuse-train.json  # PASS
git diff --check  # PASS
```

다음 검증은 contract commit 이후 marker-only evidence-tail commit을 만들고,
새 P0 head를 기준으로 A1/F1/P2를 순서대로 rebase한 뒤 각 hosted Ecosystem,
Examples, CI와 exact-head read-back을 확인하는 것이다. 최종 merge는 별도 fresh
approval 없이는 수행하지 않는다.
