# 2026-06-03 — Workshop example README localization

## Context

`bluetape4k-workshop` 예제 README는 모듈별 문서 품질이 일정하지 않았다.
일부 README에는 한국어판이 없었고, 시나리오/아키텍처/흐름/시퀀스 설명도
모듈마다 빠져 있었다.

## Decision

기존 상세 본문은 보존하고, README 앞부분에 공통 구조를 추가하는 방식으로
정리했다. 빌드 모듈뿐 아니라 소스 하위의 보조 예제 README까지 포함해
`README.md`와 `README.ko.md` 쌍을 맞췄다. 기존 README 이미지 중 Graphviz
근거가 없는 diagram은 공통 overview 용도의 Graphviz-backed PNG/SVG/DOT/PLAIN
세트로 보강했다. 새 Graphviz diagram 생성기는 `bluetape4k-diagram` skill의
규칙을 따라야 하므로 Graphviz output은 evidence로만 두고, README가 참조하는
최종 SVG/PNG는 Graphviz `.plain` 좌표를 바탕으로 별도 작성한다. 또한
`Architects Daughter`와 `Comic Mono` 발견 및 fallback 방지 게이트를 포함한다.

## Outcome

- 92개 README 디렉터리 모두 `README.md` / `README.ko.md` 쌍을 갖게 됐다.
- 각 README 쌍에 언어 스위치와 시나리오, 아키텍처, 흐름, 시퀀스 섹션을 맞췄다.
- 91개 예제/섹션 README 디렉터리에 `*-readme-architecture-01.{dot,plain,svg,png}`
  Graphviz-backed overview diagram을 추가했다.
- 기존 상세 diagram은 유지하되, Architecture 섹션의 첫 이미지는 새 Graphviz
  overview PNG를 참조하도록 맞췄다.
- Graphviz 렌더링 스크립트가 `Helvetica` fallback을 쓰지 않도록 fontconfig
  환경을 구성하고, node label은 `Architects Daughter`, edge label은 `Comic Mono`
  역할로 분리했다.
- `*-graphviz.svg/png`는 layout/routing evidence로 남기고, 최종 `*.svg/png`는
  hand-authored SVG generator가 만든 asset으로 분리했다.
- 이미지 내부 label은 영어/ASCII로 정규화했다.

## Verification

- README 디렉터리 스캔: `readme_dirs=92 missing_ko=0`.
- README 이미지 링크 스캔: `readmes=185 missing=0`.
- 필수 섹션/언어 스위치 스캔: `readme_dirs=92 failures=0`.
- Graphviz diagram 세트 스캔: `graphviz_bases=91 failures=0`.
- 새 Graphviz/final diagram 세트 스캔: `graphviz_bases=91 failures=0`.
- 최종 SVG font-family/signature 스캔:
  `final_svgs=91 bad_final_svg_signatures=0 architects=91 comic_mono=91`.
- 최종 SVG non-ASCII label 스캔: `final_svgs=91 non_ascii_label_svgs=0`.
- 새 SVG XML 검증: `xmllint --noout` 통과.
- README SVG 직접 참조 스캔: 0건.
- Visual QA: 91개 최종 PNG를 8개 batch contact sheet로 전수 확인했다.
  `.omx/artifacts/readme-diagram-font-fix-audit/batch-*-sheet.png`

## Future Note

새 예제 README를 추가할 때는 처음부터 `README.md`와 `README.ko.md`를 같이 만들고,
시나리오/아키텍처/흐름/시퀀스 섹션을 README 상단에 배치한다. 다이어그램 이미지는
가능하면 Graphviz source(`.dot`)와 layout evidence(`.plain`)를 함께 커밋하고, 새 자산을
만들 때는 링크 검증과 SVG XML 검증을 함께 실행한다.

이번 batch의 diagram 생성기는 README 디렉터리의 현재 `src/**/*.kt`, 테스트,
`build.gradle.kts`, 리소스를 evidence로 삼아 entry/API/service/domain/repository/runtime
관계를 구성한다. Graphviz는 layout/routing evidence로만 사용하고, 최종 SVG/PNG는
별도 generator가 작성한다.

Architecture 섹션을 보강할 때 첫 overview 이미지만 새 자산으로 바꾸면 기존 상세
architecture 이미지가 예전 font stack을 계속 노출할 수 있다. README 이미지 링크
검증은 섹션의 모든 architecture 이미지가 `Architects Daughter`/`Comic Mono` 기반
SVG/PNG 쌍을 참조하는지까지 확인해야 한다.

후속 전수 보정에서는 README가 실제로 참조하는 `readme-diagrams/*.png` 212개를
대상으로 SVG font-family 선언을 정규화하고 PNG를 다시 렌더링했다. 향후 diagram
검증은 전체 SVG 텍스트가 아니라 font-family 선언값만 검사해야 한다. 예를 들어
`Views.Internal`, `InterProcessMutex`, `refillIntervally` 같은 도메인 텍스트를
`Inter` font fallback으로 오탐하면 안 된다.

README에 남아 있는 Mermaid block은 GitHub 렌더러에 맡기지 말고, 현재 README 모델을
SVG/PNG diagram asset으로 변환한다. 이번 보정에서는 20개 README의 Mermaid 22개를
`docs/images/readme-diagrams/*-readme-{sequence,flow}-NN.{svg,png}`로 전환했다.
sequence diagram은 participant 좌우 여백, note, branch, return arrow, call label box를
명시적으로 렌더링하고, flow/graph diagram은 Graphviz `dot -Tplain` layout을 evidence로
사용해 노드 배치와 connector label 겹침을 줄였다. 검증은 README Mermaid 잔여 0건,
SVG XML 통과, PNG 짝 누락 0건, README image link missing 0건, README의 SVG 직접 참조
0건, font-family 선언 위반 0건, 22개 PNG 전수 contact sheet 확인으로 마무리했다.

영어 README에서 한국어 상세 본문을 "영어 README를 참고하라"는 요약 문구로
대체하면 안 된다. `README.md`와 `README.ko.md`는 같은 범위의 내용을 각 언어로
담아야 한다. 앞으로 README localization batch는 `README.md`의 한글 잔존을
검사하되, 표준 언어 스위치(`[한국어](README.ko.md) | English`)만 예외로 허용한다.
이번 보정에서는 `scripts/validate-readme-language.mjs`를 추가해 이 조건을
자동 검증했다.

요약 문구가 있던 파일만 고치면 충분하지 않다. 기존 README 쌍 중에서도
한국어판의 heading, code block, image link가 영어판보다 적은 파일이 있었다.
이번 후속 보정에서는 모든 `README.md` / `README.ko.md` 쌍을 대상으로
`scripts/validate-readme-parity.mjs`를 추가하고, language switch, heading level
sequence, code fence count, image target sequence, 금지 요약 문구를 전체 검증했다.
한국어 README도 코드 블록과 이미지 링크를 생략하지 않고 영어판과 같은 범위의
내용을 담아야 한다.

Sequence diagram의 call/return line과 label box가 겹치는 문제는 눈대중만으로
검수하지 않는다. 생성기에서 label 높이를 wrapped line 수에 맞게 계산하고,
label box 아래와 다음 call/return line 사이의 최소 간격을 12px 이상으로 둔다.
검증 스크립트는 16개 sequence SVG에서 이 간격 위반 0건, SVG XML 통과,
sequence SVG 내 한글 label 0건, `Architects Daughter`/`Comic Mono` font signature
통과를 확인했다. Contact sheet는 빠른 triage용으로만 쓰고, 이전에 문제가 됐거나
복잡한 대표 PNG는 원본 크기로 직접 확인한다.
