# Issue #317 - AWS CloudWatch 및 IMDS 관찰성 워크샵 사양

- 날짜: 2026-06-30
- 이슈: https://github.com/bluetape4k/bluetape4k-workshop/issues/317
- 작업 유형: A형 전체 기능
- 대상 저장소: `bluetape4k/bluetape4k-workshop`
- 대상 모듈: `aws/cloudwatch-imds-observability`
- Gradle 프로젝트: `:aws-cloudwatch-imds-observability`

## 문제

`bluetape4k-dependencies 1.3.1`은 추가된 `bluetape4k-aws` 행을 승격합니다.
Spring Boot CloudWatch, CloudWatch 로그, EC2 인스턴스 메타데이터 서비스 및
Micrometer 스냅샷 게시 도우미. 워크숍에서는 아직도 AWS를 가르치고 있습니다.
S3 지향 예제만 해당:

- `aws/s3-spring-cloud`
- `aws/storage-abstraction`

누락된 학습 경로는 운영 AWS 관찰 가능성: 애플리케이션이 어떻게 작동하는지
사용자 정의 CloudWatch 측정항목 게시, 구조화된 CloudWatch 로그 이벤트 전송,
로컬 Micrometer 결과 측정기를 기록하고 EC2 메타데이터를 명시적으로 읽습니다.
IMDS을 암시적 자격 증명 전략으로 바꾸지 않고.

## 현재 증거

- Issue #317은(는) 마일스톤 `1.3.1`에서 열려 있고 `debop`에 할당되었으며 레이블이 있습니다.
  `documentation`, `enhancement`, `difficulty:advanced`, `area:spring-boot`,
  그리고 `area:observability-performance`.
- `settings.gradle.kts`은 `aws/` 아래 하위 디렉터리를 자동 등록합니다. 새로운
  모듈 경로는 `:aws-cloudwatch-imds-observability`에 매핑됩니다.
- 루트 `build.gradle.kts`는 `platform(libs.bluetape4k.dependencies)`를 가져옵니다.
  하위 프로젝트이므로 예제에서는 bluetape4k 모듈 버전을 고정하면 안 됩니다.
- `gradle/libs.versions.toml`에는 이미 `bluetape4k-aws`, Micrometer이(가) 있습니다.
  Spring Boot 별칭. `cloudwatch`에 대한 AWS SDK v2 별칭이 부족합니다.
  `cloudwatchlogs` 및 `imds`.
- `bluetape4k-aws` 소스는 Spring Boot 통합API 워크숍을 노출합니다.
  다음을 섭취해야 합니다:
  - `io.bluetape4k.aws.spring.cloudwatch.CloudWatchOperations`
  - `io.bluetape4k.aws.spring.cloudwatch.CloudWatchLogsOperations`
  - `io.bluetape4k.aws.spring.cloudwatch.CloudWatchMeterPublishingOperations`
  - `io.bluetape4k.aws.spring.imds.ImdsOperations`
- `bluetape4k-aws` 레슨에서는 CloudWatch Micrometer 지원을 명시적으로 경고합니다.
  글로벌 `micrometer-registry-cloudwatch`이 아닌 수동 스냅샷 게시자입니다.
  교체하고 IMDS를 자동 자격 증명 경로로 사용해서는 안 됩니다.
- 현재 `aws/README.md`에서는 로컬 S3 예제만 문서화하므로 AWS README는
  루트 README 및 로케일 쌍을 업데이트해야 합니다.
- `Examples.yml` 및 `scripts/smoke-validate.sh`에는 CloudWatch 또는
  IMDS 아직 예가 없습니다.

## 제약

- 모듈은 bluetape4k 라이브러리가 아닌 소비자 워크샵입니다. 루트를 사용하세요
  `bluetape4k-dependencies`BOM만 가능합니다.
- 기본 테스트에는 AWS 자격 증명, EC2 런타임, LocalStack가 필요하지 않아야 합니다.
  실제 IMDS 액세스.
- IMDS 동작은 문서와 코드에서 명시적으로 선택되어야 합니다. 모델로 삼지 마세요.
  자격 증명 공급자 또는 시작 프로브.
- 이 예는 관찰 가능성 의도와 작은 경계를 가르쳐야 합니다.
  `bluetape4k-aws` 자동 구성을 재현하는 대신 읽기 가능한 코드
  테스트 스위트.
