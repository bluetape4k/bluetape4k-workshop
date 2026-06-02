# 2026-06-03 — Workshop example README localization

## Context

`bluetape4k-workshop` 예제 README는 모듈별 문서 품질이 일정하지 않았다.
일부 README에는 한국어판이 없었고, 시나리오/아키텍처/흐름/시퀀스 설명도
모듈마다 빠져 있었다.

## Decision

기존 상세 본문과 이미지 자산은 보존하고, README 앞부분에 공통 구조를
추가하는 방식으로 정리했다. 빌드 모듈뿐 아니라 소스 하위의 보조 예제
README까지 포함해 `README.md`와 `README.ko.md` 쌍을 맞췄다.

## Outcome

- 92개 README 디렉터리 모두 `README.md` / `README.ko.md` 쌍을 갖게 됐다.
- 각 README 쌍에 언어 스위치와 시나리오, 아키텍처, 흐름, 시퀀스 섹션을 맞췄다.
- 기존 `docs/images/readme-diagrams` 이미지는 재사용하고, 없는 경우에는 소스
  테스트와 예제 코드가 실행 가능한 시퀀스의 기준임을 명시했다.

## Verification

- README 디렉터리 스캔: `readme_dirs=92 missing_ko=0`.
- README 이미지 링크 스캔: `readmes=185 missing=0`.
- 필수 섹션/언어 스위치 스캔: `readme_dirs=92 failures=0`.

## Future Note

새 예제 README를 추가할 때는 처음부터 `README.md`와 `README.ko.md`를 같이 만들고,
시나리오/아키텍처/흐름/시퀀스 섹션을 README 상단에 배치한다. 다이어그램 이미지는
가능하면 `docs/images/readme-diagrams`의 기존 자산을 재사용하고, 새 자산을 만들 때는
링크 검증을 함께 실행한다.
