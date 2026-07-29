# 모듈 누락 README 수정

## 배경

여러 예제 모듈에 소스 코드는 있었지만 모듈 수준 `README.md`와 `README.ko.md`가 없었다.
요청 범위는 gateway customer/order 서비스, Spring Modulith event deep dive,
그리고 Spring Security 예제 3개를 포함했다.

## 결정

소스로 검증된 동작만 인용하는 간결한 이중 언어 README 파일을 추가하고, 이에 맞는
인포그래픽 스타일 SVG와 렌더링된 PNG 자산을 `docs/images/readme-diagrams/` 아래에 둔다.

## 결과

새 README는 모듈 목적, 아키텍처 이미지, 실행 가능한 Gradle task, 주요 endpoint,
그리고 source map을 문서화한다. 다이어그램은 보이는 텍스트를 영어로 유지하고,
큰 label에는 `Architects Daughter`를 사용하며, 1378x526 PNG로 렌더링된다.

## 검증

- `ctx_batch_execute`로 source evidence를 점검했다: controller mapping, security configuration,
  application YAML, Spring Modulith event package, sibling README pattern.
- link/asset validator가 README 파일 12개, SVG 파일 6개, PNG 파일 6개에 대해 통과했다.
- 변경된 README와 다이어그램 파일에 대해 `git diff --check`가 통과했다.
- 문서화된 모듈 경로 6개 모두에서 Gradle help 검증이 통과했다.

## 향후 지침

module-missing-readme 지적을 수정할 때는 두 localized README 상단 가까이에 architecture image를 만들고,
모든 상대 이미지 링크를 repo root가 아니라 README 위치 기준으로 검증한다.
