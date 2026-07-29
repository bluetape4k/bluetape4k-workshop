# Issue 316 Text Moderation API Lesson

## 배경

Milestone 1.3.1에는 deterministic text web-safety moderation workshop example이
필요했다. example은 local로 유지되어야 했고, root `bluetape4k-dependencies` BOM을
사용해야 했으며, 기존 `bluetape4k-text` alias를 재사용하고 learner-facing flow를 README
diagram으로 설명해야 했다.

## 결정

작은 `TextModerationController`, 재사용 가능한 `TextModerationService`, Lingua language
detection 및 Aho-Corasick blockword matcher용 singleton bean과 함께 Spring MVC를 사용한다.
blockword는 configurable하게 두되 작게 유지해 learner가 모든 response field를 configuration과
code까지 추적할 수 있게 한다.

## 결과

module은 external service 없이 success masking, Korean language detection, invalid input
mapping, oversized payload mapping, bean reuse를 검증한다. README diagram은 top-to-bottom
architecture layer와 participant header, lifeline, activation bar, pill label, 별도 error
branch note가 있는 best-practices sequence layout을 사용한다.

## 검증

- `./gradlew :spring-boot-text-moderation-api:test --warning-mode all --console=plain`: 10 tests passed.
- `./gradlew :spring-boot-text-moderation-api:compileTestKotlin --warning-mode all --console=plain`: build passed; only pre-existing root Gradle deprecation warnings appeared.
- README language, parity, architecture, and sequence validators passed.
- Diagram geometry, connector, endpoint, mixed-corner, and sequence style audits passed.
- PNG eyes-check passed for both architecture and sequence diagrams.
- `./scripts/smoke-validate.sh stale-check`: active modules 88/88, no stale refs, no broken README image links.

## 향후 참고

다음 workshop example에서는 README catalog entry와 동시에 module을 Examples workflow와
`scripts/smoke-validate.sh`에 추가한다. repo validator뿐 아니라 diagram geometry audit도
실행한다. sharp orthogonal turn은 repo parsing을 통과하더라도 diagram skill checklist에는
실패할 수 있다.
