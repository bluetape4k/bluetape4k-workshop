# README Mermaid SVG 인포그래픽

## Context

README 파일은 live Mermaid diagram을 사용했다. 문서 표현에는 안정적인 pastel
SVG infographic asset이 필요했고, sequence diagram은 Mermaid source로 편집할
수 있어야 했다.

## Decision

sequence가 아닌 모든 README Mermaid block을 `docs/images/readme-diagrams/`
아래 SVG로 render하고, 해당 block만 relative image link로 교체한다.

## Outcome

sequence가 아닌 diagram에 대해 check-in된 SVG asset을 생성했다.
`sequenceDiagram` block은 Mermaid code block으로 남긴다.

## Verification

Mermaid CLI 11.14.0으로 SVG asset을 render했고, SVG link/file count를
검증했으며, 남은 non-sequence README Mermaid block이 0개임을 확인하고
`git diff --check`를 실행했다.

## Future Guidance

먼저 render하고, 모든 SVG가 존재한 뒤에만 README link를 확정한다.
repository-wide documentation rewrite에서는 worktree와 build output을 제외한다.
