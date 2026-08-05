# Issue #567 reservation control-plane assertion 이관

## Context

`:commerce-reservation-control-plane`의 테스트 7개 파일이 JUnit assertion API를
사용하고 있었다. 모듈은 이미 `bluetape4k-assertions`를 테스트 의존성으로 제공하며,
Issue [#567](https://github.com/bluetape4k/bluetape4k-workshop/issues/567)는
reservation control-plane의 테스트 의미를 바꾸지 않고 assertion 표면만 이관하도록
범위를 한정했다.

## Decision or Finding

- 값 비교는 `shouldBeEqualTo`, 불리언 검증은 `shouldBeTrue`와 `shouldBeFalse`,
  비동등 검증은 `shouldNotBeEqualTo`를 사용한다.
- 예외 검증은 JUnit `assertThrows` 대신
  `io.bluetape4k.assertions.assertFailsWith`를 사용해 예외 타입과 반환된 예외
  진단을 같은 assertion 흐름에서 검증한다.
- PostgreSQL `withTables`, coroutine·persistence·동시성 시나리오와 기존 fixture는
  변경하지 않는다. 새 MockK double도 추가하지 않았으므로 field 선언 규칙은
  적용 대상이 아니다.
- 공통 test-framework wrapper나 새 의존성을 만들지 않고 released assertion API를
  직접 사용한다.

## Outcome

7개 테스트 파일의 JUnit assertion 호출 86건을 Bluetape assertion으로 이관했다.
production code, 모듈 경계, 의존성, 테스트 시나리오 동작은 변경하지 않았다.

## Verification

- 기준선 fresh `cleanTest + test`: 58 tests, 0 failures, 0 errors, 0 skipped.
- 변경 후 targeted 7개 클래스: 20 tests, 0 failures, 0 errors, 0 skipped.
- 변경 후 모듈 전체 `cleanTest + test`: 58 tests, 0 failures, 0 errors, 0 skipped.
- `:commerce-reservation-control-plane:compileTestKotlin` 성공.
- 대상 7개 파일에서 JUnit assertion import/call과 `mockk()` 잔여 없음.
- `git diff --check` 성공.
- 모듈 전용 `detekt` task는 존재하지 않으며, root `detekt` task graph에도 이
  Java 25 reservation 모듈이 포함되지 않아 N/A로 기록했다.

## Future Guidance

Workshop Kotlin 테스트를 추가하거나 이관할 때는 먼저 released
`bluetape4k-assertions` API의 실제 signature를 확인하고 intent-specific matcher를
선택한다. MockK가 필요한 경우 reusable double은 반드시 테스트 클래스 field로
선언하고, persistence·coroutine·동시성 경계는 assertion 교체와 함께 유지한다.