- README 작업은 `README.md` 및 `README.ko.md`의 두 가지 언어로 이루어져야 합니다.
- 다이어그램 작업은 `$bluetape4k-diagram`을 사용해야 하고, SVG 및 PNG 자산을 포함해야 하며, 통과해야 합니다.
  현재 다이어그램 체크리스트를 확인하고 전체 크기 육안 검사를 포함합니다.

## 목표

1. 실행 가능한 Spring Boot 예제로 `aws/cloudwatch-imds-observability`을 추가합니다.
2. `CloudWatchOperations`을 통해 사용자 정의 메트릭 게시를 보여줍니다.
3. `CloudWatchLogsOperations`를 통해 CloudWatch 로그 이벤트 게시를 보여줍니다.
4. `ImdsOperations`을 통해 명시적인 EC2 메타데이터 읽기를 보여줍니다.
5. 운영결과를 Micrometer timers/counters 기록하고 공표한다.
   `CloudWatchMeterPublishingOperations`을 통해 미터 스냅샷을 선택했습니다.
6. 실제 테스트 없이 스텁 또는 모의 작업을 사용하여 기본 테스트를 로컬로 유지합니다.
   AWS 또는 IMDS 네트워크 종속성.
7. 로컬 모드, 선택적 실제 AWS 모드 및 credential/metadata 학습
   이중 언어 README 파일 및 다이어그램의 경계.
8. 유효성 검사 및 예제 연기 적용 범위에 새 모듈을 등록합니다.

## 논골

- 기존 S3 예제를 교체하거나 리팩토링하지 마세요.
- 기본 테스트에서는 실제 CloudWatch 또는 IMDS 호출을 전달하지 마세요.
- `micrometer-registry-cloudwatch`를 추가하지 마세요.
- 정기적인 CloudWatch 내보내기를 위한 스케줄러를 구현하지 마세요.
- IMDS 보안 자격 증명 문서, 임시 자격 증명을 노출하지 마십시오.
  가치 또는 자동 자격 증명 전략.
- 구현 증거가 입증되지 않는 한 Testcontainers 또는 LocalStack을 추가하지 마세요.
  그것은 필요합니다. 예상되는 디자인에는 컨테이너가 필요하지 않습니다.

## 접근 옵션

### 옵션 A - Spring Boot 로컬 우선 소비자 모듈

게시된 리소스를 사용하는 Spring Boot MVC/Actuator 예제를 만듭니다.
`bluetape4k-aws` Spring은 애플리케이션 서비스를 통해 인터페이스합니다. 테스트 제공
작업의 로컬 스텁 구현 및 요청, 태그, 실패 확인
동작 및 IMDS 경계.

이익:

- 이슈 레이블과 기존 AWS/Spring 부팅 워크샵 모양과 일치합니다.
- 테스트를 빠르고 자격 증명 없이 유지합니다.
- 라이브러리 테스트를 복제하는 대신 소비자 대상 계약을 교육합니다.
- 학습자에게 README/diagram 흐름을 명확하게 만듭니다.

소송 비용:

- 결정론적 테스트를 위해서는 작은 로컬 가짜 어댑터가 필요합니다.
- 실제 AWS 왕복을 증명하지 않습니다. 문서에는 리얼 모드가 다음과 같이 명시되어 있어야 합니다.
  선택 과목.

### 옵션 B - Ktor 예

CloudWatch, 로그,
그리고 IMDS.

이익:

- 최신 Ktor 관련 표면을 표시합니다.
- `bluetape4k-aws`의 Ktor 플러그인 작업과 일치할 수 있습니다.

소송 비용:

- 문제에는 `area:spring-boot` 라벨이 붙어 있습니다.
- 기존 AWS 워크샵 예제는 Spring에 중점을 두고 있습니다.
- 독자의 관심을 웹 프레임워크 메커니즘과
  observability/IMDS 경계.

### 옵션 C - 실제 AWS 선택적 통합 모듈

실제 CloudWatch에 게시하고 IMDS을 쿼리할 수 있는 모듈을 구축하세요.
옵트인 프로필.

이익:

- 생산 배선을 직접 시연합니다.
- EC2에서 수동으로 확인하는 데 유용합니다.

