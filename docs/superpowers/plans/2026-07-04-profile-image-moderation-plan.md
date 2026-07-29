# 프로필 이미지 조정 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**목표:** 업로드 → 개인 저장 → 흐릿한 프로필 이미지 보류 → 비동기 조정 → 승인된 공개 이미지 또는 기본 대체를 보여주는 새로운 `:image-processing-profile-image-moderation` 워크숍 모듈을 구축합니다.

**아키텍처:** 모듈은 `image-processing/profile-image-moderation` 아래의 독립형 Spring Boot MVC 예제입니다. 기본적으로 로컬 결정적 구성 요소인 `ImageStorage` 로컬 백엔드, CI 안전을 위한 ImageIO 기반 JPEG 파생 생성, 인메모리 저장소 및 제한된 코루틴 조정 실행기를 사용합니다. 공개 URL 확인은 private/original 키를 명시적으로 거부하고 보류, 승인 및 기본 URL만 노출합니다.

**기술 스택:** Kotlin 2.4, Java 21, Spring Boot 4 MVC, bluetape4k core/coroutines/logging/assertions/junit5/images-spring-boot, Micrometer, ImageIO, Gradle 다중 모듈 등록.

---

## 파일 구조

만들다:

- `image-processing/profile-image-moderation/build.gradle.kts` — 모듈 종속성 및 Spring Boot 메인 클래스.
- `image-processing/profile-image-moderation/src/main/kotlin/io/bluetape4k/workshop/imageprocessing/profile/ProfileImageModerationApplication.kt` — 애플리케이션 진입점.
- `.../config/ProfileImageModerationProperties.kt` — 검증된 구성 기본값입니다.
- `.../model/ProfileImageModels.kt` — `ProfileImageStatus`, `ModerationDecision`, DTO, `Serializable` 데이터 클래스.
- `.../service/ProfileImageKeyFactory.kt` — 사용자 ID 확인, 파일 이름 삭제, `ImageObjectKey` 생성.
- `.../service/ProfileImageUrlResolver.kt` — 공개 URL 확인자; 개인 키와 안전하지 않은 기본 URL을 거부합니다.
- `.../service/UploadImageValidator.kt` — 바이트, MIME, 매직 바이트, 차원 가드.
- `.../service/ProfileImageProcessor.kt` — ImageIO 차원 읽기, 보류 중인 흐림 및 승인된 JPEG 파생 바이트.
- `.../service/ProfileImageRepository.kt` — `userId + uploadId` 비교 및 ​​설정이 완료된 인메모리 저장소.
- `.../service/ImageModerationProvider.kt` — 구성 가능한 지연 및 파일 이름 표시가 있는 가짜 조정 공급자.
- `.../service/ProfileImageModerationRunner.kt` — 제한된 애플리케이션 범위 코루틴 실행기.
- `.../service/ProfileImageMetrics.kt` — 낮은 카디널리티 Micrometer names/tags 및 도우미 메서드.
- `.../service/ProfileImageService.kt` — 업로드 조정, 저장, 측정항목, 정리, 응답 조합.
- `.../web/PublicProfileImageController.kt` — pending/default/approved 경로, 개인 접두사 거부 및 보류 중인 캐시 헤더를 제공하는 로컬 공용 객체입니다.
- `.../web/ProfileImageController.kt` — `POST` 및 `GET` API.
- `.../web/ProfileImageExceptionHandler.kt` — 안정적인 ProblemDetail 매핑.
- `image-processing/profile-image-moderation/src/main/resources/application.yml`.
- `image-processing/profile-image-moderation/src/test/resources/junit-platform.properties`.
- `image-processing/profile-image-moderation/src/test/resources/logback-test.xml`.
- Unit/controller은 일치하는 패키지에서 테스트합니다.
- `image-processing/profile-image-moderation/README.md` 및 `README.ko.md`.

수정하다:

