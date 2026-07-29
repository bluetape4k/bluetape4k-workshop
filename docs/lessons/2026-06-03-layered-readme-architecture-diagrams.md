# Layered README architecture diagrams

## 배경

README diagram standardization이 generic horizontal flow diagram과 중복
Architecture/Flow section으로 흐트러졌다. rendered PNG는 기술적으로 생성되어 있었지만,
여러 asset이 예제의 layered structure를 설명하지 못했고 일부 README pair는 English와
Korean 사이에 drift가 있었다.

## 결정

graph 예제를 README architecture diagram의 visual baseline으로 사용한다. 기준은 layered
band, component-level source-derived card, visible orthogonal connector,
`Architects Daughter` / `Comic Mono` font role이다. Graphviz `.dot`, `.plain`, sketch
asset은 evidence로 유지하지만, commit되는 README SVG/PNG는 raw Graphviz horizontal
pipeline이나 one-card-per-layer summary 대신 layered component layout으로 렌더링한다.

## 결과

- 87개 generic README architecture SVG/PNG asset을 layered component-card 구조로
  재생성했다.
- same-layer connector가 충분한 route space를 갖도록 architecture canvas를 1320px로
  넓혔다.
- AWS, Exposed, messaging, graph, observability, Redis, rate limit, Spring Data,
  Spring Boot, security, virtual-thread 예제에 domain-specific layer와 card label을
  추가했다.
- controller, service, repository, runtime item을 broad summary card로 집계하지 않고,
  generated architecture node를 source-derived component card로 분리했다.
- 긴 CamelCase component name을 wrap해 class/test name이 card boundary 안에 머물게 했다.
- visible architecture edge label을 제거했다. semantic label은 SVG `data-label` metadata로
  유지한다.
- 기존 `graph/` architecture asset 4개를 복원하고 generic generator/validator에서
  제외했다. 해당 module-specific diagram이 graph 예제를 더 잘 설명하기 때문이다.
- README 파일에서 generated Flow section을 제거했다.
- 중복 Architecture section과 중복 image target을 제거했다.
- `README.md`와 `README.ko.md` 사이 parity를 유지했다.

## 검증

- `node scripts/validate-readme-language.mjs`
- `node scripts/validate-readme-parity.mjs`
- `node scripts/validate-sequence-diagrams.mjs`
- `node scripts/validate-readme-architecture-diagrams.mjs` for 87 generated architecture SVGs,
  excluding the four preserved `graph/` assets by explicit filename
- architecture asset gate: 91 SVGs, missing pairs 0, bad font families 0
- architecture route gate: orthogonal paths, boundary endpoints, endpoint angles, and non-endpoint
  node-interior/clearance checks for all 87 generated SVGs
- README image link gate: duplicate targets 0, SVG links 0, missing files 0
- `xmllint --noout` for all `*-readme-architecture-01.svg`
- `git diff --check`
- visual contact sheets:
  `.omx/diagram-review/readme-architecture-contact-sheet-domain-specific.png`,
  `.omx/diagram-review/readme-architecture-contact-sheet-componentized.png`
- individual PNG checks: `aws`, `messaging/kafka`, `messaging/kafka-reply`,
  `observability/micrometer-tracing-coroutines`
- individual PNG rechecks after route fix: `exposed-mvc-jdbc`, `aws`, `messaging/kafka`

## 향후 규칙

domain-specific flow asset이 없는 한 generic README Flow section을 추가하지 않는다.
Architecture diagram은 기본적으로 layered structure를 사용해야 하며, rendered PNG 형태로
시각 확인해야 한다. 특히 same-layer connector label이 있을 때는 필수다.

반복되는 connector complaint는 즉시 generator validation으로 승격해야 한다.
same-layer card pair에는 충분한 canvas width, 충분한 horizontal gap, center가 정렬될 때의
side-to-side routing, connector path 위에 visible edge-label box가 없는 상태가 필요하다.
generic generator output보다 이미 설명력이 좋은 module-specific architecture asset을
덮어쓰지 않는다.

source file이 있을 때 README architecture diagram을 "API & Adapters" 또는
"Service & Domain" 같은 broad summary node로 collapse하지 않는다. generated architecture
asset은 concrete controller, handler, service, repository, DTO/model, framework helper,
bluetape4k module, runtime backend를 별도 component card로 보여주어야 한다. component
card 추가로 connector clutter가 생기면 component를 collapse하기 전에 connector 수를 줄인다.
