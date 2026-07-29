# 텍스트 조정 API 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**목표:** 이슈 #316에 대한 결정적 Spring Boot 텍스트 조정 API 예시를 추가합니다.

**아키텍처:** 작은 Spring MVC 모듈은 하나의 JSON 엔드포인트를 노출합니다. 하나씩 일어나는 것
Bean은 값비싼 Lingua 검출기와 Aho-Corasick 일치자를 소유하고 있습니다. 컨트롤러
유효성 검사 실패는 `400`에 매핑되고 페이로드 크기 실패는 `413`에 매핑됩니다.

**기술 스택:** Kotlin, Spring Boot 4 MVC, bluetape4k 텍스트 검색, bluetape4k
Lingua, bluetape4k 검증, JUnit 5.

---

## 파일 구조

- `spring-boot/text-moderation-api/build.gradle.kts` 생성
- `spring-boot/text-moderation-api/src/main/kotlin/io/bluetape4k/workshop/textmoderation/*` 생성
- `spring-boot/text-moderation-api/src/main/resources/application.yml` 생성
- `spring-boot/text-moderation-api/src/test/kotlin/io/bluetape4k/workshop/textmoderation/*` 생성
- `spring-boot/text-moderation-api/src/test/resources/junit-platform.properties` 생성
- `spring-boot/text-moderation-api/src/test/resources/logback-test.xml` 생성
- `spring-boot/text-moderation-api/README.md` 생성
- `spring-boot/text-moderation-api/README.ko.md` 생성
- `docs/images/readme-diagrams/` 아래에 아키텍처 및 시퀀스 다이어그램 SVG/PNG 파일을 생성합니다.
- 루트 `README.md`, `README.ko.md` 수정
- `.github/workflows/Examples.yml` 수정
- `scripts/smoke-validate.sh` 수정

## 작업

### 작업 1: 모듈 뼈대

- [ ] `kotlin.spring`, `spring.boot`, Spring MVC을 사용하여 `build.gradle.kts`을 생성합니다.
      유효성 검사, bluetape4k 텍스트 별칭, 로깅, JUnit5, 어설션 및
      Spring Boot MVC 종속성을 테스트합니다.
- [ ] `TextModerationApplication.kt` 및 `application.yml`을 추가합니다.
- [ ] 기존 Spring Boot 모듈과 일치하는 테스트 리소스를 추가합니다.
- [ ] `./gradlew :spring-boot-text-moderation-api:compileKotlin`를 실행하세요.

### 작업 2: 도메인 및 서비스

- [ ] 직렬화 가능한 모델을 추가합니다.
      `ModerationRequest`, `ModerationResponse`, `ModerationErrorResponse`.
- [ ] `TextModerationProperties`을 `maxTextCharacters = 2000`과 기본값으로 추가합니다.
      블록워드 `spam`, `badword`, `abuse`, `hate`.
- [ ] `LanguageDetector`에 대한 싱글톤 Bean을 추가하고
      `AhoCorasickAutomaton<String>`.
- [ ] `TextModerationService.analyze(text: String)`을 추가합니다.
- [ ] 마스킹, 언어 감지, 큰 텍스트, 빈 텍스트에 대한 단위 테스트를 추가합니다.
      다국어 텍스트 및 싱글톤 Bean 재사용.

### 작업 3: HTTP 경계

- [ ] `TextModerationController`을 `POST /api/moderation/analyze`에 추가합니다.
- [ ] `400` 및 `413` 응답에 대한 컨트롤러 조언을 추가합니다.
- [ ] 성공, 잘못된 요청, 텍스트 누락 등에 대한 WebTestClient 테스트를 추가하고
      대형 요청.
- [ ] `./gradlew :spring-boot-text-moderation-api:test`를 실행하세요.

### 작업 4: README 및 다이어그램

- [ ] 소스와 동등한 섹션을 사용하여 `README.md` 및 `README.ko.md`을 작성합니다.
- [ ] 아키텍처 및 시퀀스 SVG+PNG 자산을 생성합니다.
- [ ] 두 README에 PNG 자산을 포함합니다.
- [ ] README language/parity 및 다이어그램 유효성 검사 스크립트를 실행합니다.

### 작업 5: 등록 및 CI

- [ ] Spring Boot 작업 아래의 루트 README 로케일 테이블에 모듈을 추가합니다.
- [ ] 예제 워크플로 경로 필터, smoke 명령 및 아티팩트 경로를 추가합니다.
- [ ] `scripts/smoke-validate.sh` `all-smoke`에 모듈을 추가하고
      `spring-boot`; 부실 확인 예상 프로젝트 수를 1 늘립니다.
- [ ] `actionlint .github/workflows/Examples.yml`를 실행하세요.
- [ ] `./scripts/smoke-validate.sh stale-check`를 실행하세요.

### 작업 6: 최종 검증

- [ ] `./gradlew :spring-boot-text-moderation-api:test`를 실행하세요.
- [ ] `./gradlew :spring-boot-text-moderation-api:compileTestKotlin --warning-mode all`를 실행하세요.
- [ ] `git diff --check`를 실행하세요.
- [ ] 7계층 검토를 실행하고 모든 P0/P1 발견 사항을 수정합니다.
- [ ] 강의 추가, 커밋, 푸시, PR 생성, 메타데이터 확인, CI 모니터링이 가능합니다.

## 자체 검토

- 사양 범위: 모든 #316 승인 기준은 작업 1-6에 매핑됩니다.
- 자리 표시자 스캔: `TBD`, `TODO` 또는 개방형 구현 자리 표시자가 없습니다.
- 유형 일관성: 모듈 경로, Gradle 프로젝트 이름, 패키지, 엔드포인트 및
  응답 필드는 사양과 계획 전체에서 일관됩니다.
