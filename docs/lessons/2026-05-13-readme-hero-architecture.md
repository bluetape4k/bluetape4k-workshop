# README hero와 WIP 갱신

## Context

workshop 저장소에는 시각적인 root README 진입점과 할당된 GitHub issue에서
가져온 최신 WIP 스냅샷이 필요했다.

## Decision

생성한 workshop workbench 이미지를 `docs/assets/workshop-workbench.png`에
저장하고, 현재 issue queue를 기준으로 `WIP.md`를 갱신한다.

## Outcome

README는 이제 module map보다 먼저 저장소 목적을 보여 주고, WIP는 할당된
open issue 여섯 개를 표시한다.

## Verification

- 생성된 asset이 `docs/assets` 아래 PNG로 존재함을 확인했다.
- README가 공유 이미지 경로를 참조함을 확인했다.
