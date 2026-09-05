# Issue #889 tokenizer dictionary preload와 readiness

## Context

`bluetape4k-text` 2.0.0의 `KoreanProcessor.preload()`와
`JapaneseProcessor.preload()`는 dictionary 준비를 요청 경로 밖의 suspend startup 단계로
옮길 수 있다. 기존 `kotlin/text-processing` 예제는 동기 facade를 직접 호출하므로 처음
접근하는 요청이 dictionary load를 수행할 수 있었고, 준비 중인 요청의 동작도 명시하지 않았다.

## Decision or Finding

- `TokenizerDictionaryReadiness`가 Korean/Japanese preload를 하나의 `Mutex` lifecycle로 묶는다.
- 상태는 immutable `DictionaryReadinessSnapshot`의 `NOT_READY`, `LOADING`, `READY`로 공개한다.
- 동시 caller는 성공한 동일 readiness 상태 값과 두 loader의 단 한 번 실행을 공유한다.
- 실패나 취소는 상태를 `NOT_READY`로 되돌린 뒤 원래 throwable을 전파한다. 다음 호출은
  증가한 attempt 번호로 전체 preload를 다시 실행한다.
- 두 loader가 끝난 뒤에도 coroutine 취소 상태를 검사한다. 잘못된 loader가 취소를 삼켜도
  `READY`를 공개하지 않는다.
- `runWhenReady`는 준비 전 block을 실행하지 않고 `NotReady`를 반환한다. 부분 초기화된
  검색 결과나 요청 thread의 dictionary load를 만들지 않는다.

## Outcome

애플리케이션 예제의 권장 순서는 dictionary preload, 기존 multilingual index 생성,
readiness-gated search 공개다. 기존 synchronous/coroutine index의 hit ID, score,
highlight 결과는 그대로 유지된다.

## Verification

- 동시 preload caller 16개에서 Korean/Japanese loader 각각 1회
- `LOADING` 중 요청 block 실행 0회
- 실패·취소 뒤 `NOT_READY`, 원래 예외 전파, attempt 2 retry 성공
- loader가 취소를 삼킨 경계에서 `READY` 공개 0회
- 실제 두 processor preload 뒤 기존 sync/coroutine index 검색 결과 parity
- `bluetape4k-dependencies:2.0.0` BOM constraint로 tokenizer 모듈 해석

## Future Guidance

Dictionary-backed tokenizer를 요청 처리에서 사용할 때는 동기 facade의 lazy load를 readiness로
간주하지 않는다. Startup이 preload 완료를 기다린 뒤에만 request readiness를 열고, 실패나
취소 상태를 성공으로 cache하지 않으며 다음 startup attempt가 전체 초기화를 재시도하게 한다.
