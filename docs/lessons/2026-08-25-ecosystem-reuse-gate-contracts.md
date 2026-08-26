# Ecosystem 재사용 gate 계약 교훈

## 배경

`#792` Epic의 P0 gate와 A1/F1 stacked train을 검토하면서, 예제 모듈이
`bluetape4k` dependency를 선언했다는 사실과 실제 API를 재사용한다는 사실이
서로 다르다는 점을 확인했다. 이 문서는 같은 오류가 inventory와 PR gate에
다시 들어오지 않도록 수리한 계약과 검증 근거를 남긴다.

## 발견한 문제

- `actual_import`가 `build.gradle.kts`를 가리키고 `capability_api`가
  `libs.bluetape4k.*`인 행은 dependency resolution만 증명한다.
- 기존 checker는 `libs.*` API token을 검사하지 않고, build file 안의 alias를
  Bluetape 사용으로 인정했다. 따라서 source/test에 import가 없는 행도
  `released-bluetape4k`로 통과할 수 있었다.
- 기존 PR diff 검사는 `build.gradle.kts`와 version catalog만 수집해, 문서·테스트·
  workflow 변경이 manifest `allowed_paths`를 벗어나도 확인하지 못했다.
- 정적 manifest의 `PLANNED` node에는 OID가 없으므로, hosted PR gate가 현재
  base/head ref와 exact SHA를 런타임에 별도로 묶어야 한다.

## 결정한 계약

1. `actual_import`는 실제 Bluetape import가 있는 source/test 파일만 허용한다.
   Gradle build/catalog 파일은 dependency evidence로만 취급한다.
2. 아직 import가 없거나 dependency만 선언된 후보는 `actual_import=N/A`,
   `capability_api=candidate: ...`, `classification=shared-candidate`로
   기록하고 local `source_anchor`와 `test_anchor`를 유지한다.
3. `capability_api`의 `libs.*` catalog alias는 API 사용 증거가 아니다.
4. pull-request gate는 전체 `git diff --name-only`를 수집하고, 변경 경로가
   정확히 하나의 manifest node `allowed_paths`에 포함되는지 확인한다.
5. PR gate는 GitHub가 제공한 base/head ref 이름과 40-hex base/head SHA를
   manifest node의 기대값과 비교한다. 정적 `PLANNED` manifest의 OID는
   coordinator receipt가 생길 때까지 null로 유지할 수 있다.

## 적용한 검증

- checker unit test에 dependency declaration false positive, `libs.*` alias,
  단일 node scope, 잘못된 ref/OID, malformed SHA 회귀 사례를 추가했다.
- `python3 .github/scripts/test_check_ecosystem_reuse.py -v`에서 38개 테스트가
  통과했다.
- inventory의 dependency-only 행은 candidate marker로 변환했고, 실제
  `Uuid`, `Jackson`, `VirtualThreads`, `shouldBeEqualTo` 사용 행은 source/test
  파일과 API symbol을 가리키도록 고쳤다.

## 다음 적용 시 주의점

- 새로운 행을 추가할 때 build file을 `actual_import`로 재사용하지 않는다.
- dependencyInsight 결과는 resolved coordinate와 configuration을 증명하지만,
  source/test import를 대신하지 않는다.
- PR base가 바뀌거나 ancestor가 rebase되면 기존 child OID와 review artifact를
  재검증하고, stale train을 그대로 `PASS`로 표시하지 않는다.
- 이 교훈은 P0 gate 범위에 포함되므로 manifest와 workflow path trigger도 함께
  갱신해야 한다.