소송 비용:

- CI 및 AWS 계정이 없는 학습자에게는 위험합니다.
- 실패 사례를 결정적으로 유지하기가 더 어렵습니다.
- 주의 깊게 격리하지 않으면 문제의 기본 테스트 경계를 위반합니다.

## 결정

옵션 A를 사용하세요. 워크샵 모듈은 Spring Boot 지역 우선 소비자가 될 것입니다.
예. 의도를 구현하는 간단한 엔드포인트와 서비스를 노출합니다.
눈에 보이지만 테스트에서는 조명을 통해 직접 서비스 레이어를 호출합니다.
인메모리 작업 구현이 포함된 스프링 컨텍스트입니다.

선택적 실제 AWS 모드는 수동 프로필로 문서화됩니다. 그렇지 않을 것이다
CI에서 실행되며 모듈의 DoD에는 필요하지 않습니다.

## 건축학

### 런타임 구성요소

- `ObservabilityApplication`: Spring Boot 진입점.
- `OrderTelemetryController`: 학습자 검사를 위한 작은 HTTP 정면.
- `OrderTelemetryService`: 시뮬레이션된 주문 처리, 측정항목,
  로그 및 메타데이터 조회.
- `OrderTelemetryRequest` / `OrderTelemetryReport`: 요청 및 응답 DTO.
- `TelemetryOutcome`: success/failure 결과 모델.
- `AwsObservabilityProperties`: 로컬 namespace/log group/log 스트림 및 IMDS
  워크숍에서 사용되는 옵트인 설정입니다.
- `LocalAwsObservabilityConfig`: 구현하는 로컬 프로필 Bean
  `CloudWatchOperations`, `CloudWatchLogsOperations`, `ImdsOperations` 및
  CloudWatch 실제 AWS가 없는 미터 게시자.
- `RealAwsObservabilityConfig`: 다음을 사용하는 선택적 프로필 경계
  `bluetape4k-aws` 사용자가 AWS SDK 서비스를 제공할 때 자동 구성
  종속성, 지역 및 자격 증명.

### 데이터 Flow

1. 사용자는 주문 결과에 대해 로컬 엔드포인트 또는 서비스 메서드를 호출합니다.
2. 서비스는 Micrometer timer/counter 상태를 기록합니다.
3. 이 서비스는 다음과 같은 안정적인 크기로 CloudWatch `MetricDatum`을 구축합니다.
   `Outcome`, `Service`, `Source`.
4. 서비스는 `CloudWatchOperations`를 통해 메트릭을 보냅니다.
5. 서비스는 정리된 이벤트로 CloudWatch 로그 `InputLogEvent`를 구축합니다.
   필드를 입력하고 `CloudWatchLogsOperations`을 통해 보냅니다.
6. 메타데이터 조회가 명시적으로 요청되면 서비스는 안전한 메타데이터를 읽습니다.
   `instanceId`, `region` 등의 `ImdsOperations` 도우미를 통해
   `availabilityZone`.
7. 서비스는 선택한 Micrometer 미터 스냅샷을 다음을 통해 게시합니다.
   `CloudWatchMeterPublishingOperations`.
8. 보고서는 전송된 내용과 메타데이터를 읽었는지 여부를 반환합니다.

### 실패 처리

- CloudWatch 지표 게시 실패는 실패한 보고서를 반환하고 로컬을 기록합니다.
  실패 카운터.
- CloudWatch 로그 게시 실패는 실패한 보고서를 반환하고 로컬을 기록합니다.
  실패 카운터.
- IMDS은 기본적으로 건너뜁니다. 요청 시 메타데이터 오류는 다음과 같이 캡처됩니다.
  자격 증명 문서를 노출하지 않고 보고서의 메타데이터 상태.
- `CancellationException`은 호출 일시 중단 시 삼켜지지 않습니다.
- 발신자 입력은 생산이 이루어지는 곳에서 bluetape4k 검증 도우미를 통해 검증됩니다.
  코드는 직접 호출자 값을 허용합니다.

## 테스트 전략

- TDD은 서비스 동작에 필수입니다. 테스트가 작성되고 실패 여부가 확인됩니다.
  생산 구현 전.
