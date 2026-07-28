# 2026-05-20 — README 개요 시각 자료 배치

## 배경

README 다이어그램과 차트는 장식용 생성 자산이 아니라, 소스에 근거한
문서로 다루어야 한다. 이번 작업은 2026년 기준 문서와 공통 README
다이어그램 스타일 가이드를 사용했지만, 모듈 이름과 그룹화의 최종
근거는 여전히 소스 코드와 빌드 레이아웃으로 두었다.

## 결정

루트 README에 영어 전용 SVG+PNG 개요 시각 자료를 추가하고, 개요
다이어그램을 설치, 사용법, 빌드 지침보다 앞에 배치한다. 기존
Architecture/Diagram 섹션이 사용 예시 뒤에 붙어 있던 경우에는 더 위로
옮긴다.

## 결과

`bluetape4k-workshop`에는 이제 루트 README 개요 다이어그램과 모듈 구성
차트가 있으며, README 시각 자료 배치는 개요 우선 규칙을 따른다. 생성된
레이블은 이미지 내부에 현지화된 텍스트를 넣지 않는다.

## 검증

- 생성된 SVG 파일을 `xmllint --noout`로 파싱했다.
- 생성된 PNG 파일을 `rsvg-convert`로 렌더링했다.
- 워크스페이스 README 이미지 링크 스캔에서 누락된 로컬 이미지는 0건이었다.
- 워크스페이스 Architecture/Diagram 순서 스캔에서 Installation, Usage,
  Examples, Build heading 뒤에 남아 있는 섹션은 0건이었다.
- 생성된 루트 개요 SVG 텍스트에는 비 ASCII 문자가 없었다.

## 향후 참고

아키텍처 다이어그램을 README 끝에 덧붙이지 않는다. 개요 또는 아키텍처
다이어그램은 상단 가까이에 두고, class, sequence, ERD, flow 다이어그램은
설명 대상 섹션 옆에 둔다.

루트 개요 다이어그램과 구성 차트는 BOM이 있으면 먼저 배치하고, Examples
또는 Additional examples가 있으면 마지막에 둔다. 중간 그룹은 repo별
README가 알파벳 순서를 요구하지 않는 한 소스 기반 방향 순서를 유지한다.
