# Issue #889 dictionary preload/readiness 설계 리뷰

## 판정

설계 단계에서 performance, stability, security, operator/ops, developer/API, user/caller
관점을 검토했다. 최종 P0/P1/P2/P3는 모두 0이다.

| 관점 | 판정 | 설계에 고정한 경계 |
|---|---|---|
| 성능 | PASS | 16개 concurrent caller가 loader를 각각 한 번만 호출하고 첫 요청 경로 밖에서 preload한다. |
| 안정성 | PASS | cancellation 확인 뒤에만 `READY`를 공개하며 실패·취소 뒤 다음 mutex owner가 재시도한다. |
| 보안 | PASS | 오류 원문·dictionary 경로를 state에 저장하거나 로그로 노출하지 않는다. |
| 운영 | PASS | `NOT_READY/LOADING/READY`를 명시하고 readiness 성공은 `READY`로 한정한다. |
| API | PASS | 기존 index API를 바꾸지 않고 additive guard와 result model을 제공한다. |
| 사용자 | PASS | startup → index build → gated search 순서를 양국어 예제로 제공한다. |

## 기각한 대안

- `GlobalScope.async`로 preload 유지: caller ownership과 cancellation을 잃으므로 기각했다.
- 최초 search에서 자동 preload: 요청 latency와 readiness 상태가 다시 숨겨지므로 기각했다.
- upstream provider 상태를 reflection으로 조회: 비공개 구현에 결합하므로 기각했다.
- 오류를 `NotReady` result로 흡수: startup 실패와 retry 원인을 호출자가 잃으므로 기각했다.