- `README.md`, `README.ko.md` — 고급 예제 행 및 명령을 추가합니다.
- `AGENTS.md` — 누락된 경우 `image-processing/` 모듈 그룹을 추가합니다.
- `scripts/smoke-validate.sh` — `all-smoke`에 새 모듈을 포함합니다.
- `.github/workflows/Examples.yml` — 경로 필터, 테스트 명령 및 아티팩트 경로를 추가합니다.
- `docs/coverage-matrix.md` — 저장소 검증 매트릭스에 새 모듈을 추가합니다.

## 작업 1: 모듈 뼈대 및 RED 테스트

**파일:** 모듈, 리소스, 초기 테스트를 만듭니다.

- [ ] 모듈 디렉터리를 만듭니다.
- [ ] 명시적 종속성을 갖는 `build.gradle.kts` 생성: `bluetape4k.core`, `bluetape4k.coroutines`, `bluetape4k.idgenerators`, `bluetape4k.logging`, `bluetape4k.micrometer`, `bluetape4k.images`, `bluetape4k.images.spring.boot`, `kotlinx.coroutines.core.lib`, `micrometer.core`, Spring Boot autoconfigure/configuration processor/actuator/validation/webmvc, `bluetape4k.assertions`, `bluetape4k.junit5`, `kotlinx.coroutines.test.lib`, `spring.boot.starter.webmvc.test` 및 `spring.boot.starter.test`(Mockito/JUnit 빈티지 제외 포함)
- [ ] 다음에 대해 `ProfileImageServiceTest.kt`에 실패한 서비스 테스트를 작성합니다.
  - 보류 중인 업로드는 검토가 완료되기 전에 `PENDING_MODERATION` 및 `uploadId`을 반환합니다.
  - 승인된 완료 스위치 유효 URL;
  - 거부된 완료는 기본값 URL으로 전환됩니다.
  - 이전 업로드의 오래된 완료는 무시됩니다.
  - timeout/failure은 `MODERATION_FAILED`이 됩니다.
- [ ] `./gradlew :image-processing-profile-image-moderation:test --tests '*ProfileImageServiceTest'`를 실행하세요.
- [ ] 예상: Gradle가 자동 등록된 모듈을 인식한 후 해결되지 않은 새 service/model 클래스의 FAIL; `project not found`은(는) 허용되는 RED 결과가 아닙니다.

## 작업 2: 핵심 model/config/key/url 구현

**파일:** `ProfileImageModels.kt`, `ProfileImageModerationProperties.kt`, `ProfileImageKeyFactory.kt`, `ProfileImageUrlResolver.kt`.

- [ ] DTO/state/value/config 데이터 클래스를 포함하여 `serialVersionUID`가 있는 모든 새로운 Kotlin `data class`에 `Serializable`를 추가합니다. 적절하지 않은 경우에는 `data class`을 피하세요.
- [ ] `ProfileImageStatus.NO_IMAGE`, `PENDING_MODERATION`, `APPROVED`, `REJECTED`, `MODERATION_FAILED`를 추가합니다.
- [ ] `[A-Za-z0-9._-]{1,80}`을 사용하여 `userId`을 검증하고 잘못된 값을 거부합니다.
- [ ] `Base58.randomString(16)`을 사용하여 높은 엔트로피 업로드 ID를 생성합니다.
- [ ] URL 확인자에서 개인 키를 거부합니다.
- [ ] 잘못된 concurrency/timeouts/base URL 및 `allow-local-storage-remote-public-base-url=false` 동작에 대한 구성 테스트를 추가합니다.
- [ ] 발신자 입력 확인을 위해 bluetape4k `require*` 도우미를 사용하세요. 사용자 입력에는 `check`을 사용하지 마세요. 잘못된 userId/base URL/content 제약 조건이 안정적인 ProblemDetail에 매핑되는지 테스트합니다.
- [ ] 대상 config/key/url 테스트를 실행합니다.

