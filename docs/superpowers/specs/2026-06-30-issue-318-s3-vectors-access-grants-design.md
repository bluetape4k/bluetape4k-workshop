# Issue #318 - AWS S3 벡터 및 액세스 권한 부여 워크샵 사양

- 날짜: 2026-06-30
- 이슈: https://github.com/bluetape4k/bluetape4k-workshop/issues/318
- 작업 유형: A형 전체 기능
- 대상 저장소: `bluetape4k/bluetape4k-workshop`
- 대상 모듈: `aws/s3-vectors-access-grants`
- Gradle 프로젝트: `:aws-s3-vectors-access-grants`

## 문제

`bluetape4k-dependencies 1.3.1`은 추가된 `bluetape4k-aws` 행을 승격합니다.
선택적 S3 벡터 및 S3 액세스 권한 부여 통합. 현재 AWS 워크숍
이미 일반 S3 객체 스토리지, 사전 서명된 URL, 스토리지 프로필,
및 CloudWatch/IMDS 관찰 가능성이 있지만 두 가지 새로운 경계는 표시되지 않습니다.

- S3 벡터스는 평범하지 않은 벡터 검색 전용 서비스 화면입니다 S3
  객체 메타데이터에 임베딩이 숨겨진 객체 스토리지.
- S3 액세스 권한은 S3 제어를 사용하여 범위가 지정된 데이터 액세스를 요청하며 다음과 같아야 합니다.
  광범위한 S3 클라이언트 권한 및 정책 변경 작업과 분리됩니다.

워크숍에는 두 표면을 모두 설명하는 학습자 친화적인 예가 필요합니다.
기본 테스트에서는 실시간 AWS 리소스가 필요하지 않습니다.

## 현재 증거

- Issue #318은(는) 마일스톤 `1.3.1`에서 열려 있고 `debop`에 할당되었으며 레이블이 있습니다.
  `documentation`, `enhancement`, `difficulty:advanced`,
  `area:architecture-extension` 및 `area:storage`.
- `settings.gradle.kts`은 `aws/*` 디렉토리를 `:aws-*`로 자동 등록합니다.
  모듈이므로 `aws/s3-vectors-access-grants`은
  `:aws-s3-vectors-access-grants`.
- `gradle/libs.versions.toml`에는 현재 `aws2-s3-lib`이(가) 있고
  `aws2-s3-transfer-manager`, 그러나 `aws2-s3vectors-lib` 또는 없음
  `aws2-s3control-lib` 별칭.
- `bluetape4k-aws`은 `io.bluetape4k.aws.s3vectors.S3VectorsOperations`을 노출합니다.
  `listVectorBuckets`, `getVectorBucket`, `listIndexes`, `getIndex`,
  `putVectors`, `getVectors`, `listVectors`, `queryVectors`.
- `bluetape4k-aws` 노출
  `io.bluetape4k.aws.spring.s3.accessgrants.S3AccessGrantsOperations`와 함께
  `getDataAccess`, `listCallerAccessGrants`, `listAccessGrants`,
  `listAccessGrantsInstances` 및 `listAccessGrantsLocations`.
- `S3VectorsAutoConfiguration`은(는) 기본적으로 비활성화되어 있으며 선택 사항이 필요합니다.
  AWS SDK `software.amazon.awssdk:s3vectors` 서비스 종속성.
- `S3AccessGrantsAutoConfiguration`은(는) 기본적으로 비활성화되어 있으며
  선택적 AWS SDK `software.amazon.awssdk:s3control` 서비스 종속성.
- AWS SDK Java 2.x 문서용 S3 벡터
  `software.amazon.awssdk.services.s3vectors` client/model 패키지 및 액세스
  S3 제어 `getDataAccess` 및 목록 API를 통해 부여합니다.
- GNO 관련 병합 `bluetape4k-aws` PR이 표시되었습니다.
  - PR #291 `feat: add optional S3 Vectors support`
  - PR #290 `feat(aws-ktor): add optional S3 Access Grants integration`
- AWS 이슈 #317의 워크샵 교훈에서는 새로운 AWS 예시가 그대로 유지되어야 한다고 말합니다.
  로컬 우선, 실제 AWS 프로필을 명시적으로 유지하고 로컬 가짜를 구별합니다.
  다이어그램의 실제 AWS 관리 서비스의 어댑터.

## 제약

- 루트 `bluetape4k-dependencies` BOM만 사용하세요. bluetape4k를 고정하지 마세요
  모듈 버전.
- 기본 테스트에는 AWS 자격 증명, 실시간 S3 벡터, 액세스가 필요하지 않아야 합니다.
  설정, LocalStack, Floci 또는 에뮬레이터 지원을 부여합니다.
