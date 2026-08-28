# #776 assertion matcher governance 7-Tier 검토

## 검토 범위와 판정

이번 terminal child는 #742 exact head 위에서 consumer assertion 잔여를
정리하고 회귀 방지 guard를 추가한다. 생산 코드와 dependency version은
변경하지 않으며, 현재 checkout에서 재현된 generic Boolean/null 19건과
consumer legacy import 0건, build-logic 예외 16건만 다룬다.

현재 구현 판정은 **P0=0, P1=0, P2=0 / IMPLEMENTATION VERIFIED**다.
hosted CI, PR metadata/thread read-back, 전체 train rebase merge는 별도
closeout gate로 남긴다.

## 수용 증거

| 항목 | 수용 기준 | 구현·검증 증거 |
|---|---|---|
| Intent matcher | Boolean/null 검증이 helper 이름으로 의도를 표현 | 10개 Kotlin 테스트 파일의 19개 `shouldBeTrue`/`shouldBeFalse`/`shouldBeNull` 전환 |
| Legacy boundary | consumer의 Kotlin/JUnit assertion import 0건 | guard `findings=0`; `build-logic` 16개 import는 allowlist 집계 |
| Static guard | 새 legacy/generic matcher 회귀를 deterministic report로 차단 | `.github/scripts/check-assertion-governance.py`와 4개 unit test |
| False-positive boundary | framework DSL, 문자열·주석, 일반 equality를 보존 | guard negative test: `expectStatus`, `jsonPath().isEqualTo`, string/int equality |
| Workflow | PR/push에서 guard와 test를 실행 | `.github/workflows/ecosystem-reuse-gate.yml` path 및 두 실행 step |
| Scope | #742 위 stacked child 변경만 허용 | `assertion-governance-followup` manifest scope와 fresh receipt 예정 |

## 7-Tier 결과

| Tier | 판정 | 근거 |
|---|---|---|
| 1. 요구사항·범위 | PASS | #776의 matcher governance와 회귀 gate에 한정하고 모듈별 raw protocol 후속은 건드리지 않았다. |
| 2. 정확성·계약 | PASS | Boolean/null만 intent matcher로 바꾸고 숫자·문자열·도메인 equality는 보존했다. |
| 3. 수명주기·동시성 | N/A | assertion 표현과 Python 정적 검사만 변경하며 runtime lifecycle·DB·container를 추가하지 않는다. |
| 4. 보안·비밀 | PASS | guard report는 파일·행·규칙만 출력하고 secret/credential/payload를 수집하지 않는다. |
| 5. 성능·자원 | PASS | repository-relative Kotlin 파일 scan과 기존 masking helper 재사용으로 bounded deterministic 검사를 유지한다. |
| 6. 테스트·검증 | PASS (local) | guard 4 tests, assertion smoke, 7개 변경 모듈 test, projects, actionlint, py_compile, diff check를 통과했다. hosted exact-head 검증은 PR 이후 수행한다. |
| 7. 문서·운영 | PASS (local) | coverage matrix, lesson, spec/plan, workflow와 stacked manifest에 범위·allowlist·stop condition을 기록한다. |

## Kotlin checklist와 writer DoD

- **KT-01:** touched Kotlin tests는 `bluetape-kotlin-patterns` testing/checklist
  reference를 읽고, matcher import와 기존 `bluetape4k-assertions` API를
  재사용했다.
- **KT-02:** 현재 checkout scan, sibling assertion helper 검색, 9개 잔여 파일
  및 test fixture를 직접 확인한 뒤 변경했다.
- **KT-03:** production validation/exception/coroutine contract는 변경하지
  않고 assertion 의미만 좁게 교체했다. P0/P1 위반은 없다.
- **KT-04:** guard RED→GREEN, targeted Gradle test, projects, actionlint,
  py_compile, diff check를 수행했다.
- **KT-05:** local verdict `P0=0/P1=0`; hosted CI와 merge closeout만 pending이다.

- **SPW-01:** #776 issue, current scan, 대상 독자와 source anchor를 고정했다.
- **SPW-02:** review 범위·수용 증거·7-Tier·검증 gap·DoD를 포함했다.
- **SPW-03:** Korean technical register를 적용하고 code/command/ref를 보존했다.
- **SPW-04:** 구현·guard 출력·workflow·coverage matrix와 대조했다.
- **SPW-05:** rendered Markdown을 read-back했고 assertion 수·allowlist 수를
  evidence와 일치시켰다.

## 남은 게이트

- [ ] manifest fresh coordinator receipt와 local exact scope checker PASS
- [ ] terminal PR 생성 후 assignee `debop`, milestone `1.4.0`, issue labels,
      Korean body/`## DoD Status` live read-back
- [ ] hosted CI exact head와 review/thread read-back
- [ ] 전체 train final approval 이후에만 #847→#848→#849→#850 rebase merge
