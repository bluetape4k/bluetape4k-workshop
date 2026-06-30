# Text Moderation API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a deterministic Spring Boot text moderation API example for issue #316.

**Architecture:** A small Spring MVC module exposes one JSON endpoint. Singleton
beans own the expensive Lingua detector and Aho-Corasick matcher. The controller
maps validation failures to `400` and payload-size failures to `413`.

**Tech Stack:** Kotlin, Spring Boot 4 MVC, bluetape4k text-search, bluetape4k
Lingua, bluetape4k assertions, JUnit 5.

---

## File Structure

- Create `spring-boot/text-moderation-api/build.gradle.kts`
- Create `spring-boot/text-moderation-api/src/main/kotlin/io/bluetape4k/workshop/textmoderation/*`
- Create `spring-boot/text-moderation-api/src/main/resources/application.yml`
- Create `spring-boot/text-moderation-api/src/test/kotlin/io/bluetape4k/workshop/textmoderation/*`
- Create `spring-boot/text-moderation-api/src/test/resources/junit-platform.properties`
- Create `spring-boot/text-moderation-api/src/test/resources/logback-test.xml`
- Create `spring-boot/text-moderation-api/README.md`
- Create `spring-boot/text-moderation-api/README.ko.md`
- Create architecture and sequence diagram SVG/PNG files under `docs/images/readme-diagrams/`
- Modify root `README.md`, `README.ko.md`
- Modify `.github/workflows/Examples.yml`
- Modify `scripts/smoke-validate.sh`

## Tasks

### Task 1: Module Skeleton

- [ ] Create `build.gradle.kts` with `kotlin.spring`, `spring.boot`, Spring MVC,
      validation, bluetape4k text aliases, logging, JUnit5, assertions, and
      Spring Boot MVC test dependencies.
- [ ] Add `TextModerationApplication.kt` and `application.yml`.
- [ ] Add test resources matching existing Spring Boot modules.
- [ ] Run `./gradlew :spring-boot-text-moderation-api:compileKotlin`.

### Task 2: Domain And Service

- [ ] Add serializable models:
      `ModerationRequest`, `ModerationResponse`, `ModerationErrorResponse`.
- [ ] Add `TextModerationProperties` with `maxTextCharacters = 2000` and default
      blockwords `spam`, `badword`, `abuse`, `hate`.
- [ ] Add singleton beans for `LanguageDetector` and
      `AhoCorasickAutomaton<String>`.
- [ ] Add `TextModerationService.analyze(text: String)`.
- [ ] Add unit tests for masking, language detection, oversized text, blank text,
      multilingual text, and singleton bean reuse.

### Task 3: HTTP Boundary

- [ ] Add `TextModerationController` with `POST /api/moderation/analyze`.
- [ ] Add controller advice for `400` and `413` responses.
- [ ] Add WebTestClient tests for success, invalid request, missing text, and
      oversized request.
- [ ] Run `./gradlew :spring-boot-text-moderation-api:test`.

### Task 4: README And Diagrams

- [ ] Write `README.md` and `README.ko.md` with source-equivalent sections.
- [ ] Generate architecture and sequence SVG+PNG assets.
- [ ] Embed PNG assets in both READMEs.
- [ ] Run README language/parity and diagram validation scripts.

### Task 5: Registration And CI

- [ ] Add the module to root README locale tables under Spring Boot Operations.
- [ ] Add Examples workflow path filters, smoke command, and artifact paths.
- [ ] Add the module to `scripts/smoke-validate.sh` `all-smoke` and
      `spring-boot`; increase the stale-check expected project count by one.
- [ ] Run `actionlint .github/workflows/Examples.yml`.
- [ ] Run `./scripts/smoke-validate.sh stale-check`.

### Task 6: Final Verification

- [ ] Run `./gradlew :spring-boot-text-moderation-api:test`.
- [ ] Run `./gradlew :spring-boot-text-moderation-api:compileTestKotlin --warning-mode all`.
- [ ] Run `git diff --check`.
- [ ] Run 7-tier review and fix all P0/P1 findings.
- [ ] Add lessons, commit, push, create PR, verify metadata, and monitor CI.

## Self-Review

- Spec coverage: all #316 acceptance criteria map to Tasks 1-6.
- Placeholder scan: no `TBD`, `TODO`, or open-ended implementation placeholders.
- Type consistency: module path, Gradle project name, package, endpoint, and
  response fields are consistent across spec and plan.
