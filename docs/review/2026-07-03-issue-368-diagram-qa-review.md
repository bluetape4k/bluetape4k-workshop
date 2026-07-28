# Issue 368 Diagram QA Review

날짜: 2026-07-03
범위: milestone 1.3.1 README diagram QA hardening for architecture, sequence, state SVG/PNG assets.

## 7-Tier Findings

남은 P0/P1: 없음.

PR 전에 해결됨:

- 여러 generated SVG가 시각적으로는 통과했지만 audit가 감지할 수 있는 connector/card metadata가 부족했다. learner-facing content를 바꾸지 않고 `card`, `flow`, marker, `data-connector` metadata를 추가했다.
- Sequence diagram에는 label-pill class와 marker styling이 일관되지 않았다. numbered label, transparent alt region, branch-specific call color, marker/path color parity를 정규화했다.
- Kotlin Flow event aggregation sequence는 framed title/subtitle, participant header, activation bar, numbered call label, light label pill을 갖춘 현재 best-practices layout으로 재구성했다.
- Kafka fallback sequence는 rendered class가 CSS rule과 맞지 않아 black call-label pill을 표시했다. 명시적 `labelPill` styling을 추가하고 PNG를 다시 rendering했다.
- Architecture connector check는 checklist가 기대하는 위치에서 sharp mixed-corner bend를 rounded bend로 변환하여 강화했다.

## 근거

- `node scripts/validate-readme-diagram-qa.mjs $(git diff --name-only 10c6a1078..develop -- 'docs/images/readme-diagrams/*.svg')`
  - 결과: PASS
  - 범위: 37 SVG targets
  - Weak reference rows: 0
  - Architecture validator: PASS, checked 113
  - Sequence validator: PASS, checked 88
  - Sequence style reference audit: PASS, sequence files 16
- `python3 /Users/debop/.codex/skills/bluetape4k-diagram/references/diagram-sequence-style-audit.py $(git diff --name-only 10c6a1078..develop -- 'docs/images/readme-diagrams/*sequence*.svg')`
  - 결과: PASS, sequence files 16
- `git diff --check`: PASS
- Rendered PNG eye check:
  - `aws-cloudwatch-imds-observability-readme-architecture-01.png`: PASS, broken icon 없음, label centered, legend visible.
  - `aws-cloudwatch-imds-observability-readme-sequence-01.png`: PASS, alt region transparent, branch label readable, marker color matches paths.
  - `aws-s3-vectors-access-grants-readme-architecture-01.png`: PASS, vertical layer flow clear, rounded bend correct, legend visible.
  - `kafka-outbox-fallback-readme-architecture-01.png`: PASS, card aligned, connector metadata detected, PNG arrow mismatch 없음.
  - `kafka-outbox-fallback-readme-sequence-01.png`: label-pill styling repair 이후 PASS, label이 더 이상 black block으로 render되지 않음.
  - `kafka-outbox-fallback-readme-state-01.png`: PASS, lifecycle path가 legible하며 sharp-bend나 arrowhead defect 없음.
  - `kotlin-flow-extensions-event-aggregation-readme-architecture-01.png`: PASS, rounded fan-out connector와 layer spacing이 legible.
  - `kotlin-flow-extensions-event-aggregation-readme-sequence-01.png`: PASS, best-practices sequence layout 적용.
  - `kotlin-flow-extensions-metrics-sampling-readme-architecture-01.png`: PASS, centered card와 simple vertical flow.
  - `kotlin-flow-extensions-metrics-sampling-readme-sequence-01.png`: PASS, label, alt frame, dashed call이 readable.
  - `kotlin-text-processing-readme-architecture-01.png`: PASS, metadata repair가 visual layout을 바꾸지 않음.
  - `spring-boot-text-moderation-api-readme-architecture-01.png`: PASS, connector가 readable하고 aligned.
  - `spring-boot-text-moderation-api-readme-sequence-01.png`: PASS, transparent alt region과 branch color가 보존됨.

## 검토 메모

이 review는 단순히 그림이 렌더링되는지만 본 것이 아니라, 이후 자동 감사가 같은 결함을 다시 잡을 수 있도록 SVG 내부 metadata와 시각적 규칙을 함께 정렬했는지 확인했다. 따라서 검증 근거는 validator 통과와 full-size PNG eye check를 모두 포함한다.

## 잔여 위험

- #368은 diagram QA/style에 한정된다. Kafka outbox semantic follow-up은 #369에서 별도로 추적한다.
