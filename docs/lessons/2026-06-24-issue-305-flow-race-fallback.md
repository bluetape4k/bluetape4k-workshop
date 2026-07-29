# Issue 305 Flow Race/Fallback Lesson

## 배경

race/fallback 예제는 Subject bridge module 옆에 있으며, multi-source read를 위한
source-composition 결정을 가르친다.

## 결정

timing-only assertion 대신 source lifecycle test를 사용한다. atomic flag는 loser
cancellation과 eager source startup을 검증하고, result assertion은 ordered output을 검증한다.

## 결과

module은 `race`, `concat`, eager concat, `merge`, materialized error handling을 언제
선택해야 하는지 문서화한다.

## 향후 지침

`merge`를 ordered fallback으로 설명하지 않는다. `merge`는 모든 source를 arrival order로
수집한다. priority order가 중요하면 `concat` 또는 eager concat을 사용한다.
