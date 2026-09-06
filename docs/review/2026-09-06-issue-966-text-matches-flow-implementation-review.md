# Issue #966 Aho-Corasick Flow 구현 검토

## 범위와 판정

- 대상: `feat/issue-966-text-matches-flow` →
  `feat/issue-965-privacy-derivative-payload`
- 범위: `kotlin/text-processing`의 `AbuseWordFilter.findMatchesAsFlow`, 회귀
  테스트, 영어·한국어 README, stale guard
- 최종 판정: **APPROVE — P0=0, P1=0, P2=0, P3=0**

## 확인 결과

- 새 함수는 안정판 `text-search:1.0.0`의 공개 `matchesAsFlow`에 직접
  위임하며 기존 동기 API를 변경하지 않는다.
- 같은 cold Flow를 두 번 collect했을 때마다 scan 결과가 재생성되고,
  `findMatches`와 automaton emission, overlap, source offset이 일치한다.
- empty dictionary와 clean text는 empty Flow를 만들며 NFC/NFKC가 원문 Kotlin
  `String` offset을 보존한다.
- 긴 입력에서 `take(1)`은 downstream emission을 하나로 제한하고 upstream
  completion에 `CancellationException`을 전달한다.
- 문서와 KDoc은 start offset 정렬이나 정확한 scan 중단 시점을 보장하지 않고
  emission parity와 내부 buffering 비보장만 설명한다.
- root `bluetape4k-dependencies:2.0.0` BOM이 `text-search:1.0.0`을
  해석하며 개별 module version/BOM은 추가하지 않았다.

## 독립 리뷰 처분

첫 리뷰는 P2 두 건을 보고했다. 기존 KDoc의 start position 순서 주장을 실제
automaton emission 순서로 수정했고, 단순 downstream count였던 `take(1)` 테스트에
긴 입력과 upstream `onCompletion` cancellation 검증을 추가했다. 재리뷰 결과는
P0=0, P1=0, P2=0, P3=0이며 최종 verdict는 APPROVE다.

## 검증 증거

- focused `AbuseWordFilterTest`: 16/16 PASS
- `:kotlin-text-processing:test`: 85/85 PASS
- root `detekt`: PASS
- `bash scripts/smoke-validate.sh stale-check`: PASS
- dependency insight: `text-search:1.0.0` via `bluetape4k-dependencies:2.0.0`
- `git diff --check`: PASS

## 잔여 범위

- upstream channel capacity와 내부 scan scheduling은 공개 계약으로 고정하지 않는다.
- benchmark나 throughput 개선 주장은 이 예제 범위가 아니다.
- merge는 다섯 개 stacked PR의 exact-head CI와 최종 사용자 승인 뒤에만 수행한다.
