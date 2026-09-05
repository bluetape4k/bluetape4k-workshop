# #889 dictionary preload/readiness 구현 계획

**Goal:** Korean/Japanese dictionary preload를 요청 경로 밖에서 공유하고 readiness 미완료
요청을 명시적으로 거절하면서 기존 multilingual index 결과를 보존한다.

**Architecture:** `TokenizerDictionaryReadiness`가 `Mutex`와 immutable volatile snapshot으로
두 processor preload의 lifecycle을 소유한다. `runWhenReady`는 `READY`일 때만 기존 search
block을 실행한다.

**Tech Stack:** Kotlin 2.4, Java 25, Kotlin Coroutines, bluetape4k-text 2.0.0 BOM,
JUnit 5, bluetape assertions

### Task 1: 상태와 요청 gate를 TDD로 구현한다

**Files:**
- Create: `kotlin/text-processing/src/main/kotlin/io/bluetape4k/workshop/text/readiness/TokenizerDictionaryReadiness.kt`
- Create: `kotlin/text-processing/src/test/kotlin/io/bluetape4k/workshop/text/readiness/TokenizerDictionaryReadinessTest.kt`

- [ ] 초기 `NOT_READY` snapshot과 block 미실행 RED 테스트
- [ ] 상태·result model과 `runWhenReady` 최소 구현
- [ ] snapshot invariant와 ready result 테스트

### Task 2: concurrent preload와 retry 경계를 TDD로 구현한다

- [ ] 16개 동시 caller에서 두 loader가 각각 한 번만 실행되는 RED 테스트
- [ ] `Mutex` 기반 single initializer와 `ensureActive()` 구현
- [ ] cancellation 뒤 `NOT_READY`, 원래 cancellation 전파, 다음 attempt 성공 검증
- [ ] 일반 실패 뒤 `NOT_READY`, 원래 예외 전파, 다음 attempt 성공 검증

### Task 3: 실제 processor와 기존 index 호환성을 검증한다

- [ ] 기본 loader가 `KoreanProcessor.preload()`와 `JapaneseProcessor.preload()`를 호출하도록 연결
- [ ] 실제 preload → coroutine index 생성 → gated search 성공 테스트
- [ ] 기존 synchronous index와 hit ID·score·highlight parity 테스트
- [ ] module full test 실행

### Task 4: 문서와 저장소 검증 표면을 갱신한다

- [ ] module/root README 양국어에 startup/readiness/retry 예제 추가
- [ ] `Examples.yml`, stale-check, coverage matrix, lesson/index 갱신
- [ ] ecosystem reuse manifest에 #889 독립 PR scope 등록
- [ ] README language/parity, terminology, actionlint, stale, ecosystem 검증

### Task 5: Type A review, commit, PR, exact-head CI를 완료한다

- [ ] implementation traceability와 six-lens review에서 P0/P1=0 수렴
- [ ] fresh module test와 repository detekt 실행
- [ ] Lore commit과 `[2.0.0]` Korean PR 생성
- [ ] milestone `2.0.0`, assignee, exact head, 12/12 hosted checks, review thread 0 확인
- [ ] PR을 OPEN으로 유지하고 #890으로 순차 전환
