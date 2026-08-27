# G0-BASE-POLICY 후속 child base 정책 7-Tier 검토

## 판정과 범위

이번 변경은 Epic #792의 후속 거버넌스 lane인 G0-BASE-POLICY다. 최신
`develop@69ba991778101c027282a80726de5eb1403da091`에서 시작했으며, 과거 PR
#831의 전체 branch/history를 재적용하지 않고 후속 child의 base ref 의미만
checker 계약으로 추출했다.

- Issue: #822, Parent Epic: #792
- 예정 branch: `chore/ecosystem-reuse-follow-up-base-policy`
- 변경 파일: checker, checker tests, `docs/ecosystem-reuse-train.json`, 이
  review와 lesson
- 명시적 제외: Kotlin 예제 production/test source, 새 dependency, individual
  Bluetape BOM/version pin, 이미 `develop`에 병합된 #821/#832/#833의 code와
  receipt
- 병합 정책: fresh exact-head approval 뒤 GitHub `rebase`만 허용하며,
  squash와 auto-merge는 사용하지 않는다.

## 핵심 계약

| 정책 | 허용 scope | 허용 OID | expected base | 의미 |
| --- | --- | --- | --- | --- |
| `parent-head` | `child` 또는 `coordinator` | `exact` 또는 `rebase-aware` | parent track의 `expected_head_ref` | 부모 branch를 직접 base로 삼는 기존 후속 흐름 |
| `repository-base-after-parent-merge` | `child`만 | `rebase-aware`만 | manifest의 `base_ref` | 부모가 repository에 병합된 뒤 최신 repository base에서 rebase하는 흐름 |

`base_ref_policy`는 필수 필드이며 열거형 밖의 값은 거부한다. 두 정책 모두
scope의 path/ref/OID/issue/review artifact 검사를 유지한다. repository-base
정책은 `child`와 `rebase-aware` 조합을 동시에 요구하고, parent ref를
repository base로 가장하는 경우를 거부한다.

## 소스·테스트 근거

| 대상 | anchor | 확인 내용 |
| --- | --- | --- |
| checker 정책 상수/필드 | `.github/scripts/check-ecosystem-reuse.py:60-70` | 두 base 정책과 필수 `base_ref_policy` 필드를 선언 |
| checker scope invariant | `.github/scripts/check-ecosystem-reuse.py:679-692` | enum, scope kind, OID 조합을 fail-closed로 검사 |
| checker base binding | `.github/scripts/check-ecosystem-reuse.py:737-747` | parent-head와 repository base의 expected ref를 분리 검사 |
| 테스트 fixture | `.github/scripts/test_check_ecosystem_reuse.py:156-168` | follow-up scope에 명시적 `parent-head`를 포함 |
| manifest positive/negative | `.github/scripts/test_check_ecosystem_reuse.py:767-816` | parent merge, exact/coordinator 오사용, unknown policy와 parent ref 혼용을 검증 |
| train-scope positive/negative | `.github/scripts/test_check_ecosystem_reuse.py:1287-1325` | repository base와 parent ref를 실제 PR scope binding에서 구분 |
| current manifest | `docs/ecosystem-reuse-train.json:54-88` | coordinator scope는 `parent-head`로 보존하고 새 review/lesson과 fresh receipt를 연결 |

## 7-Tier 결과

| Tier | 판정 | 근거 |
| --- | --- | --- |
| 1. 요구사항·범위 | PASS | #822/#792 continuation과 사용자 지시인 rebase merge를 반영했다. #831 전체 재사용과 병합된 child code는 제외했다. |
| 2. 계약·자료구조 | PASS | `base_ref_policy` 필수성, 두 enum, scope-kind/OID 조합, expected base binding을 checker와 manifest에 명시했다. |
| 3. 경계·보안 | PASS | repository-relative allowlist와 기존 control-character/path traversal 방어를 유지했다. token, credential, owner handle은 문서·manifest에 기록하지 않았다. |
| 4. 정확성·상태 | PASS | `parent-head`는 parent track head만, repository 정책은 manifest repository base만 허용한다. rebase-aware scope의 `base_oid/head_oid=null` invariant도 유지한다. |
| 5. 성능·안정성 | N/A | Python checker와 JSON/documentation만 변경하며 Kotlin runtime, DB, coroutine, Testcontainers 경로를 건드리지 않는다. |
| 6. 테스트·운영 | PASS | test-first RED에서 정책 미지원으로 6개 실패를 확인한 뒤 90개 전체가 `OK`가 됐다. current manifest, JSON, py_compile, diff 검증과 workflow receipt를 연결한다. hosted PR CI는 PR exact head 생성 전이므로 아직 실행하지 않는다. |
| 7. 문서·유지보수 | PASS | 한국어 review/lesson에 source/test anchor, raw fallback, receipt, stop condition을 남겼고, 기존 역사적 review를 덮어쓰지 않았다. |

## Bluetape 패턴 적용 여부

이 lane은 예제의 Kotlin production/test 동작을 변경하지 않는 Type E
거버넌스 변경이다. 따라서 `$bluetape-kotlin-patterns`의 Kotlin source/test
항목과 `bluetape4k-assertions` 사용 여부는 적용 대상이 아니며, 이는 누락이
아닌 범위에 따른 `N/A`다. 실제 예제 테스트의 assertions 전환은 후속 F2/R1/T1/I1
각 lane에서 별도 7-Tier 검토와 함께 수행한다. checker 자체는 표준 Python
라이브러리만 사용하며 새 dependency를 추가하지 않는다.

## Raw fallback 및 receipt

주요 검증 명령은 다음과 같다. hosted CI가 아직 없는 단계에서는 이 raw
명령과 출력이 재현 가능한 fallback이다.

```text
python3 .github/scripts/test_check_ecosystem_reuse.py -v
python3 .github/scripts/check-ecosystem-reuse.py --inventory docs/ecosystem-reuse-inventory.md --manifest docs/ecosystem-reuse-train.json --workflow .github/workflows/ecosystem-reuse-gate.yml --pins docs/governance/github-action-pins.json
python3 -m json.tool docs/ecosystem-reuse-train.json
python3 -m py_compile .github/scripts/check-ecosystem-reuse.py .github/scripts/test_check_ecosystem_reuse.py
git diff --check
```

- workflow type: `E` (`bluetape-maintenance`)
- run: `20260827T133807Z-2e478eff`
- base: `origin/develop@69ba991778101c027282a80726de5eb1403da091`
- receipt evidence: completed sequence 16 head
  `efde1f6ec96c2531e8a7dc3844805f69742eeab6e69d30e894a4abacfcfc38cd`
- `completion-check`: `complete=true`, missing component/lane/check/replacement/main proof 없음.

## Stop condition

다음 중 하나라도 발생하면 PR 생성 또는 후속 train으로 진행하지 않는다.

1. checker가 unknown policy, scope-kind/OID 조합, expected base mismatch를
   `FAIL`로 보고한다.
2. trusted manifest 비교에 fresh coordinator receipt가 없거나, 기존
   merged/verified 상태를 되돌리는 diff가 생긴다.
3. exact PR head의 base/head, changed paths, CI, review/thread, milestone,
   labels, assignee를 live read-back할 수 없다.
4. rebase merge 이외의 method가 선택되거나 fresh approval이 없다.
5. 7-Tier에서 P0 또는 P1이 하나라도 남거나, workflow receipt의
   `completion-check`에 누락된 required proof가 남는다.

현재 verdict: `P0=0, P1=0`; 새 PR 생성·hosted CI·merge는 별도 gate로
`PENDING`이다.
