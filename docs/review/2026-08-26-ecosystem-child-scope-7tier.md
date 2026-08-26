# Ecosystem child scope governance 7-Tier 검토

## 범위와 현재 판정

이번 Type E lane은 Kotlin 예제의 실행 동작을 변경하지 않고, stacked child PR의
OID 고정 정책을 manifest와 Python checker에 명시하는 governance 변경이다. 기존
9개 fixed track은 유지하며, coordinator-owned #823과 child PR #821을
`follow_up_scopes`로 분리한다.

현재 정책은 다음과 같다.

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
| 2. 계약·자료구조 | PASS | `scope_id`, `scope_kind`, `oid_policy`, 부모 track, exact ref, issue, allowlist, review artifact를 필수 필드로 검증한다. `exact`는 두 OID를 요구하고 `rebase-aware`는 두 OID의 `null`을 요구한다. |
| 3. 경계·보안 | PASS | path traversal/control character, 잘못된 SHA, 정책 외 값, 중복 ID, scope overlap, 부모 track과 base ref 불일치를 fail-closed로 거부한다. |
| 4. 정확성·상태 | PASS | path 후보가 겹쳐도 exact base/head ref가 하나인 scope만 선택한다. `exact`만 manifest OID equality를 검사하고, `rebase-aware`는 현재 rebase OID가 달라도 ref/path 계약을 유지한다. |
| 5. 동시성·자원 | N/A | 실행 중 Kotlin/DB/비동기 자원은 변경하지 않는다. 기존 Testcontainers 직렬화와 child workflow 정책을 유지한다. |
| 6. 테스트·운영 | IN PROGRESS | exact child 회귀, rebase-aware child/coordinator 수락, stale OID 거부, 잘못된 정책 거부, rebase 후 scope 선택 테스트를 추가한다. P0 hosted gate와 downstream exact-head 재검증은 이 문서 갱신 후 수행한다. |
| 7. 문서·유지보수 | PASS | 정책 선택 이유와 merge 전 fresh review/approval 분리를 이 문서와 #822에 기록한다. |

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
- checksum 입력은 `oid-policy-v1`과 두 scope의 정책·null OID·run ID를 포함한
  canonical policy string이며, manifest 자체를 다시 hash하지 않아 순환 의존을
  만들지 않는다.

## 남은 검증

1. checker unit test와 JSON/diff validation을 실행한다.
2. P0 exact head에서 `detekt`와 hosted Ecosystem/CI/Examples gate를 확인한다.
3. 새 P0 head를 기준으로 A1/F1/P2를 순서대로 rebase하고, 각 PR의 exact head,
   hosted checks, review/merge 대기 상태를 다시 읽는다.

최종 merge는 별도 fresh approval 없이는 수행하지 않는다.
