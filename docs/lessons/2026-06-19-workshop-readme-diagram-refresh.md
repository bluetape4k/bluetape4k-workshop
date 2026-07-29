# Workshop README diagram refresh

## 배경

workshop README refresh는 generated 또는 template-like README content를 source-backed
module explanation과 diagram으로 교체했다. 작업 범위는 Spring, AWS, Exposed, graph,
Vert.x, virtual-thread 및 관련 example module을 포함했다.

## 결정

- README image는 `docs/images/readme-diagrams/` 아래에 유지하고 obsolete Graphviz
  `.dot`, `.plain`, `*-graphviz.*` output은 제거한다.
- `Example Scenario`, `Sequence Diagram` 같은 generated heading보다 `Architecture`,
  `Request Flow`, `Module Guide`, domain-specific seed-data name 같은 reader-facing heading을
  선호한다.
- 변경된 모든 SVG를 PNG로 렌더링하고 commit 전에 PNG를 검사한다.
- `Architects Daughter` label, 절제된 arrow, 같은 색 arrowhead, card/text/line overlap을
  피할 만큼 충분한 image size를 가진 읽기 쉬운 card layout을 사용한다.
- hand-reviewed module document가 수정된 뒤 오래된 README template을 다시 도입할 수 있는
  generator는 유지하지 않는다.

## 결과

branch에는 이제 README와 diagram refresh를 위한 module-level commit이 있다. 남은
virtual-thread module은 usage rule, Spring MVC on Tomcat, Spring WebFlux dispatcher
comparison에 대한 source-backed diagram으로 완료했다. root/group README에서 residual
generated section name을 제거했고, legacy `README_KO.md`와 stale README generator를
삭제했다.

## 검증

- 변경된 SVG asset을 CairoSVG로 PNG로 렌더링했다.
- commit 전에 새로 렌더링한 diagram을 시각 검사했다.
- global README image link를 검증했다.
- 모든 `docs/images/readme-diagrams/*.svg` file이 `xmllint`로 parse되는지 확인했다.
- `docs/images/readme-diagrams`에 README-facing Graphviz artifact가 남지 않았음을 확인했다.
- historical lesson 밖에 stale template string이 더 이상 나타나지 않음을 확인했다.
- `git diff --check`를 실행했다.

## 다음 작업자 지침

향후 README diagram 작업에서는 SVG source inspection에만 의존하지 않는다. PNG를
렌더링하고 label overflow, card crowding, arrowhead size, connector/card contact,
line-label overlap, labeled layer 안의 unequal visual margin을 시각적으로 확인한다.
diagram이 여전히 PPT mockup처럼 보이면 label을 더 편집하기 전에 repository 안의 더 나은
기존 예제를 baseline으로 사용한다.
