# README 다이어그램 이미지 검증

## 배경

bluetape4k-workshop의 README diagram을 공유 pastel infographic renderer로 갱신했다. 작업 범위는 현재 Mermaid block과 git history에서 복구한 기존 README diagram image link를 포함한다.

## 결정

README-facing artifact로 PNG를 사용하고, 재사용할 수 있도록 SVG source를 PNG 파일 옆에 둔다. diagram label은 영어 전용이다. `Diagram`, `Architecture`, `Sequence Diagram` 같은 일반 title은 module-specific English title로 교체한다. 비영어 텍스트가 사라진 sequence label은 의미 없는 generic label 대신 participating component로 대체한다.

## 결과

- rendered artifact 93개
- PNG 파일 94개
- SVG source file 94개
- 누락된 README image link 없음
- README 파일 안의 local SVG image embed 없음
- 남은 Mermaid code block 없음
- shape-check candidate 없음

## 검증

- `node /Users/debop/work/bluetape4k/.omx/scripts/refine-readme-diagrams.mjs .`
- README image link 및 Mermaid residue checker
- PNG/SVG shape checker
- visual contact sheet review: `/tmp/bluetape4k-workshop-diagram-review-samples.png`
- `git diff --check`

## 향후 지침

사용할 수 있으면 원본 Mermaid source에서 재생성하고, 이전에 교체된 block은 git history까지 확인한다. image size는 content-driven으로 유지하고, fake filler node를 피하며, SVG source를 보존하고, publish 전에 sample sheet를 점검한다.
