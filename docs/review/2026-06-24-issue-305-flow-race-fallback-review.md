# Issue 305 Self Review

## 발견 사항

implementation self-review에서 P0/P1 finding은 없다.

## 참고

- race test는 source lifecycle callback으로 loser cancellation을 assertion한다.
- merge test는 arrival order가 의도적으로 안정적이지 않으므로 source와 attribute set을
  assertion한다.
- materialize test는 원래 exception object를 보존한다.

## 잔여 위험

full repository test는 PR scope에 필요하지 않으며, CI가 cross-module failure를 드러내지 않는 한
계획하지 않는다.