- 이 예는 S3 벡터가 일반적인 S3 객체 저장소임을 암시해서는 안 됩니다.
- 예시에서는 반환된 임시 자격 증명을 노출, 기록 또는 유지해서는 안 됩니다.
  액세스 권한 부여에서. 학습자는 부여 결정 및 권한 범위를 볼 수 있습니다.
  하지만 자격증 자료는 아닙니다.
- 액세스 권한 부여 관리 create/update/delete 작업이 종료되었습니다.
  범위; 워크숍에서는 공통 read/data-access 및 목록 경로만 사용합니다.
- README 작업은 `README.md` 및 `README.ko.md`의 이중 언어로 이루어집니다.
- 다이어그램 작업은 `$bluetape4k-diagram`을 사용해야 하고, SVG 및 PNG 자산을 포함해야 하며, 통과해야 합니다.
  현재 체크리스트를 확인하고 전체 크기의 육안 검사 증거를 기록합니다.
- 새 모듈 등록은 root/AWS README 테이블을 포함해야 합니다(예: CI/smoke).
  적용 범위, 오래된 확인 프로젝트 수 및 `./gradlew projects`.

## 목표

1. Spring Boot 로컬 우선 예시로 `aws/s3-vectors-access-grants`을 추가합니다.
2. 소규모 액세스 범위 문서 검색 및 유사성 검색 모델링
   학습자를 위한 시나리오.
3. 벡터 인덱스 검색, upsert 및 쿼리에 `S3VectorsOperations`을 사용하세요.
   의지.
4. 호출자 부여 목록 및 범위 지정에는 `S3AccessGrantsOperations`을 사용하세요.
   `getDataAccess` 인텐트.
5. 가짜 작업 구현을 통해 기본 테스트를 결정적으로 유지합니다.
6. 명확하게 구분된 일반 S3 객체 저장소, 미리 서명된 URL S3, S3 벡터,
   코드, README 텍스트 및 다이어그램의 액세스 권한 부여.
7. 해당 프로필을 실행하지 않고 선택적 real-AWS 프로필 전제 조건을 문서화합니다.
   in CI.
8. CI/smoke 커버리지에 모듈을 등록하세요.

## 논골

- `aws/storage-abstraction` 또는 `aws/s3-spring-cloud`을 바꾸지 마십시오.
- 실제 벡터 버킷, 벡터 인덱스, 액세스 권한 부여 인스턴스,
  보조금 또는 기본 테스트의 위치.
- S3 벡터 또는 액세스 권한에 대한 에뮬레이터 지원을 보장하지 마십시오.
- RAG 답변 생성 또는 LLM 통합을 구현하지 마세요.
- 파괴적이거나 정책을 변경하는 S3 제어 API를 워크숍에 포함시키지 마십시오.
  서비스.
- 보고서에 액세스 권한 부여 임시 자격 증명을 표시하거나 저장하지 마세요.

## 접근 옵션

### 옵션 A - Spring Boot 로컬 우선 소비자 모듈

게시된 bluetape4k 외관을 사용하는 Spring Boot 모듈을 생성합니다.
애플리케이션 서비스를 통한 인터페이스. 로컬 가짜 어댑터 캡처 요청
의도와 결정론적 응답을 반환합니다. README 및 다이어그램은 방법을 설명합니다.
실제 AWS로 수동으로 교체하세요.

이익:

- 현재 AWS 작업장 형태와 Spring Boot 발행 열차와 일치합니다.
- CI을 빠르고 자격 증명 없이 유지합니다.
- AWS 계정 프로비저닝이 아닌 애플리케이션 경계를 학습합니다.
- 다이어그램을 통해 명확한 로컬 가짜 대 AWS 관리 서비스 경계를 ​​표시할 수 있습니다.

소송 비용:

- 기본적으로 실시간 AWS 동작을 증명하지 않습니다. 문서에는 이를 명확하게 명시해야 합니다.
- 요청 형태를 학습하기에 충분한 충실도를 갖춘 가짜 어댑터가 필요합니다.

### 옵션 B - Ktor 예

S3 벡터 및 액세스 권한 부여를 위해 Ktor 플러그인과 경로 테스트를 사용하세요.

이익:

- 일부 `bluetape4k-aws` Ktor 액세스 권한 부여 작업과 일치합니다.
- Spring이 아닌 학습자에게 유용합니다.

소송 비용:

- 기존 AWS 워크샵 모듈은 Spring 지향적입니다.
- Ktor 메커니즘과 이미 새로운 AWS 표면 두 개를 혼합할 것입니다.
- 이슈 승인은 Ktor에만 국한되지 않습니다.

### 옵션 C - 실제 AWS 프로필 우선

