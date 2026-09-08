# CodeQL SVG 텍스트 정규화 교훈

## 배경

GitHub Code Scanning 경고 [#25](https://github.com/bluetape4k/bluetape4k-workshop/security/code-scanning/25)부터 [#28](https://github.com/bluetape4k/bluetape4k-workshop/security/code-scanning/28)까지는 SVG 텍스트를 처리하는 세 스크립트에서 발생했다. 순차 `replaceAll`은 `&amp;lt;`를 한 단계에서 `<`까지 디코딩했고, `<[^>]+>`와 `<!--[^]*?-->` 정규식은 닫히지 않은 태그나 주석을 남겼다.

## 결정

XML 엔터티는 치환 결과를 다시 검사하지 않는 단일 정규식 호출로 한 번만 디코딩한다. 태그와 주석은 다중 문자 제거 정규식 대신 입력을 한 번 순회하는 함수로 제거한다. 세 스크립트는 의존성 없는 공용 도우미를 사용하고, 이중 디코딩과 닫히지 않은 입력을 `node:test` 회귀 테스트로 고정한다.

## 결과와 검증

- `node --test scripts/graalvm-metadata-cache.test.mjs scripts/svg-text.test.mjs`: CI와 Nightly가 실행할 7개 테스트 통과
- `node --check`: 도우미, 테스트, 호출 스크립트 3개 통과
- `actionlint .github/workflows/ci.yml .github/workflows/nightly.yml`: 통과
- `node scripts/validate-sequence-diagrams.mjs`: 105개 검사, 실패 0
- 빈 격리 fixture에서 두 정규화 진입점 실행: 생성 0, 실패 0
- CodeQL 워크플로는 `schedule`과 `workflow_dispatch`만 지원하므로 실제 경고 종료 여부는 정확한 브랜치 디스패치와 `develop` 병합 후 다시 확인해야 한다.

## 놓친 점과 교정

`normalize-readme-diagram-fonts.mjs`를 검증기처럼 실행해 126개 SVG/PNG를 다시 생성했고, 이번 변경과 무관한 기존 폰트 불일치 36건도 함께 드러났다. 생성된 변경은 즉시 브랜치 기준 상태로 복원했다. 이름이 `normalize-*`인 스크립트는 상태를 변경할 수 있으므로 실제 저장소에서 검증 명령으로 실행하면 안 된다.

## 이후 작업 기준

- 엔터티 디코딩은 한 입력 단계에서 한 번만 수행하고, 치환된 문자열을 같은 단계에서 다시 디코딩하지 않는다.
- 닫는 구분자가 없는 태그와 주석도 나머지 입력을 제거하는 회귀 테스트를 둔다.
- 정규화 진입점의 실행 여부만 확인할 때는 빈 임시 fixture를 사용한다.
- 실제 에셋을 대상으로 정규화해야 한다면 실행 전후 변경 경로를 제한하고, 예상 밖 파일이 생기면 다음 검증으로 진행하지 않는다.
