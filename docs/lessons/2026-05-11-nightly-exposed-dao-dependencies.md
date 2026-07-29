# Nightly 변경은 기존 컴파일 드리프트를 드러낼 수 있다

## Context

bluetape4k-workshop Nightly 워크플로를 smoke lane과 full lane으로 나누었다.
이 PR은 Exposed workshop 모듈에 이미 있던 CI 컴파일 실패를 드러냈다.

## Decision or Finding

워크플로만 바꾼 PR에서 컴파일이 실패하면, 워크플로 변경이 원인이라고
단정하기 전에 실패한 소스를 먼저 확인해야 한다. CI는 로컬 증분 빌드가
숨기고 있던 의존성 드리프트를 드러낼 수 있다.

## Outcome

`io.bluetape4k.exposed.dao.*` 헬퍼 함수를 import하는 모듈은 이제
`bluetape4k-exposed-dao` 의존성을 명시적으로 선언한다.

## Verification

- 다음 로컬 컴파일이 성공했다:
  - `:exposed-domain:compileKotlin`
  - `:exposed-dao-web-transaction:compileKotlin`
  - `:exposed-sql-web-virtualthread:compileKotlin`
  - `:exposed-sql-webflux-coroutines:compileKotlin`
- PR #29 CI가 성공했다.

## Future Guidance

- 워크플로 PR 실패를 실제 저장소 신호로 다룬다.
- import한 헬퍼 package와 선언된 모듈 의존성을 대조한다.
- 의존성 alias를 catalog로 관리해서 모듈 빌드가 명시적으로 유지되게 한다.