IAM을 설명하는 문서를 사용하여 모듈을 주로 실제 AWS 통합 랩으로 만듭니다.
액세스 권한 부여, 벡터 버킷 설정 및 정리.

이익:

- 생산 설정에 가장 가깝습니다.
- 실제 AWS 서비스 동작을 수동으로 검증할 수 있습니다.

소송 비용:

- 기본 워크샵 테스트에는 너무 비싸고 부서지기 쉽습니다.
- 계정 수준 설정이 필요하며 혼란스러운 IAM 문제가
  첫 번째 학습 경로.

## 결정

옵션 A를 사용하세요. 모듈은 Spring Boot 로컬 우선 소비자 예제가 됩니다.
다음을 위한 간결한 애플리케이션 서비스와 HTTP 외관을 노출합니다.

- 검색 가능한 문서 벡터를 등록하고,
- 유사한 문서를 쿼리하고,
- 발신자 액세스 권한 확인,
- URI 문서에 대한 범위 지정 읽기 액세스를 요청합니다.
- 어떤 작업이 가짜 로컬인지 실제 AWS 선택인지 설명합니다.

선택적 real-AWS 프로필은 수동 확장으로만 문서화됩니다.
CI에서는 실행되지 않으며 DoD에는 필요하지 않습니다.

## 건축학

### 런타임 구성요소

- `S3VectorsAccessGrantsApplication`: Spring Boot 진입점.
- `DocumentSearchController`: 학습자를 향한 HTTP 종점.
- `DocumentSearchService`: 벡터 upsert/query 및 액세스 권한 부여를 조정합니다.
  체크 무늬.
- `DocumentVectorRequest`, `DocumentSearchRequest`,
  `DocumentSearchReport`, `AccessGrantReport`, `VectorSearchMatch`: DTO 및
  보고서 모델.
- `DocumentAccessPolicy`: 안전한 워크숍 문서 ID를 허용된 S3 URI에 매핑합니다.
  그리고 권한을 요청했습니다.
- `AwsS3VectorsAccessProperties`: 네임스페이스, bucket/index 이름, 계정 ID,
  액세스 권한은 위치 ARN, 로컬 모드 플래그 및 최대 입력 크기입니다.
- `LocalS3VectorsAccessConfig`: 결정적 로컬 가짜 Bean
  `S3VectorsOperations` 및 `S3AccessGrantsOperations`.
- `RealAwsS3VectorsAccessConfig`: 다음을 사용하는 선택적 profile/property 경계
  bluetape4k AWS 사용자가 자격 증명, 지역을 추가하면 자동 구성
  벡터 bucket/index 및 액세스 권한 설정.

### 데이터 Flow

1. 학습자는 문서 메타데이터로 작은 문서 벡터를 삽입합니다.
2. 이 서비스는 입력 크기와 안정적인 문서 식별자의 유효성을 검사합니다.
3. 서비스는 `PutVectorsRequest`부터 `S3VectorsOperations`까지 빌드됩니다.
4. 학습자가 임베딩 벡터를 사용하여 쿼리합니다.
5. 서비스는 `QueryVectorsRequest`을 빌드하고 반환된 일치 항목을
   안전합니다 `DocumentSearchReport`.
6. 학습자는 문서 검색을 위해 검색 일치 항목을 선택합니다.
7. 서비스는 선택한 일치 항목이 허용된 문서에 매핑되는지 확인합니다.
   URI 데이터 액세스를 요청하기 전에.
8. 서비스는 `ListCallerAccessGrantsRequest`을 빌드하고, 허용되는 경우
   `GetDataAccessRequest`과 `Permission.READ`.
9. 보고서에는 대상 URI, 권한, 부여 상태 및 수정된 액세스가 표시됩니다.
   상태이지만 임시 자격 증명 값은 아닙니다.

### 실패 처리

- S3 벡터 실패는 응답에서 벡터 작동 상태가 됩니다.
- 액세스 권한 부여 실패는 응답에서 권한 부여 작업 상태가 됩니다.
- 부분 실패는 명시적입니다. 벡터 쿼리 성공은 액세스를 의미하지 않습니다.
  인증 성공.
- `CancellationException`은(는) 이전에 서비스 중지 메소드에서 다시 발생합니다.
  광범위한 예외 처리.
- 발신자 입력은 bluetape4k 검증 도우미를 통해 검증됩니다.
- `GetDataAccessResponse`의 자격 증명 필드는 DTO에 노출되지 않습니다.
  로그, 다이어그램 또는 README 예시.

## 테스트 전략

