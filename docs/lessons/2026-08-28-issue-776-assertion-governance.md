# Issue #776 assertion matcher governance

## Context

기존 후속 이슈를 확인한 현재 `feat/aws-settings-boundary-742` 기준에서
consumer Kotlin 테스트에 generic `shouldBeEqualTo(true|false|null)` 19건이
남아 있었다. legacy assertion import는 consumer에서 0건이었지만
`build-logic/src/test`에는 build logic 자체의 `kotlin.test` 계약을 검증하는
16개 import가 있었다.

## Decision or Finding

- Boolean은 `shouldBeTrue`/`shouldBeFalse`, nullable 값은 `shouldBeNull`로
  바꿔 assertion 실패가 검증 의도를 직접 드러내도록 했다.
- 숫자·문자열·도메인 객체 equality는 의미가 다르므로 `shouldBeEqualTo`를
  유지했다.
- `check-assertion-governance.py`는 `src/test`와 `src/testFixtures`의
  consumer만 검사한다. `kotlin.test.assert*`, JUnit assertion import와
  generic Boolean/null matcher를 실패시키고, `build-logic/**` legacy import는
  명시적 allowlist 수치로만 집계한다.
- comment/string masking은 기존 ecosystem checker의
  `_strip_comments_and_strings`를 재사용했다. `WebTestClient` 같은 framework
  DSL과 protocol/raw `check`는 이 guard가 해석하지 않으며 해당 동작 계약은
  연결된 후속 이슈에서 별도로 판정한다.

## Outcome

19개 matcher를 10개 파일에서 intent matcher로 전환했고, 새 guard는
consumer legacy/generic finding 0건과 build-logic allowlist 16건을
deterministic report로 출력한다. guard unit test는 legacy 검출, line number,
allowlist, framework DSL, comment/string, 일반 equality의 회귀를 고정했다.

## Verification

- `python3 .github/scripts/test_check_assertion_governance.py -v`: 4 tests `OK`
- `python3 .github/scripts/check-assertion-governance.py`: `scanned=1151`,
  `findings=0`, `allowlisted_build_logic_legacy_imports=16`
- `./scripts/smoke-validate.sh assertion-governance`: guard test와 scan 모두
  통과
- 변경 모듈 targeted Gradle test 7개 task: `BUILD SUCCESSFUL`
- `./gradlew projects --console=plain`: module registry `BUILD SUCCESSFUL`
- `actionlint .github/workflows/ecosystem-reuse-gate.yml`: 출력 없는 성공
- `python3 -m py_compile ...`: 성공
- `git diff --check`: 성공

## Future Guidance

새 consumer 테스트에서 Boolean/null 결과를 비교할 때는 값의 equality가
아니라 의도 matcher를 먼저 선택한다. 새 legacy import나 generic matcher를
추가하면 ecosystem workflow의 assertion governance guard가 실패해야 한다.
build logic의 Kotlin Test 사용을 consumer 예외로 넓히지 말고, 새 예외가
필요하면 경로·이유·회귀 테스트를 함께 allowlist에 기록한다. protocol이나
framework DSL의 raw check를 자동 변환하지 말고 해당 계약을 검증하는 후속
이슈와 inventory 근거를 먼저 연결한다.
