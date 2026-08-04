# Issue #561 — released Observation coroutine helper 재사용

## 맥락

`observability-basic`과 `observability-advanced`는 과거 provider helper의
제약을 우회하기 위해 각 모듈에 `observed`/`ObservationSupport` lifecycle wrapper를
복제하고 있었다. 이 wrapper는 observation을 시작·종료하고 오류를 기록했지만,
`withContext(Dispatchers.Default)` 같은 dispatcher 경계를 넘을 때 현재 Micrometer
Observation context를 coroutine에 전달하지 못했다.

현재 workshop root의 `bluetape4k-dependencies:1.3.1`이 해석하는
`bluetape4k-micrometer:1.11.0`에는 이 계약을 제공하는
`withObservationContextSuspending`이 이미 배포되어 있다.

## 결정 또는 발견

- local `ObservationSupport` 구현과 호출부를 제거하고 released
  `withObservationContextSuspending`을 두 observability 예제에서 직접 사용한다.
- helper source의 계약을 기준으로 cancellation은 다시 던지고, 일반 오류만 기록하며,
  observation은 성공·실패·취소 모두에서 정확히 한 번 stop되는지 검증한다.
- dispatcher boundary에서 `currentObservationInContext()`가 null이 아님을 회귀 테스트로
  고정한다. 이 테스트가 local wrapper에서 먼저 실패(RED)하고 released helper에서
  통과(GREEN)하는지 확인한다.

## 결과

- basic/advanced 서비스와 cache repository가 동일한 released helper를 사용한다.
- 앱 KDoc과 English/Korean README가 더 이상 local helper를 의존성이나 권장 패턴으로
  설명하지 않는다.
- observation lifecycle/context 계약을 검증하는 basic 테스트를 추가하고 중복 helper
  소스 두 개를 삭제했다.

## 검증

- `:observability-basic`의 dispatcher-context RED 재현: local wrapper에서
  `currentObservationInContext()`가 null로 실패.
- released helper 회귀 테스트 4개 통과: context propagation, success/error의 exactly-once
  stop, cancellation rethrow/cleanup.
- `:observability-basic:test`, `:observability-advanced:test` 및 정적 검사를 순차 실행한다.

## 향후 지침

consumer 예제에서 provider lifecycle/context helper를 다시 복제하지 않는다. 먼저
`bluetape4k-dependencies` BOM이 해석한 실제 artifact와 source signature를 확인하고,
released helper가 제공하는 coroutine context propagation·cancellation·stop 계약을
직접 사용한다. dispatcher boundary와 취소 경로를 함께 검증하지 않은 단순 span 존재
assertion만으로는 충분하지 않다.
