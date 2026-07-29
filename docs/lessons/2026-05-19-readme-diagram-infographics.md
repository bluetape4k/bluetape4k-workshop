# README 다이어그램 인포그래픽

## Context

README 파일은 architecture, class, sequence, ERD와 그 밖의 다이어그램에
Mermaid code block을 사용했다. workspace 전체의 시각 방향은 재사용 가능한
SVG source asset을 함께 보관하는, 검토된 pastel infographic PNG로 바뀌었다.

## Decision

README Mermaid block을 생성된 PNG image link로 교체하고, 대응하는 SVG source를
PNG 파일 옆에 저장한다. 다이어그램 문구는 English-only로 유지하고, 큰 label에는
Architects Daughter, detail text에는 Comic Mono를 사용하며, architecture,
class, sequence, ERD 다이어그램마다 전용 layout을 둔다.

## Outcome

README 다이어그램은 bluetape4k.github.io/docs/readme-diagram-samples의 공유
2026-05-19 style guide에 맞춰 render했다. root README asset은 해당 규칙이
있을 때 repo-local asset placement rule을 따른다.

## Verification

cross-repository conversion pass에서 rsvg-convert로 PNG/SVG asset을 생성하고
README link를 확인했다.

## Future Guidance

README 다이어그램은 편집용 SVG source를 함께 둔 PNG embed로 유지한다. 시각적
일관성이 중요할 때 raw Mermaid나 단순 Mermaid theme recoloring으로 되돌아가지
않는다.
