# #776 assertion matcher governance 설계

## 문제와 목표

`bluetape4k-assertions`를 이미 사용하는 workshop 테스트에서도 Boolean과
`null`을 generic `shouldBeEqualTo`로 비교하면 실패 메시지가 검증 의도를
설명하지 못한다. 이 이슈는 현재 `develop`에서 확인된 좁은 잔여 범위를
의도 기반 matcher로 전환하고, 같은 회귀가 다시 들어오지 않도록 재현 가능한
정적 gate를 추가한다.

이번 child는 전체 assertion backlog를 한 번에 재작성하지 않는다. 이미
별도 후속 이슈로 연결된 모듈별 동작 검증은 그대로 두고, 현재 checkout에서
확인한 generic Boolean/null 비교와 legacy assertion import를 기준 이슈의
governance 경계로 고정한다.

## 현재 근거와 범위

- 대상 이슈: [#776](https://github.com/bluetape4k/bluetape4k-workshop/issues/776)
- stacked base: `feat/aws-settings-boundary-742`
- consumer 테스트에서 확인된 generic Boolean/null matcher는 9개 파일의
  19개 사용이다. `operations/job-console-core/src/testFixtures`의 한 건도
  같은 consumer 테스트 계약에 포함한다.
- consumer 테스트의 `kotlin.test.assert*` 또는 JUnit assertion import는
  현재 0개다. `build-logic/src/test`의 4개 파일은 Gradle build logic 자체를
  검증하므로 명시적 allowlist로 보존한다.
- `WebTestClient.expectStatus`, `jsonPath().isEqualTo` 같은 framework DSL과
  protocol/raw `check`는 이 child의 generic matcher 규칙에 포함하지 않는다.
  관련 동작 계약은 기존 후속 이슈와 inventory에서 별도로 추적한다.

## 선택한 접근

### 의도 기반 matcher 전환

현재 표현을 의미가 같은 assertion helper로만 교체한다.

| 기존 표현 | 전환 표현 |
|---|---|
| `shouldBeEqualTo(true)` | `shouldBeTrue()` |
| `shouldBeEqualTo(false)` | `shouldBeFalse()` |
| `shouldBeEqualTo(null)` | `shouldBeNull()` |

숫자·문자열·도메인 객체의 `shouldBeEqualTo`는 비교 의도가 equality이므로
그대로 둔다. 각 파일은 필요한 `io.bluetape4k.assertions` import만 가진다.

### 정적 guard

`.github/scripts/check-assertion-governance.py`는 `src/test`와
`src/testFixtures` 아래 Kotlin 파일을 주석·문자열을 마스킹한 뒤 검사한다.

1. consumer 파일의 `kotlin.test.assert*`, `org.junit.jupiter.api.Assertions`,
   `org.junit.Assert` import를 실패로 보고한다.
2. `shouldBeEqualTo(true|false|null)`를 실패로 보고한다.
3. `build-logic/**`의 legacy import는 build-logic allowlist로 집계하고
   실패시키지 않는다.
4. framework DSL, 문자열·주석 안의 예시, 일반 equality matcher는 오탐하지
   않는다.

검사 결과는 파일·행·규칙을 포함한 deterministic text로 출력하고, CI에서
직접 실행할 수 있어야 한다. 기존 ecosystem checker의
`_strip_comments_and_strings`를 재사용해 두 gate의 masking 동작을 일치시킨다.

## 실패·호환성·운영

- legacy import 또는 generic Boolean/null matcher가 새로 추가되면 정적
  guard가 non-zero로 종료되어 CI를 차단한다.
- build-logic allowlist는 directory boundary로만 적용하며 consumer 모듈의
  파일을 예외 처리하지 않는다.
- guard는 Kotlin compiler나 framework DSL을 해석하지 않으므로 syntax
  parser가 필요한 판단은 하지 않는다. 오탐이 확인되면 해당 표현을
  generic matcher가 아닌 명시적 허용 규칙으로 좁혀 회귀 테스트를 추가한다.
- 실제 테스트 동작은 matcher 함수의 기존 contract를 사용하며 생산 코드,
  dependency version, 외부 credential/network는 변경하지 않는다.

## 검증 계획

- RED: guard unit test가 legacy import와 generic Boolean/null matcher를
  각각 검출하고, build-logic allowlist·framework DSL·문자열/주석을 통과시키는
  기대를 먼저 실패시킨다.
- GREEN: guard 구현 후 unit test를 통과시킨다.
- 현재 9개 consumer 파일의 19개 비교를 matcher로 전환한 뒤 guard를 루트에서
  실행한다.
- 변경 모듈 targeted test, 필요한 integration test, `detekt`,
  `git diff --check`, `./gradlew projects`, workflow/actionlint 검사를
  순차 실행한다.
- review receipt와 Korean lesson에 범위, allowlist, 미해결 follow-up을
  기록하고 stacked manifest에 #776 exact scope를 추가한다.

## 완료 조건

- [ ] consumer 테스트의 legacy assertion import가 0개이고 build-logic
      allowlist가 guard 출력과 문서에 명시된다.
- [ ] 현재 generic Boolean/null matcher 19개가 의도 기반 matcher로 전환된다.
- [ ] guard unit test가 positive/negative/allowlist/framework DSL 회귀를
      증명하고 CI workflow에서 실행된다.
- [ ] 변경 모듈 검증, coverage matrix, lesson, 7-Tier review, stacked scope
      receipt와 PR metadata가 live read-back 된다.
- [ ] P0/P1 미해결 없이 terminal PR을 merge-ready 상태로 남긴다.

## Writer DoD

- [x] SPW-01 — 이슈, 기준 HEAD, 독자, 범위, source anchor를 고정했다.
- [x] SPW-02 — 경계, matcher 계약, allowlist, 실패·검증·완료 조건을 포함했다.
- [x] SPW-03 — Korean technical register와 코드 token 보존을 적용했다.
- [x] SPW-04 — 현재 checkout, live issue, 기존 checker/workflow 근거와 추적했다.
- [x] SPW-05 — Markdown 구조와 행 목록을 read-back했다.
