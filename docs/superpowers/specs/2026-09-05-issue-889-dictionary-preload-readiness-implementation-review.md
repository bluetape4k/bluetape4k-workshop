# Issue #889 dictionary preload/readiness 구현 리뷰

## 리뷰 범위

- Issue: `#889`
- Base: `origin/develop`
- Head: `feat/issue-889-dictionary-preload-readiness`
- 의존성 기준: `bluetape4k-dependencies:2.0.0`
- 대상: `kotlin/text-processing`의 Korean/Japanese suspend preload, readiness request gate,
  실패·취소 retry, 기존 index 호환성, 양국어 문서와 검증 표면

## 요구사항 추적성

| ID | 요구사항 | 구현·검증 증거 | 상태 |
|---|---|---|---|
| A-889-01 | startup에서 두 dictionary preload | 기본 loader가 두 processor `preload()`를 순서대로 호출 | PASS |
| A-889-02 | 준비 전 요청의 명시적 거절 | `runWhenReady`의 `NotReady`, block 실행 0회 테스트 | PASS |
| A-889-03 | 동시 preload 중복 방지 | 16 caller에서 loader별 1회, 같은 readiness 상태 instance 검증 | PASS |
| A-889-04 | 실패·취소 안전한 retry | `NOT_READY` 복귀, 원래 throwable, attempt 2 성공 테스트 | PASS |
| A-889-05 | 기존 검색 결과 호환 | 실제 preload 뒤 sync/coroutine hit·score·highlight parity | PASS |
| A-889-06 | consumer 검증 표면 | README, stale guard, coverage, lesson, manifest 갱신 | PASS |

## Six-lens review

| 관점 | 판정 | 근거 |
|---|---|---|
| 성능 | P0=0, P1=0, P2=0, P3=0 | 성공 preload를 instance마다 한 번 공유하고 요청 block에서 dictionary IO를 시작하지 않는다. Latency 향상 수치는 주장하지 않아 benchmark는 적용하지 않는다. |
| 안정성 | P0=0, P1=0, P2=0, P3=0 | `Mutex`가 initializer를 직렬화하고 실패·취소는 `NOT_READY`로 복구한다. Loader가 취소를 삼키는 경우도 마지막 `ensureActive()`가 `READY` 공개를 차단한다. |
| 보안 | P0=0, P1=0, P2=0, P3=0 | Snapshot은 상태와 bounded attempt만 포함하고 경로, 입력 text, 예외 원문을 저장하지 않는다. Credential이나 외부 network 표면이 없다. |
| 운영 | P0=0, P1=0, P2=0, P3=0 | Startup 순서와 `NOT_READY`/`LOADING`/`READY`, 실패 시 원래 예외와 retry 계약을 README에 명시했다. |
| API | P0=0, P1=0, P2=0, P3=0 | 새 coordinator는 기존 index API를 변경하지 않는다. 기본 loader는 공개 2.0.0 suspend API를 사용하고 test seam은 함수형 인자뿐이다. |
| 사용자 | P0=0, P1=0, P2=0, P3=0 | 양국어 README에 startup 코드, 준비 전 결과, 동시 공유, 실패·취소, 기존 동기 facade의 첫 호출 blocking 가능성을 같은 의미로 설명한다. |

## 저장소 위험 점검

- 기존 module 변경이므로 `settings.gradle.kts` 등록과 신규 Kover wiring은 해당하지 않는다.
- `Examples.yml`은 이미 `kotlin/text-processing/**` path filter, smoke test와 test artifact를
  포함하므로 중복 수정하지 않고 현재 등록을 검증한다.
- `scripts/smoke-validate.sh` all-smoke에도 module test가 이미 포함되어 있으며 #889 stale guard만 추가한다.
- README language/parity, 용어, actionlint, stale guard, ecosystem reuse unit 검사를 실행한다.

## 알려진 범위 제한

이 예제는 Spring lifecycle bean이나 HTTP readiness endpoint를 새로 만들지 않는다. Framework별
startup adapter는 caller가 `preload()` 완료 뒤 readiness를 열도록 연결해야 한다. Sudachi의
별도 dictionary download와 `sudachiTest`는 이 API의 대상이 아니다.

## 최종 판정

구현과 문서 리뷰에서 P0/P1 문제는 발견되지 않았다. 다음 fresh 검증을 통과했다.

- module test: 65 passed
- repository `detekt`: 112 tasks executed, build successful
- README language: offenders 0
- 변경한 root/module README parity: failures 0
- Korean terminology audit: 신규·변경 문서 7개, findings 0
- `actionlint`, JSON parse, `git diff --check`: passed
- stale-check: tokenizer readiness guard 포함 전체 passed
- ecosystem reuse checker unit tests: 110 passed
- dependency insight: `bluetape4k-dependencies:2.0.0` constraint에서
  `tokenizer-korean:1.0.0`, `tokenizer-japanese:1.0.0` resolved

Repository 전체 README parity 검사는 이 branch가 변경하지 않은 optimization module 3개의
기존 language-switch 누락을 보고했다. 이번에 변경한 root와 `kotlin/text-processing` pair는
각각 별도 검사에서 failures 0이며 #889 범위의 회귀가 아니다.

- P0: 0
- P1: 0
- P2: 0
- P3: 0
