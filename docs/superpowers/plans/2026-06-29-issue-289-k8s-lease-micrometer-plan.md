# Issue #289 - Kubernetes 임대 Micrometer 워크숍 계획

**날짜**: 2026-06-29
**문제**: https://github.com/bluetape4k/bluetape4k-workshop/issues/289
**사양**: `docs/superpowers/specs/2026-06-29-issue-289-k8s-lease-micrometer-design.md`
**모듈**: `leader/k8s-lease-micrometer` -> `:leader-k8s-lease-micrometer`
**상태**: 사용자가 승인함

## T1 - 빌드 및 카탈로그

- `bluetape4k-leader-k8s`에 대한 버전 없는 별칭을 추가합니다.
  `bluetape4k-leader-micrometer` 및 `fabric8-kubernetes-client`.
- 액추에이터가 포함된 Spring Boot 4개 모듈 빌드, Micrometer 코어, Prometheus 추가
  레지스트리, Fabric8 클라이언트, bluetape4k 로깅, 코루틴 및 결정적
  종속성을 테스트합니다.
- `bluetape4k-leader` BOM 또는 명시적인 bluetape4k 버전을 추가하지 마세요.

**DoD**: 종속성 해결이 성공하고 모듈이 다음과 같이 나열됩니다.
`./gradlew projects`.

## T2 - 먼저 테스트

- ID, 네임스페이스, 임대 이름 등에 대한 속성 유효성 검사 테스트를 추가합니다.
  기간 주문.
- 미터 잠금 names/tags에 `SimpleMeterRegistry`을 사용하여 메트릭 테스트를 추가합니다.
- 선택, 건너뛰기 및 건너뛰기 작업에 대해 가짜 코디네이터를 사용하여 보호된 작업 테스트를 추가합니다.
  실패한 실행 경로.

**DoD**: 프로덕션 클래스가 없기 때문에 처음에는 테스트가 실패했다가 통과했습니다.
구현 후.

## T3 - 구현

- `K8sLeaseMicrometerProperties`을 구현합니다.
- `LeaderCoordinator` 구현, 기본 비활성화 코디네이터 및 옵트인
  Kubernetes 코디네이터.
- `K8sLeaseMetrics` 및 `K8sLeaseGuardedTask`을 구현합니다.
- `@ConditionalOnProperty`으로 Spring 구성을 구현하여 기본 테스트를 수행합니다.
  Kubernetes은 피하세요.

**DoD**: 실제 Kubernetes 클러스터 없이 대상 모듈 테스트를 통과했습니다.

## T4 - 문서 및 다이어그램

- 언어 스위치를 사용하여 `README.md` 및 `README.ko.md`을 추가합니다.
- 위에서 아래로 아키텍처 및 시퀀스 다이어그램을 SVG+PNG로 추가합니다.
- 문서 kind/RBAC 매니페스트, 명령 실행, 예상되는 리더 전달 동작,
  측정항목 names/tags, 기본 로컬 동작 및 생산 경계.
- 루트 README/README.ko 모듈 카탈로그 및 스모크 유효성 검사기 수를 업데이트합니다.

**DoD**: README 패리티 및 언어 유효성 검사기가 통과되었습니다. 이미지 링크가 해결됩니다.

## T5 - 검증, 검토, PR, CI

- 타겟 테스트 실행, 경고 컴파일, 프로젝트 목록, README 유효성 검사기,
  다이어그램 자산 검사 및 `git diff --check`.
- 구현이 완료된 경우 검토 통과를 수행하고 짧은 강의를 녹화합니다.
  워크플로우는 내구성 있는 지침을 보여줍니다.
- Lore 예고편으로 커밋하고, 푸시하고, 이슈 메타데이터가 미러링된 PR을 생성하고,
  라이브 PR 메타데이터 및 CI을 확인합니다.

**DoD**: PR에는 담당자 `debop`, 마일스톤 `1.2.0`, 이슈 라벨이 미러링됨,
`## DoD Status`으로 끝나는 본문과 CI의 증거가 수집됩니다.
