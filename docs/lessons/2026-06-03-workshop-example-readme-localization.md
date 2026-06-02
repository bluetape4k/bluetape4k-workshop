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
세트로 보강했다.

## Outcome

- 92개 README 디렉터리 모두 `README.md` / `README.ko.md` 쌍을 갖게 됐다.
- 각 README 쌍에 언어 스위치와 시나리오, 아키텍처, 흐름, 시퀀스 섹션을 맞췄다.
- 91개 예제/섹션 README 디렉터리에 `*-readme-architecture-01.{dot,plain,svg,png}`
  Graphviz-backed overview diagram을 추가했다.
- 기존 상세 diagram은 유지하되, Architecture 섹션의 첫 이미지는 새 Graphviz
  overview PNG를 참조하도록 맞췄다.

## Verification

- README 디렉터리 스캔: `readme_dirs=92 missing_ko=0`.
- README 이미지 링크 스캔: `readmes=185 missing=0`.
- 필수 섹션/언어 스위치 스캔: `readme_dirs=92 failures=0`.
- Graphviz diagram 세트 스캔: `graphviz_bases=91 failures=0`.
- 새 SVG XML 검증: `xmllint --noout` 통과.
- README SVG 직접 참조 스캔: 0건.
- Visual QA: `.omx/artifacts/workshop-readme-graphviz-diagrams-contact-sheet.png`
  및 대표 PNG 원본 크기 확인.

## Future Note

새 예제 README를 추가할 때는 처음부터 `README.md`와 `README.ko.md`를 같이 만들고,
시나리오/아키텍처/흐름/시퀀스 섹션을 README 상단에 배치한다. 다이어그램 이미지는
가능하면 Graphviz source(`.dot`)와 layout evidence(`.plain`)를 함께 커밋하고, 새 자산을
만들 때는 링크 검증과 SVG XML 검증을 함께 실행한다.