- TDD 레드 테스트는 제품 구현에 앞서 진행됩니다.
- 서비스 테스트는 결정론적 가짜 작업을 사용합니다.
  - upsert는 예상 벡터 bucket/index 및 벡터 키를 빌드합니다.
  - 쿼리 빌드가 `QueryVectorsRequest`으로 예상되고 맵이 일치합니다.
  - 선택된 일치 항목 검색은 성공적인 액세스 권한 부여 결정에 의해 제어됩니다.
  - 부여 목록은 예상 계정 및 S3 접두사 범위를 구축합니다.
  - `getDataAccess`은 `Permission.READ`을 빌드하고 수정된 상태를 반환합니다.
  - 액세스 거부는 범위가 지정된 데이터 액세스 요청을 방지합니다.
  - S3 벡터 실패는 AWS 내부를 노출하지 않습니다.
  - 액세스 권한 부여 실패는 자격 증명 자료를 노출하지 않습니다.
  - 취소가 다시 발생합니다.
- 컨트롤러 테스트는 JSON 모양, 검증 실패, 로컬 프로필 배선,
  자격 증명 필드 유출이 없고 캡처된 로그에 자격 증명 자료가 없습니다.
- 스프링 컨텍스트 연기 테스트는 AWS 없이 로컬 가짜 Bean 와이어를 확인합니다.
  신임장.
- Testcontainers 테스트는 계획되어 있지 않습니다.

## 문서 및 다이어그램

생성 또는 업데이트:

- `aws/s3-vectors-access-grants/README.md`
- `aws/s3-vectors-access-grants/README.ko.md`
- `aws/README.md`
- `aws/README.ko.md`
- 루트 `README.md`
- 루트 `README.ko.md`

`docs/images/readme-diagrams/` 아래에 다이어그램을 추가합니다.

- `aws-s3-vectors-access-grants-readme-architecture-01.svg/png`
- `aws-s3-vectors-access-grants-readme-sequence-01.svg/png`

다이어그램 요구 사항:

- S3와 같은 실제 AWS 관리 서비스 카드에만 공식 AWS 아이콘을 사용하세요.
  벡터, S3 제어 및 S3.
- 로컬 가짜 작업 Bean을 텍스트로만 유지하거나 AWS 서비스가 아닌 로컬로 명확하게 유지하세요.
  브랜드.
- 임시 액세스 키, 비밀 키, 세션 토큰, 자격 증명 JSON을 표시하지 않습니다.
  또는 다이어그램 레이블, README 예시 또는 스크린샷의 자격 증명 필드 이름입니다.
- 아키텍처 다이어그램은 다음과 같은 레이어와 커넥터 의미 체계를 표시해야 합니다.
  solid/dashed 행이 다른 경우 범례.
- 시퀀스 다이어그램은 현재 모범 사례 스타일을 사용해야 합니다. 참가자
  헤더, 생명줄, 활성화 표시줄, 호출 회선 위의 번호가 매겨진 알약 라벨,
  투명한 `alt`/`else` 몸체, 지점별 음소거 색상 및 일치
  line/arrowhead 색상.

## 수락 기준

- 루트 BOM만; 명시적인 bluetape4k 버전이 없습니다.
- `aws2-s3vectors-lib` 및 `aws2-s3control-lib` 별칭이 로컬 없이 추가됨
  AWS SDK 버전 드리프트.
- `:aws-s3-vectors-access-grants`이 `./gradlew projects`에 나타납니다.
- 기본 테스트는 자격 증명, 컨테이너 또는 라이브 AWS 없이 통과됩니다.
- README 쌍은 전제 조건, 경계, 로컬 가짜 모드, 선택 사항을 설명합니다.
  real-AWS 모드이며 기존 S3 예제와의 차이점입니다.
- 다이어그램 자산은 `$bluetape4k-diagram` 체크리스트 및 전체 크기 PNG 시각적 개체를 통과합니다.
  점검.
- CI/smoke 작업 흐름 범위에는 새 모듈이 포함됩니다.

## 외부 참조

- AWS SDK(Java 2.x S3 벡터용 API:
  https://sdk.amazonaws.com/java/api/latest/software/amazon/awssdk/services/s3vectors/S3VectorsClient.html
- AWS SDK for Java 2.x S3 벡터 쿼리 모델:
  https://sdk.amazonaws.com/java/api/latest/software/amazon/awssdk/services/s3vectors/model/QueryVectorsRequest.html
- AWS SDK Java 2.x S3 제어 API용:
  https://sdk.amazonaws.com/java/api/latest/software/amazon/awssdk/services/s3control/S3ControlClient.html
- Java 2.x 액세스 권한 데이터 액세스 모델의 경우 AWS SDK:
  https://sdk.amazonaws.com/java/api/latest/software/amazon/awssdk/services/s3control/model/GetDataAccessRequest.html