## 작업 3: 유효성 검사기, 프로세서, 저장소 및 중재 실행기

**파일:** 서비스 패키지.

- [ ] 업로드 유효성 검사 구현: JPEG/PNG/WebP 허용 목록, 매직 바이트, 최대 바이트, 크기, 최대 픽셀.
- [ ] ImageIO JPEG 파생 생성 구현: 크게 축소된 흐릿한 보류 이미지 및 승인된 파생물. 콘텐츠 type/signature가 `.jpg` URL 확장자와 일치하고 EXIF/GPS 메타데이터가 전파되지 않는다고 검증문합니다.
- [ ] 비교 및 설정 `completeModeration(userId, uploadId, decision)`을 사용하여 인메모리 저장소를 구현합니다.
- [ ] 지연 및 파일 이름 표시를 사용하여 가짜 조정 공급자를 구현합니다.
- [ ] 제한된 `ProfileImageModerationRunner` 구현; `GlobalScope` 없음, `CancellationException` 다시 던지기, 테스트에서 제어 가능한 가짜 provider/channel 사용, 미해결 작업을 취소하고 허가를 해제하는 `@PreDestroy` 또는 `SmartLifecycle` 종료를 추가합니다.
- [ ] 조정 동시성 제한, 처리 시간 초과, 동시성 게이트 요청, 오래된 완료, 종료 취소 및 실제 1초 절전 모드 없음에 대한 결정적 테스트를 추가합니다. 스트레스 스타일 동시성을 추가하는 경우에만 `SuspendedJobTester`을 사용하세요. 그렇지 않으면 결정론적 순서 이론적 근거를 기록합니다.
- [ ] 서비스 테스트 및 저장소 테스트를 실행합니다.
- [ ] 스토리지 실패 테스트 추가: original-written-then-pending/approved 업로드 실패, 정리 시도, 정리 실패 logged/metriced, 부분적으로 유효한 이미지가 게시되지 않았습니다.

## 작업 4: 스토리지 오케스트레이션, 컨트롤러 및 오류

**파일:** `ProfileImageService.kt`, 웹 패키지.

- [ ] `ImageStorage`을 통해 원본 개인 키, 보류 중인 흐릿한 키 및 승인된 키를 저장합니다.
- [ ] 조정이 완료되기 전에 업로드하려면 HTTP `202 Accepted`를 반환하세요.
- [ ] no-image/pending/approved/rejected/failed에 대해 `GET /api/users/{userId}/profile-image`을 구현합니다.
- [ ] 유효하지 않은 입력을 400으로 매핑하고, 크기가 큰 업로드를 413(해당하는 경우)으로 매핑하고, storage/unexpected 안정적인 실패 ProblemDetail를 매핑합니다.
- [ ] pending/default/approved 가져오기를 위해 `PublicProfileImageController` 또는 이에 상응하는 리소스 핸들러를 구현합니다.
- [ ] 보류 중인 업로드, approved/rejected/failed 조회, 업로드 없음, 잘못된 업로드, 공개 pending/default/approved 가져오기, 보류 중인 `Cache-Control: no-store`, 비공개 `/public-images/**/private/**` 404 및 비공개 URL 확인자 거부에 대한 컨트롤러 테스트를 추가합니다.
- [ ] 컨트롤러 테스트를 실행합니다.

## 작업 5: 문서 및 등록

**파일:** README 쌍, 루트 README 쌍, AGENTS, 연기 스크립트, 예제 워크플로.

