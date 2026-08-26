# Ecosystem child scope governance 7-Tier review

## 범위와 판정

이번 lane은 Kotlin 예제 모듈의 동작을 바꾸지 않고, `docs/ecosystem-reuse-train.json`과 Python checker가 stacked child PR을 coordinator-owned scope로 정확히 식별하도록 고정하는 Type E governance 변경이다. P0의 기존 9개 track은 유지하며, #821의 exact head와 #822의 coordinator child를 follow-up scope로 명시한다.

## 7-Tier 점검

| Tier | 점검 결과 | 근거 |
| --- | --- | --- |
| 1. 요구사항·범위 | PASS | 고정 9-track을 늘리지 않고 #822 이슈와 #821 child의 경로·base/head ref·OID를 별도 scope로 고정했다. |
| 2. 계약·자료구조 | PASS | `scope_id`, `scope_kind`, 부모 track, exact ref/OID, issue, allowlist, review artifact를 필수 필드로 검증하고 coordinator receipt 없이는 scope 추가를 허용하지 않는다. |
| 3. 경계·보안 | PASS | 경로 traversal/control character, 잘못된 SHA, 중복 ID, child scope 간 overlap, 부모 track과 base ref 불일치를 fail-closed로 거부한다. |
| 4. 정확성·상태 | PASS | 여러 path 후보가 있어도 exact base/head ref가 하나인 scope만 선택하며, 기록된 OID가 있으면 실제 PR OID와 일치해야 한다. |
| 5. 동시성·자원 | N/A | 실행 중인 Kotlin/DB/비동기 자원 소유권을 변경하지 않는 manifest/checker 변경이다. 기존 Testcontainers 직렬화와 child workflow 정책은 유지한다. |
| 6. 테스트·운영 | PASS | 유효 child 선택, 잘못된 OID, overlap, trusted manifest receipt, 기존 fixed track 회귀를 checker unit test로 검증한다. hosted exact-head 재검증은 governance PR과 #821 재실행에서 확인한다. |
| 7. 문서·유지보수 | PASS | 이 review artifact와 #822 acceptance criteria가 coordinator scope의 의도와 재검증 경계를 기록한다. merge 전 fresh review와 approval을 별도 gate로 유지한다. |

## Bluetape4k 및 Kotlin 지침

- 이 변경은 Python checker와 JSON manifest만 다루므로 `bluetape-kotlin-patterns` 적용 대상 Kotlin 코드는 없다.
- `bluetape4k-dependencies` BOM, 개별 Bluetape BOM 금지, 명시적 버전 pin 금지 규칙을 변경하지 않는다.
- 실제 bluetape4k API 재사용 검증은 각 Kotlin child의 모듈별 review와 test evidence에서 계속 수행한다. 이번 governance scope는 그 evidence를 누락시키지 않도록 경로·ref·OID 경계를 고정한다.

## 남은 검증

- governance child exact head에서 checker unit test, manifest validation, `detekt`, diff check를 실행한다.
- PR이 hosted 된 뒤 ecosystem guard가 PASS하는지 확인하고, 그 결과를 기준으로 #821의 exact-head guard를 다시 실행한다.
