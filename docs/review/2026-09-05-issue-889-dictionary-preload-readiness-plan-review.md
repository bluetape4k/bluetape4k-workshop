# Issue #889 dictionary preload/readiness 계획 리뷰

## 범위·순서 검토

- API 존재와 BOM `2.0.0` 해석을 구현 전에 확인했다.
- 상태/request gate → concurrency/retry → 실제 processor/index parity → 문서·CI 순서가
  실패 원인을 좁힐 수 있다.
- 기존 index를 수정하지 않아 source·behavior 호환성 위험을 제한한다.
- Cancellation, concurrent waiters, loader failure를 결정적 fixture로 검증한다.

## 최종 판정

P0=0, P1=0, P2=0, P3=0. 구현을 시작할 수 있다.
