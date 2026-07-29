# Issue #104 - 다중 테넌트 데이터 격리 구현 계획

**날짜**: 2026-05-24
**문제**: https://github.com/bluetape4k/bluetape4k-workshop/issues/104
**사양**: `docs/superpowers/specs/2026-05-24-issue-104-multi-tenant-data-isolation-design.md`
**상태**: 초안

## 작업

- [x] `spring-boot/multi-tenant-data-isolation/build.gradle.kts`을 추가합니다.
- [x] Spring Boot 애플리케이션 진입점 및 H2/Exposed 구성을 추가합니다.
- [x] `Serializable` 및 `serialVersionUID`과 함께 `TenantId` 및 `InvoiceRecord` 도메인 유형을 추가합니다.
- [x] `InvoiceTable` 및 스키마 초기화 프로그램을 추가합니다.
- [x] `TenantInvoiceRepository : LongJdbcRepository<InvoiceRecord>`을 추가합니다.
- [x] `UnsafeInvoiceRepository` 기본 구현을 추가합니다.
- [x] `TenantKeyFactory`, 테넌트 키 캐시, 키별 `ReentrantLock` 잠금 레지스트리, 고정 창 인메모리 속도 제한기 및 Micrometer 카운터 서비스를 추가합니다.
- [x] 기준 누출 및 테넌트 안전 동작을 입증하는 테스트를 추가합니다.
- [x] `src/test/resources/junit-platform.properties`을 추가합니다.
- [x] `src/test/resources/logback-test.xml`을 추가합니다.
- [x] `README.md` 및 `README.ko.md`을 추가합니다.
- [x] `./gradlew :spring-boot-multi-tenant-data-isolation:test`를 확인합니다.
- [x] `git diff --check`를 확인합니다.
- [x] 새로운 공개 API 및 README/README.ko.md 잠금 단계에 대해 영어 KDoc을 감사합니다.
- [x] `docs/lessons/` 아래에 간결한 강의를 추가합니다.
- [x] 최종 diff에서 코드 review/advisor 게이트를 실행합니다.

## 구현 노트

- `spring-boot/` 아래의 모듈 경로는 `settings.gradle.kts`에 `withProjectName=false` 및 `withBaseDir=true`와 함께 `spring-boot`을 포함하므로 프로젝트 이름 `:spring-boot-multi-tenant-data-isolation`을 생성합니다.
- 공개 API KDoc은 영어여야 합니다.
- 새로운 테스트에서는 JUnit 5와 `bluetape4k-assertions`을 사용합니다. AssertJ/JUnit 검증 API이 없습니다.
- 데이터 클래스는 `Serializable`을 구현하고 `serialVersionUID`을 정의합니다.
- 테넌트 키 도우미는 의도적으로 명시적이므로 README는 원시 키와 테넌트 접두사가 붙은 키를 대조할 수 있습니다.
- README 클레임은 실제 클래스와 종속성만 참조해야 합니다.
- 종속성은 기존 별칭인 `exposed-jdbc`, `bluetape4k-spring-boot4-core`, `bluetape4k-micrometer`, `micrometer-core`, `h2-v2`, Spring Boot JDBC/test 스타터, `bluetape4k-junit5` 및 `bluetape4k-assertions`에 고정됩니다.
- 속도 제한 및 잠금 예제는 `TenantKeyFactory` 키가 있는 인메모리 상태를 사용합니다. 새로운 Bucket4j 또는 분산 잠금 종속성은 도입되지 않습니다.

## 확인

```bash
./gradlew :spring-boot-multi-tenant-data-isolation:test
git diff --check
```

## 리뷰 노트

- Claude 어드바이저 게이트 1: `.omx/artifacts/claude-issue-104-design-20260524152918.md`
- P0/P1 게이트 1의 결과: 이 초안에서 수정되었습니다.
- Claude 설계 재실행: `.omx/artifacts/claude-issue-104-design-rerun-20260524153255.md`, PASS, P0=0, P1=0.
- Claude 코드 검토: `.omx/artifacts/claude-issue-104-code-review-final-20260524154933.md`, PASS, P0=0, P1=0.