- Unit/service 테스트는 MockK 또는 결정론적 가짜 연산을 사용합니다.
  - 측정항목 데이텀 이름, 네임스페이스, 차원 및 값 매핑
  - 로그 그룹, 스트림, 이벤트 timestamp/message 매핑
  - Micrometer counter/timer 성공 및 실패 증가
  - CloudWatch 게시 실패 동작
  - CloudWatch 게시 실패 동작을 기록합니다.
  - IMDS 기본 건너뛰기 동작
  - 명시적 메타데이터 옵트인은 안전한 도우미 값만 읽습니다.
  - `/latest/meta-data/iam/security-credentials/{role}` 문서를 읽지 못했습니다.
- 스프링 컨텍스트 스모크 테스트는 로컬 프로필이 서비스와 연결되어 있는지 확인하고
  자격 증명이 없는 로컬 작업 Bean.
- Testcontainers 테스트는 계획되어 있지 않습니다. 모듈은
  컨테이너가 없는 관찰성 연기 경로.

## 문서 및 다이어그램

생성 또는 업데이트:

- `aws/cloudwatch-imds-observability/README.md`
- `aws/cloudwatch-imds-observability/README.ko.md`
- `aws/README.md`
- `aws/README.ko.md`
- 루트 `README.md`
- 루트 `README.ko.md`

`docs/images/readme-diagrams/` 아래에 다이어그램을 추가합니다.

- `aws-cloudwatch-imds-observability-readme-architecture-01.svg/png`
- `aws-cloudwatch-imds-observability-readme-sequence-01.svg/png`

다이어그램 요구 사항:

- 공유 위키의 공식 AWS CloudWatch 및 CloudWatch 로그 아이콘을 사용하세요.
  카드가 AWS 관리 서비스를 나타내는 경우 아이콘 카탈로그입니다.
- 정적 구성요소 다이어그램에 계층화된 아키텍처를 사용합니다.
- 시퀀스 다이어그램에는 현재 모범 사례 시퀀스 계열을 사용합니다.
- 메시지 라벨에는 번호가 매겨져 있고 표시되어야 합니다.
- `alt` 또는 `else` 영역 본체는 투명해야 하며 분기별 선이 있어야 합니다.
  성공, 실패 및 IMDS skip/read 분기의 색상입니다.
- SVG을 PNG로 렌더링하고 터치된 모든 PNG를 전체 크기로 검사합니다.

## CI 및 등록

- `settings.gradle.kts`은(는) 다음을 통해 새 모듈을 자동 등록해야 합니다.
  `includeModules("aws", false, true)`. `./gradlew projects`로 확인합니다.
- `.github/workflows/Examples.yml`에 경로 필터와 연기 작업 범위를 추가합니다.
- `:aws-cloudwatch-imds-observability:test` 추가
  `scripts/smoke-validate.sh observability` 또는 기타 비용기 흡연 그룹.
- 오래된 확인 예상 프로젝트 수를 업데이트합니다.
- 워크플로 편집 후 `actionlint`을 실행합니다.

## 수락 기준 매핑

| 이슈기준 | 디자인 반응 |
|---|---|
| 루트 BOM만 사용 | `bluetape4k-aws` 별칭을 버전 없이 유지하세요. bluetape4k 버전 핀 없이 AWS SDK 별칭을 추가합니다. |
| Adds/reuses 버전-카탈로그 별칭 | 모듈에 SDK 클래스가 직접 필요한 경우 `aws2-cloudwatch-lib`, `aws2-cloudwatchlogs-lib` 및 `aws2-imds-lib` 별칭을 추가합니다. |
| metric/log 게시 의도 테스트 | 서비스 테스트는 `MetricDatum` 및 `InputLogEvent` 값을 캡처합니다. |
| tag/field 매핑 테스트 | 어설션에는 지표 차원과 로그 이벤트 필드가 포함됩니다. |
| 실패 동작 테스트 | 실패 측정기를 기록하는 동안 모의 작업에서 실패 보고서를 생성하고 서비스를 반환합니다. |
| 명시적인 IMDS 경계를 테스트합니다 | 기본 경로는 IMDS을 건너뜁니다. 옵트인 경로는 안전한 도우미만 읽습니다. 자격 증명 문서 경로가 호출되지 않습니다. |
| README 문서 local/optional 실제 AWS/IMDS 경계 | 모듈 README 로캘 쌍에는 로컬 프로필, 선택적 실제 AWS 프로필 및 자격 증명 경고가 포함됩니다. |
| CI/default 연기 테스트는 실제가 아님 AWS | 테스트에는 로컬 프로필과 가짜 작업이 사용됩니다. 컨테이너나 실제 자격 증명이 없습니다. |

