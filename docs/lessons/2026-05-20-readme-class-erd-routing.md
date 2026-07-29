# README Class/ERD 라우팅

## 배경

문서, blog post, presentation에서 재사용할 수 있도록 bluetape4k workspace 전반의 README class 및 ERD image를 재생성했다.

## 결정

class 및 ERD diagram에는 blocker-aware lane selection을 포함한 직교 connector routing을 사용한다. pastel color와 기존 typography는 유지하되, cubic curve와 component 내부를 가로지르는 connector path는 피한다.

## 결과

재생성된 class/ERD SVG는 relation-aware component placement, 직선 horizontal/vertical lane, 더 작은 arrow marker, vertical first/final segment가 있는 top/bottom port를 사용하며, horizontal lane을 component edge가 아니라 row midline 근처에 배치한다.

## 검증

- `node --check .omx/scripts/refine-readme-diagrams.mjs`
- 변경된 class/ERD SVG: cubic connector count `0`
- 변경된 class/ERD SVG: card-interior crossing candidates `0`

## 향후 지침

다이어그램을 재생성할 때는 blocker-aware route scoring을 보존하고, 광범위한 image churn을 받아들이기 전에 contact sheet를 점검한다.
