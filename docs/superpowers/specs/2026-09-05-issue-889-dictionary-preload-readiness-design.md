# Issue #889 dictionary preload/readiness 설계

## 목표

`kotlin/text-processing`이 Korean/Japanese dictionary를 요청 경로보다 먼저 suspend
preload하고, 완료 전 요청에는 부분 tokenizer 결과 대신 명시적인 readiness 상태를
반환하는 consumer 예제를 제공한다.

## 확인한 기준선

- GNO의 `bluetape4k-text` PR #261은 `KoreanProcessor.preload()`와
  `JapaneseProcessor.preload()`를 startup/readiness 단계에서 호출하도록 권장한다.
- `bluetape4k-dependencies:2.0.0`은 두 tokenizer를 각각 `1.0.0`으로 해석하며 실제 jar에
  두 suspend `preload()` API가 존재한다.
- 기존 `MultilingualSearchIndex`와 `CoroutineMultilingualSearchIndex`는 문서 index 생성과
  query tokenization에서 processor를 직접 사용하므로 preload를 호출하지 않은 소비자는
  최초 접근에서 호환성 bridge의 동기 로딩을 만날 수 있다.

## 설계

### 상태 모델

`DictionaryReadinessStatus`는 `NOT_READY`, `LOADING`, `READY` 세 상태만 가진다.
`DictionaryReadinessSnapshot`은 현재 상태와 1부터 증가하는 attempt를 immutable 값으로
반환한다. 초기 상태의 attempt는 0이고, 실패·취소 뒤에는 실패한 attempt 번호를 보존한다.

### 단일 초기화 경계

`TokenizerDictionaryReadiness.preload()`는 하나의 `Mutex`에서 다음 순서를 수행한다.

1. 이미 `READY`이면 저장된 동일 readiness 상태 값을 반환한다.
2. 새 attempt의 `LOADING` 상태 값을 공개한다.
3. Korean과 Japanese processor의 suspend preload를 순서대로 호출한다.
4. coroutine cancellation을 다시 확인한 뒤 `READY` 상태 값을 공개한다.
5. 실패나 cancellation이면 `NOT_READY`로 복귀하고 원래 예외를 재전파한다.

동시 caller는 같은 mutex를 기다린다. 첫 성공 뒤 대기 caller는 loader를 다시 호출하지 않고
동일한 `READY` 상태 값을 받는다. 첫 caller가 실패·취소되면 다음 caller가 새 attempt로
재시도한다. 별도 scope나 `GlobalScope`를 만들지 않으므로 caller cancellation과 ownership을
보존한다.

### 요청 gate

`runWhenReady`는 호출 시점의 readiness 상태가 `READY`가 아니면 block을 실행하지 않고
`DictionaryReadyResult.NotReady`를 반환한다. `READY`이면 block 결과와 관찰한 상태 값을
`DictionaryReadyResult.Ready`로 반환한다. Readiness가 준비되지 않았을 때 search block을
평가하지 않으므로 partial tokenizer 결과가 외부로 나가지 않는다.

Startup 사용 순서는 다음과 같다.

1. `TokenizerDictionaryReadiness.preload()`
2. 기존 `CoroutineMultilingualSearchIndex.indexOf(...)`
3. 요청마다 `runWhenReady { index.search(...) }`

기존 동기·suspend index API 자체는 변경하지 않는다.

## 오류·보안·운영 경계

- Loader 예외 메시지나 dictionary 경로를 readiness 상태 값 또는 `toString()`에 저장하지 않는다.
- 실패와 cancellation은 삼키거나 결과 객체로 바꾸지 않고 원래 throwable을 재전파한다.
- `LOADING`은 진행 상태일 뿐 health 성공이 아니다. Readiness 성공은 `READY`만 허용한다.
- 외부 config server, dictionary 파일 배포, tokenizer 품질 변경은 범위 밖이다.

## 수용 기준

| ID | 기준 |
|---|---|
| A-889-01 | 초기·loading 요청은 block을 실행하지 않고 명시적 `NotReady`를 반환한다. |
| A-889-02 | 성공한 16개 동시 preload caller가 Korean/Japanese loader를 각각 한 번만 실행한다. |
| A-889-03 | cancellation과 실패 뒤 `NOT_READY`로 복귀하고 다음 attempt가 성공한다. |
| A-889-04 | 취소된 preload가 `READY`를 공개하지 않는다. |
| A-889-05 | 실제 2.0.0 processor preload 뒤 기존 index 검색 결과가 동기 facade와 호환된다. |
| A-889-06 | BOM-only, 양국어 README, workflow/stale/coverage/lesson/manifest gate가 통과한다. |