## 위험 및 완화

| 위험 | 완화 |
|---|---|
| 게시된 `bluetape4k-aws` 아티팩트가 형제 소스와 다름 | 이 저장소의 해결된 종속성에 대해 컴파일을 확인하고 공개 API만 사용하세요. |
| 예제 중복 `bluetape4k-aws` 라이브러리 테스트 | 소비자 조율과 학습자를 향한 행동에 계속 집중하세요. |
| IMDS이(가) 실수로 자격 증명 안내가 되었습니다 | IMDS 메타데이터가 자동 자격 증명 전략이 아니라는 점을 문서화하고 자격 증명 문서 엔드포인트를 피하세요. |
| 다이어그램 회귀 시퀀스 모범 사례 | 최신 모범 사례 참조에서 시작하여 시퀀스 스타일 감사와 전체 크기 PNG 검사를 실행하세요. |
| CI이(가) 건너뛴 새 모듈 | 워크플로 경로 필터, 연기 작업 목록, 필요한 경우 아티팩트 및 `stale-check` 프로젝트 수를 업데이트합니다. |
| 테스트가 느리거나 자격 증명에 민감해짐 | 로컬 가짜 작업과 직접 서비스 테스트를 사용합니다. 실제 AWS 모드는 수동으로만 유지하세요. |

## 검증 계획

구현 후 현지 검증:

- `./gradlew :aws-cloudwatch-imds-observability:compileKotlin --warning-mode all --console=plain`
- `./gradlew :aws-cloudwatch-imds-observability:compileTestKotlin --warning-mode all --console=plain`
- `./gradlew :aws-cloudwatch-imds-observability:test --warning-mode all --console=plain`
- `./gradlew projects --console=plain`
- `node scripts/validate-readme-language.mjs`
- `node scripts/validate-readme-parity.mjs`
- `node scripts/validate-readme-architecture-diagrams.mjs`
- `node scripts/validate-sequence-diagrams.mjs`
- `python3 /Users/debop/.codex/skills/bluetape4k-diagram/references/diagram-sequence-style-audit.py docs/images/readme-diagrams/aws-cloudwatch-imds-observability-readme-sequence-01.svg`
- `python3 /Users/debop/.codex/skills/bluetape4k-diagram/references/diagram-geometry-audit.py docs/images/readme-diagrams/aws-cloudwatch-imds-observability-readme-architecture-01.svg docs/images/readme-diagrams/aws-cloudwatch-imds-observability-readme-sequence-01.svg`
- `python3 /Users/debop/.codex/skills/bluetape4k-diagram/references/diagram-endpoint-audit.py docs/images/readme-diagrams/aws-cloudwatch-imds-observability-readme-architecture-01.svg docs/images/readme-diagrams/aws-cloudwatch-imds-observability-readme-sequence-01.svg`
- `python3 /Users/debop/.codex/skills/bluetape4k-diagram/references/diagram-connector-audit.py docs/images/readme-diagrams/aws-cloudwatch-imds-observability-readme-architecture-01.svg docs/images/readme-diagrams/aws-cloudwatch-imds-observability-readme-sequence-01.svg`
- `~/.local/bin/cairosvg <svg> -o <png> -s 2` 터치할 때마다 SVG
- 만지는 모든 부분의 전체 크기 육안 검사 PNG
- `actionlint .github/workflows/Examples.yml`
- `./scripts/smoke-validate.sh stale-check`
- `git diff --check`

검토 게이트:

- 2단계-R 사양 검토: P0=0, P1=0.
- 3단계-R 계획 검토: P0=0, P1=0.
- 6-R단계 구현 검토: P0=0, P1=0.
- 7-R단계 PR 검토: P0=0, P1=0.

## 공개 질문

차단 미결 질문이 남아 있지 않습니다. 디자인은 의도적으로 지역을 선택합니다.
Spring Boot 소비자 예시 및 문서 실제 AWS 수동 옵트인 실행.
