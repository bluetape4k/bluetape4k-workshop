# Issue 392 Test Null Assertion Cleanup

## 배경

Issue #392는 milestone 1.3.1 audit의 후속이며, production null assertion이 이미 제거된 뒤
test-side Kotlin `!!` 사용을 대상으로 했다.

## 결정

- 단순 test null assertion은 bluetape4k `shouldNotBeNull()`로 교체해 failure가
  `NullPointerException`이 아니라 assertion intent를 보여주게 한다.
- 같은 nullable ID 또는 response body를 재사용할 때는 non-null local value capture를 선호한다.
- nullability가 test 대상 behavior의 일부인 cursor-buffer와 framework lookup case는 focused
  follow-up으로 남긴다.
- source는 편집되지만 root Gradle build가 해당 module을 포함하지 않는 경우 excluded module을
  문서화한다.

## 결과

- Kotlin `!!` candidates moved from `163` to `68`.
- The cleanup touched 21 test files and removed 95 direct not-null assertions.
- The highest remaining cluster is Okio cursor buffer access, which needs a narrower helper-oriented cleanup rather than a mechanical conversion.

## 검증

- Baseline full build passed before edits.
- Affected compiles passed in three focused rounds.
- Affected-module tests passed for registered modules with `--max-workers=1`.
- `git diff --check` passed.
- Post-work full `./gradlew build --max-workers=1 --warning-mode all --console=plain` passed before PR creation.

## 향후 guard

test `!!`를 무작정 교체하지 않는다. 먼저 expression을 분류한다.

- response body, repository result, generated ID, query result: `shouldNotBeNull()`을 사용하고
  non-null value로 계속 진행한다.
- repeated nullable value: local non-null value를 한 번 capture한다.
- test subject 또는 framework nullability behavior: 명시적으로 남기거나, 테스트 대상 behavior를
  보존하는 local helper를 추가한다.