- [ ] 모듈 README.md는 영어로, README.ko.md는 한국어로 소스와 동등한 내용으로 작성합니다.
- [ ] 시나리오, 상태 전환, 컬 예제, pending/approved/rejected/failed/no-upload/invalid JSON, 폴링 타이밍, ProblemDetail 필드, 개인 정보 보호 경고, 가짜 대 Rekognition 어댑터 메모, health/metrics 검사, 로컬 정리, 기본 이미지 프로비저닝 및 S3/CDN 개인 접두사 경고를 포함합니다.
- [ ] 저장소 README 다이어그램 자산 경로 아래에 `profile-image-moderation-readme-architecture-01.{png,svg}` 및 `profile-image-moderation-readme-sequence-01.{png,svg}`라는 이름의 생성된 PNG/SVG README 다이어그램을 추가한 후 README 링크의 유효성을 검사하십시오.
- [ ] 루트 README.md 및 README.ko.md 고급 예제 테이블 및 명령 목록을 업데이트합니다. 이중 언어 섹션 순서, 언어 전환, 링크 및 JSON 필드 패리티를 확인합니다.
- [ ] 없는 경우 `image-processing/`을 포함하도록 AGENTS 모듈 테이블을 업데이트합니다.
- [ ] 자동 등록이 불충분한 경우에만 `settings.gradle.kts`을 업데이트하세요. 그렇지 않으면 `includeModules("image-processing", false, true)`이 이미 모듈을 다루고 있다고 기록하세요.
- [ ] `scripts/smoke-validate.sh all-smoke`를 업데이트하세요.
- [ ] 새 모듈에 대한 `.github/workflows/Examples.yml` 경로 필터, 테스트 명령, 아티팩트 경로 및 summary/coverage 참조를 업데이트합니다.
- [ ] `.github/workflows/nightly.yml`이 여전히 `./scripts/smoke-validate.sh all-smoke`을 통해 모듈을 덮고 있는지 확인합니다. 스크립트 적용 범위가 충분하지 않은 경우에만 편집하십시오.
- [ ] `docs/coverage-matrix.md`을 새 모듈로 업데이트하세요.
- [ ] 워크플로 편집 후 `actionlint .github/workflows/Examples.yml .github/workflows/nightly.yml` 및 `rg "\\'" .github/workflows`을 실행합니다.

## 작업 6: 검증 및 검토 준비

- [ ] `./gradlew projects`을 실행하고 `:image-processing-profile-image-moderation`이(가) 나열되어 있는지 확인합니다.
- [ ] `./gradlew :image-processing-profile-image-moderation:test --console=plain`를 실행하세요.
- [ ] `./scripts/smoke-validate.sh stale-check`를 실행하세요.
- [ ] `./scripts/smoke-validate.sh all-smoke`을 실행하세요. 구체적인 로컬 차단기에 대해서만 연기하고 차단기를 기록합니다.
- [ ] 로컬에서 저렴한 Smoke-check README/runbook 명령: 컨트롤러 테스트를 통해 생성된 샘플 이미지 업로드 경로, 기본 이미지 가져오기, 개인 접두사 거부, 액추에이터 상태 및 메트릭 엔드포인트 어설션.
- [ ] `git diff --check`를 실행하세요.
- [ ] `rg "image-processing-profile-image-moderation|profile-image-moderation|:image-processing-profile-image-moderation:test" .github scripts README.md README.ko.md AGENTS.md settings.gradle.kts`를 실행하세요.
- [ ] 간결한 context/decision/outcome/verification으로 `docs/lessons/2026-07-04-profile-image-moderation.md`을 만들거나 PR 앞에 수업이 없는 이유를 명시적으로 기록하세요.
- [ ] P0/P1=0으로 6-R단계 7단계 검토 증거를 준비합니다.

## 자체 검토

- 사양 적용 범위: 계획에는 모듈, upload/pending/approval/rejection/failure/no-upload/stale 완료, 비공개 경계, 관찰 가능성, 문서, CI/smoke 등록이 포함됩니다.
- 자리 표시자 검사: 의도적으로 남겨진 TBD/TODO 자리 표시자는 없습니다.
- 유형 일관성: `ProfileImageStatus`, `ModerationDecision`, `ProfileImageView` 및 `uploadId`을 일관되게 사용하십시오.
