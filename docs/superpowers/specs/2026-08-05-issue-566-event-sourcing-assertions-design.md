# Issue #566 이벤트 소싱 assertion 이관 설계

## 1. 결정 요약

`commerce/usage-metering-billing-event-sourcing` 테스트 21개 파일의 JUnit/Kotlin assertion idiom을 이미 릴리스된 `bluetape4k-assertions` API로 이관한다. 테스트의 도메인 동작, coroutine/Awaitility/Testcontainers 수명주기, MockK 상호작용, JUnit 실행 구조는 보존한다.

이번 작업은 assertion 표현의 일관성과 실패 메시지의 의도를 개선하는 테스트 리팩터링이다. production Kotlin, Gradle 의존성, 모듈 경계, 다른 commerce 모듈은 변경하지 않는다.

## 2. 근거와 현재 상태

- 대상 issue: [#566](https://github.com/bluetape4k/bluetape4k-workshop/issues/566)
- 대상 모듈: `:commerce-usage-metering-billing-event-sourcing`
- 대상 범위: 모듈 `src/test` 아래 assertion API를 사용하는 21개 Kotlin 테스트 파일
- baseline: `./gradlew :commerce-usage-metering-billing-event-sourcing:cleanTest :commerce-usage-metering-billing-event-sourcing:test --no-build-cache`
- baseline 결과: `BUILD SUCCESSFUL` (25초)
- baseline 기준 commit: `ad91ca06ecc1cbe5de99bfdeb8f425d03a35088d`
- 대상 모듈은 이미 `bluetape4k-assertions` 테스트 의존성을 선언하고 있다.
- 저장소의 인접 테스트에서 `shouldBeEqualTo`, `shouldBeTrue`, `shouldBeFalse`, `shouldBeNull`, `shouldNotBeNull`, `shouldBeInstanceOf`, `shouldContain`, `shouldHaveSize`, `shouldNotBeEqualTo`, `assertFailsWith`를 사용하고 있다.

## 3. 목표

1. 대상 21개 테스트에서 JUnit static assertion 및 `kotlin.test` assertion 의존성을 제거한다.
2. 값의 의도를 드러내는 Bluetape matcher와 Bluetape 예외 assertion을 적용한다.
3. 기존 테스트가 검증하는 이벤트 소싱 계약과 비동기/통합 테스트 수명주기를 그대로 유지한다.
4. Kotlin pattern 규칙에 맞는 import, 이름, 구조, coroutine 테스트 방식을 유지한다.
5. 전체 모듈 테스트를 순차 실행해 migration이 실제 동작을 보존함을 증명한다.

## 4. 비목표와 불변 조건

- production source, API, schema, fixture, Gradle dependency/version, workflow는 수정하지 않는다.
- 공통 assertion wrapper, 새 helper, 새 abstraction을 추가하지 않는다.
- assertion migration을 이유로 테스트의 timeout, retry, `Awaitility`, `runTest`, dispatcher, Testcontainers lifecycle을 조정하지 않는다.
- JUnit annotation/import는 assertion과 분리해 필요한 경우 유지한다.
- 재사용 MockK mock은 기존 field 선언을 유지한다. 새 mock을 만들 필요가 생기더라도 local mock으로 우회하지 않고 Kotlin pattern 기준을 따른다.
- 대상 모듈 밖의 assertion migration은 이번 PR에 포함하지 않는다.

## 5. 적용 방식

### 5.1 API mapping

| 기존 표현 | 적용할 Bluetape 표현 | 적용 원칙 |
| --- | --- | --- |
| `assertEquals(expected, actual)` | `actual.shouldBeEqualTo(expected)` | actual을 receiver로 두고 실패 의도를 보존한다. |
| `assertNotEquals(expected, actual)` | `actual.shouldNotBeEqualTo(expected)` | 단순 부등식만 유지한다. |
| `assertTrue(condition)` | `condition.shouldBeTrue()` | 조건식 자체를 receiver로 둔다. |
| `assertFalse(condition)` | `condition.shouldBeFalse()` | 부정 조건을 이중 부정으로 만들지 않는다. |
| `assertNull(value)` | `value.shouldBeNull()` | nullable 결과의 의도를 명시한다. |
| `assertNotNull(value)` | `value.shouldNotBeNull()` | smart cast가 필요한 경우 반환값/지역 변수 구조를 확인한다. |
| `assertInstanceOf<T>(value)` | `value.shouldBeInstanceOf<T>()` | 필요한 타입 추론을 컴파일로 확인한다. |
| collection equality/size/contains | `shouldBeEqualTo`, `shouldHaveSize`, `shouldContain` 등 | 전체 equality와 부분 containment를 구분한다. |
| `assertThrows<T> { ... }` 또는 동등한 exception assertion | `io.bluetape4k.assertions.assertFailsWith<T> { ... }` | JUnit/AssertJ/Kotlin test exception API를 남기지 않는다. |

mapping에 없는 assertion은 기계적으로 치환하지 않는다. 해당 값의 타입과 테스트 의도를 확인한 뒤 저장소에 이미 사용 중인 intent-specific matcher를 선택하고, 컴파일 오류가 나면 가장 작은 표현 변경으로 해결한다.

### 5.2 변경 순서

1. 대상 21개 파일의 assertion import와 호출을 목록화한다.
2. 이미 Bluetape matcher를 사용하는 인접 commerce 테스트와 API overload를 대조한다.
3. 파일별로 import를 정리하고 assertion 호출만 변환한다.
4. 각 변환 후 해당 테스트 클래스의 compile/test를 실행한다.
5. 전체 모듈 테스트를 Testcontainers 경합을 피하도록 단일 Gradle invocation으로 순차 실행한다.
6. JUnit/Kotlin assertion 잔존 검색, diff 검토, Kotlin checklist를 수행한다.

## 6. 위험과 완화

### R1. matcher overload 또는 nullable smart cast 불일치

- 증상: 컴파일 시 generic/type inference 오류 또는 nullable receiver 오류.
- 완화: API mapping을 타입별로 적용하고, 문제 파일의 기존 변수 구조를 보존한 채 명시적 지역 변수/타입만 최소 추가한다. wrapper를 추가하지 않는다.

### R2. 비동기/컨테이너 테스트 동작 회귀

- 증상: assertion 변경과 무관해 보이는 timeout, port, database lifecycle 실패.
- 완화: baseline을 이미 확보했고, coroutine/Awaitility/Testcontainers 코드는 변경하지 않는다. 클래스 단위 확인 뒤 동일 환경에서 전체 모듈을 한 번에 실행하고 실패 시 assertion diff와 lifecycle diff를 분리한다.

### R3. import 충돌 또는 잘못된 API 잔존

- 증상: JUnit `assertFailsWith`/`assertEquals`가 남거나 같은 이름의 다른 assertion이 자동 import된다.
- 완화: import를 명시적으로 정리하고 `org.junit.jupiter.api.Assertions`, `kotlin.test.assert*`, JUnit `assertThrows` 잔존을 대상 디렉터리에서 검색한다. JUnit annotation은 허용 목록으로 구분한다.

### R4. assertion 의미가 단순 치환 과정에서 바뀜

- 증상: collection equality와 containment, exception type과 message 검증의 의미가 달라짐.
- 완화: 각 assertion을 expected/actual와 검증 목적에 맞춰 intent-specific matcher로 변환하고, 테스트 본문/fixture/예외 검증 범위를 변경하지 않는다.

## 7. 검증 계약

구현 완료를 주장하려면 다음을 모두 만족해야 한다.

1. 대상 21개 파일 외의 production/의존성 변경이 없다.
2. 대상 파일에서 JUnit/Kotlin assertion API가 제거되고 Bluetape assertion import가 의도에 맞게 사용된다.
3. 변경된 각 테스트 클래스의 compile/test가 통과한다.
4. `./gradlew :commerce-usage-metering-billing-event-sourcing:cleanTest :commerce-usage-metering-billing-event-sourcing:test --no-build-cache`가 통과한다.
5. `git diff --check`가 통과한다.
6. `$bluetape-kotlin-patterns`의 테스트 및 final checklist 항목을 적용하고, 해당 없는 production/coroutine 항목은 근거와 함께 N/A로 기록한다.
7. 한국어 lesson에 이관 범위, API mapping, 검증 결과, 향후 assertion migration 시 주의점을 기록한다.

실패한 검증은 숨기지 않고 DoD에 `PENDING` 또는 `BLOCKED`로 남긴다. 테스트 인프라의 일시적 실패는 재현 횟수와 로그 증거를 함께 기록한 뒤, 코드 변경과 분리해 판단한다.

## 8. 대안 검토

### 대안 A: 공통 assertion wrapper

거부한다. 이 issue의 목적은 Bluetape matcher를 테스트 코드에 직접 적용해 intent를 드러내는 것이며, wrapper는 API 의미와 실패 위치를 숨긴다.

### 대안 B: 여러 branch/PR로 패키지 분할

거부한다. 내부 검토는 패키지 순서로 나눌 수 있지만, 모듈 전체의 assertion contract를 하나의 독립적으로 검증 가능한 변경으로 제공하는 편이 issue 범위와 CI 증거를 명확하게 한다.

## 9. 롤백

모든 변경은 테스트 파일의 import와 assertion 표현에 한정한다. 검증 불합격 시 feature branch의 마지막 green commit으로 되돌리거나 failing file 단위로 수정/제외해 되돌릴 수 있다. production/runtime state나 외부 데이터는 변경하지 않는다.

## 10. 설계 승인 기준

- [x] issue 범위와 baseline 근거가 명시되었다.
- [x] 권장안과 거부한 대안의 이유가 명시되었다.
- [x] API mapping과 불변 조건이 명시되었다.
- [x] async/container/import/의미 보존 위험과 완화책이 명시되었다.
- [x] 구현·검증·lesson의 완료 기준이 명시되었다.
- [ ] six-lens 설계 review 완료
- [ ] 구현 plan review 및 사용자 plan 승인
