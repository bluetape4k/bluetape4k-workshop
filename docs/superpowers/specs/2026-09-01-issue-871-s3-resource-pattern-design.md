# Issue #871 S3 Resource pattern resolver 소비자 예제 설계

- Issue: [#871](https://github.com/bluetape4k/bluetape4k-workshop/issues/871)
- Branch: `feat/issue-871-s3-resource-pattern-resolver`
- 대상 모듈: `aws/s3-spring-cloud`
- 대상 독자: Spring Cloud AWS S3를 사용하는 Spring Boot 개발자
- 문서 언어: 한국어 (코드·API 이름·명령은 원문 유지)

## 목표와 문제

현재 예제는 `ResourceLoader.getResource("s3://bucket/key")`로 하나의 S3
객체만 읽는다. `bluetape4k-aws 2.0.0-SNAPSHOT`에는 같은 S3 client를 재사용하는
`S3ResourcePatternResolver`가 추가되어 literal bucket의 prefix 아래에서
`*`, `?`, `**` 패턴으로 객체를 찾을 수 있다. 기존 예제에 이 resolver를
주입하고 Floci fixture로 exact 조회와 wildcard 조회를 함께 실행해, 라이브 AWS
credential 없이 신규 소비자 계약을 학습할 수 있게 한다.

기존 Spring Cloud AWS `S3Template` 업로드·AWS SDK 목록 조회·exact
`ResourceLoader` 흐름은 유지한다. `bluetape4k.aws.s3.enabled=false`로 전체
bluetape4k S3 자동 구성을 끄던 로컬 설정은 제거하고, 사용자 소유 `S3Client`가
자동 구성의 client 조건을 만족하게 한다. 그러면 자동 구성된 protocol resolver와
`s3ResourcePatternResolver` bean이 같은 client provider를 사용하며, Spring Cloud
AWS의 `S3Template`과도 역할이 겹치지 않는다.

## 근거 ledger

| 근거 | 확인한 계약 | 적용 결정 |
| --- | --- | --- |
| [Issue #871](https://github.com/bluetape4k/bluetape4k-workshop/issues/871) | exact/pattern fixture, pagination, deterministic ordering, empty match, metadata·stream lifecycle, unsupported boundary와 등록 파일 요구 | 기존 `aws/s3-spring-cloud` 안에서 소비자 예제와 Floci 통합 테스트를 확장 |
| [bluetape4k-aws Issue #463](https://github.com/bluetape4k/bluetape4k-aws/issues/463) | Spring `Resource` exact protocol과 single-bucket pattern resolver 요구 | protocol 경로와 pattern 경로를 테스트에서 각각 관찰 |
| [bluetape4k-aws PR #538](https://github.com/bluetape4k/bluetape4k-aws/pull/538) | `S3ResourcePatternResolver`, strict URI parser, 모든 `ListObjectsV2` paginator page 소비, 중복 제거·정렬, Floci 우선 | upstream API를 재구현하지 않고 qualifier bean을 직접 주입해 실제 소비자 사용법을 문서화 |
| upstream `S3ResourceAutoConfiguration` | `bluetape4k.aws.s3.enabled=true`(기본값), `S3Client` 존재, 기본 bean 이름 `s3ResourcePatternResolver` | 기존 disabled override를 삭제하고 수동 `S3Client`를 보존 |
| 현재 `SpringCloudAwsS3Sample`·`SpringCloudAwsS3Test` | Floci endpoint와 `S3Template`이 이미 local-first로 구성됨 | 별도 credential·client·container를 만들지 않고 기존 fixture를 재사용 |
| `gradle/libs.versions.toml` | `bluetape4k-dependencies`가 `2.0.0-SNAPSHOT`을 관리하며 module alias는 versionless | 새 bluetape4k 버전 pin이나 개별 BOM을 추가하지 않음 |

## 범위

### 포함

- `SpringCloudAwsS3Sample`에 `@Qualifier("s3ResourcePatternResolver")`
  `ResourcePatternResolver`를 주입하고 `s3://<bucket>/config/**/*.yml` 예제를
  실행한다.
- exact object와 nested wildcard object를 함께 생성하고 읽는다. 샘플의
  `ApplicationRunner`는 관찰 가능한 목록과 패턴 결과를 로그로 남긴다.
- `SpringCloudAwsS3Test`에 Floci 기반 multiple-object fixture를 추가한다.
  fixture는 서로 다른 prefix와 이름 순서를 사용해 resolver의 prefix 요청,
  모든 page 소비, deterministic sorted 결과, empty match, metadata와 stream
  close를 검증한다.
- unsupported cross-bucket/root/empty-prefix/write/output-stream 경계를
  README 두 언어로 명시한다. 실제 AWS credential과 real AWS endpoint는
  기본 테스트 경로에서 사용하지 않는다.
- `aws/s3-spring-cloud/README.md`, `README.ko.md`, `docs/coverage-matrix.md`,
  `scripts/smoke-validate.sh`, `docs/ecosystem-reuse-train.json`, lesson과
  review artifact를 같은 contract로 갱신한다.

### 제외

- upstream `S3ResourcePatternResolver`나 URI parser를 consumer 모듈에 복제하지
  않는다.
- cross-bucket glob, wildcard bucket, prefix 없는 root listing, wildcard write,
  `WritableResource` output stream은 지원하지 않는다.
- S3 Vectors, Access Grants, CRT transfer manager의 API나 dependency를 이
  example에 추가하지 않는다.
- retry/cache/executor/client lifecycle을 새로 소유하지 않는다. resolver가
  반환한 stream은 호출자가 닫고, client와 application context는 Spring이
  관리한다.

## 선택지와 권고

### A — 자동 구성 resolver를 qualifier로 주입 (권고)

`S3Client`는 현재처럼 샘플이 생성하고, `S3ResourceAutoConfiguration`이 제공하는
`s3ResourcePatternResolver`를 `ResourcePatternResolver` 타입으로 주입한다.
exact 조회는 기존 `ResourceLoader`를 유지하고, wildcard 조회만 pattern resolver
경로로 분리한다. upstream의 parser·paginator·diagnostic 계약을 그대로 소비하므로
코드가 짧고 버전 upgrade의 학습 효과가 분명하다.

### B — consumer에서 resolver를 직접 생성

`ApplicationContext`와 `ObjectProvider<S3Client>`를 예제 코드가 직접 조립하면
자동 구성의 bean 조건과 주입법을 보여 주지 못하고 client ownership 경계를
혼동한다. 따라서 선택하지 않는다.

### C — Spring Cloud AWS `ResourceLoader`만 사용

현재 exact 사례는 유지되지만 `getResources`의 wildcard 기능을 검증하지 못한다.
Issue #871의 신규 기능 소비자 목표를 충족하지 못하므로 선택하지 않는다.

## 구조와 데이터 흐름

```text
SpringCloudAwsS3Sample
  ├─ S3Client bean (Floci endpoint, sample-owned)
  ├─ S3Template (Spring Cloud AWS upload)
  ├─ ResourceLoader
  │    └─ S3ProtocolResolver → exact s3://bucket/key → S3Resource
  └─ @Qualifier("s3ResourcePatternResolver") ResourcePatternResolver
       └─ ListObjectsV2 paginator → prefix filter → wildcard match
            → deduplicate + String.compareTo sort → S3Resource[]
```

pattern fixture의 객체 키는 다음처럼 고정한다.

| key | `config/**/*.yml` | 목적 |
| --- | --- | --- |
| `config/z.yml` | 포함 | 정렬 순서의 뒤쪽 경계 |
| `config/application.yml` | 포함 | exact와 wildcard 공통 fixture |
| `config/nested/application.yml` | 포함 | `**` 재귀 match |
| `config/readme.txt` | 제외 | suffix filter와 empty match 기준 |
| `other/application.yml` | 제외 | 요청 prefix가 bucket 전체로 확장되지 않음 |

실제 S3 응답의 page 경계를 Floci 버전에 의존해 추측하지 않는다. 통합 테스트는
Floci에 여러 fixture를 저장하고 AWS SDK paginator가 반환하는 결과를 resolver에
전달하는 경계를 검증하며, deterministic ordering·empty match는 관찰 결과로
고정한다. resolver의 모든 page 순회 자체는 upstream PR #538의 계약과 consumer
통합 테스트에서 추적한다.

## 테스트 설계

TDD 순서로 먼저 다음 실패 테스트를 작성하고, 각 테스트가 기능 부재 때문에
실패하는지 확인한 뒤 최소 구현을 추가한다.

1. **자동 구성·exact 회귀**: test context에 `S3Client`, `ResourceLoader`,
   qualifier pattern resolver가 함께 생성되는지 확인하고, exact resource가
   기존처럼 존재하며 본문을 읽는지 검증한다.
2. **wildcard 및 prefix**: Floci에 위 fixture를 저장한 뒤
   `getResources("s3://$bucket/config/**/*.yml")` 결과 key가
   `config/application.yml`, `config/nested/application.yml`, `config/z.yml`
   순서인지 확인한다. `ListObjectsV2` 요청의 bucket과 `config/` prefix는
   AWS SDK client spy/fixture 관찰로 고정한다.
3. **empty match와 metadata/stream lifecycle**: `config/**/*.json` 결과가
   빈 배열이고 object GET/HEAD가 발생하지 않는지 확인한다. 반환 resource의
   `exists`, `contentLength`, `lastModified`를 관찰하고 `inputStream`을
   `use` 블록에서 읽어 caller가 stream을 닫는 예제를 고정한다.
4. **parser boundary**: wildcard bucket, `s3://bucket/*.yml`,
   cross-bucket 형태는 client 조회 전에 `IllegalArgumentException`을 내는지
   확인한다. `ResourcePatternResolver`는 읽기 전용이므로 write/output stream을
   지원한다고 표현하지 않고 README에도 명시한다.
5. **기존 회귀**: 기존 `stores lists and reads objects...` 테스트를 유지하고
   module test가 Floci cleanup 이후에도 반복 실행 가능한지 확인한다.

실제 AWS 호출과 credential은 사용하지 않는다. fixture가 만든 object만 테스트가
정리하고 Floci singleton 자체는 중지하지 않는다. resolver의 stream/client/context
수명은 consumer가 소유하지 않으며, test는 stream을 `use`로 닫는다.

## 실패 모드와 대응

| 실패 모드 | 관찰 가능한 계약 | 대응 |
| --- | --- | --- |
| `bluetape4k.aws.s3.enabled=false` 유지 | pattern resolver bean과 exact protocol resolver가 함께 사라짐 | 설정 override를 제거하고 `S3Client` 수동 bean이 auto-config 조건을 만족하는지 context test로 고정 |
| wildcard 결과가 page 순서에 종속됨 | 키 순서가 실행마다 달라질 수 있음 | resolver의 sorted 결과를 assert하고 prefix 밖의 key를 포함하지 않음 |
| match가 없음 | `null`이나 예외 대신 빈 배열이어야 함 | empty suffix fixture를 별도 assert하고 per-key HEAD/GET이 없음을 검증 |
| object가 존재하지 않음 | `Resource.exists()`가 false, listing 자체는 실패하지 않음 | metadata/stream assert는 실제 fixture에만 적용하고 exact missing은 upstream 계약으로 추적 |
| stream을 닫지 않음 | HTTP connection/resource가 남을 수 있음 | README와 테스트에서 `inputStream.use { ... }`를 필수 사용법으로 제시 |
| unsupported pattern 입력 | client 호출 전에 parser가 거부해야 함 | boundary test에서 provider/client 호출 횟수를 0으로 고정 |
| real AWS credential이 환경에 있음 | 기본 예제가 외부 AWS에 접근할 위험 | Floci endpoint와 synthetic credentials를 테스트 dynamic properties로 강제하고 real AWS 경로를 문서에서 제외 |

## 수용 기준과 DoD

- [ ] `bluetape4k-dependencies`가 관리하는 `2.0.0-SNAPSHOT`을 그대로 사용하고
  개별 Bluetape 버전 pin/BOM을 추가하지 않는다.
- [ ] 기존 exact `ResourceLoader` 예제와 신규 qualifier pattern resolver
  예제가 같은 Floci `S3Client`로 실행된다.
- [ ] multiple-object fixture가 `config/` prefix, `**` nested match, deterministic
  sort, empty match, metadata와 caller-owned stream close를 검증한다.
- [ ] unsupported cross-bucket/root/empty-prefix/write/output-stream 경계가
  두 README에 코드 예제와 함께 명시된다.
- [ ] module test/build, `scripts/smoke-validate.sh aws`, stale-check,
  `./gradlew projects`, README link/locale parity, `git diff --check`가 통과한다.
- [ ] validation matrix, smoke/stale 등록, ecosystem reuse train, lesson과
  review artifact가 Issue #871 및 동일한 명령·fixture·제약을 추적한다.
- [ ] PR body에 fresh test/CI 증거와 `## DoD Status`를 기록하고, exact-head
  CI 확인 후 새 `승인`을 받은 다음에만 merge한다. merge 후 `develop` 동기화와
  해당 feature worktree/branch만 정리한다.

## SPW·한국어 품질 게이트

| 게이트 | 이번 산출물 적용 |
| --- | --- |
| SPW-01 목적·독자 | 상단 metadata와 목표 절에 기록 |
| SPW-02 근거·범위 | 근거 ledger와 포함/제외 절에 기록 |
| SPW-03 실행 가능성 | 구조, fixture, 테스트 순서와 실패 대응을 구체화 |
| SPW-04 검증 가능성 | 명령과 수용 기준을 명시 |
| SPW-05 추적성 | Issue #871, upstream Issue #463/PR #538, README·matrix·lesson을 연결 |

최종 문서 검토에서는 KO-01(문장 자연스러움), KO-02(용어 일관성),
KO-03(영문 API 보존), KO-04(표현 과잉 금지), KO-05(명령 정확성),
KO-06(독자 행동의 명확성), KO-07(영문 README와 의미 동등성)을 확인하고
`audit-korean-terms.mjs` 결과를 lesson/review artifact에 남긴다.
