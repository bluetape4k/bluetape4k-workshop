# Issue #317 Spec Review

- 날짜: 2026-06-30
- 이슈: https://github.com/bluetape4k/bluetape4k-workshop/issues/317
- Spec: `docs/superpowers/specs/2026-06-30-issue-317-cloudwatch-imds-observability-design.md`
- 리뷰 게이트: Step 2-R
- 실행 메모: 이전 턴에서 native subagent 정리가 지연되었으므로, workflow를 막지 않기 위해 동일한 6개 관점 검토 계약을 main session에서 기록했다.

## 판정

| 심각도 | 건수 | 상태 |
|---|---:|---|
| P0 | 0 | PASS |
| P1 | 0 | PASS |
| P2 | 6 | Step 3 plan으로 이월 |
| P3 | 3 | 선택적 polish |

P0=0, P1=0이므로 Step 2-R은 진행할 수 있다.

## 관점별 발견사항

### 1. 성능

hot path, 지연 시간, allocation, contention, smoke-test 비용을 검토했다.

- P0/P1: 없음.
- P2: CloudWatch metric dimension은 low-cardinality로 유지해야 한다. order id, customer id, request id, metadata 값은 dimension에 추가하지 않는다.
- P2: 기본 테스트는 local/no-container로 유지하여 Examples workflow가 느려지거나 credential에 민감해지지 않게 해야 한다.
- P3: 문서에서 이 예제가 explicit snapshot을 publish하는 예제이며 high-throughput exporter가 아니라는 점을 언급한다.

근거:

- spec은 이미 안정적인 dimension인 `Outcome`, `Service`, `Source`를 선택했다.
- spec은 `micrometer-registry-cloudwatch`, scheduler, LocalStack, Testcontainers, 기본 테스트의 real AWS call을 제외한다.

### 2. 안정성

cancellation, retry/failure 동작, local/real mode 경계, 테스트 결정성을 검토했다.

- P0/P1: 없음.
- P2: implementation plan은 metric publish, logs publish, optional metadata read가 서로 다른 결과를 낼 때 partial-failure 동작을 정의해야 한다.
- P2: 테스트는 `CancellationException`이 failure report로 변환되지 않고 다시 throw됨을 증명해야 한다.
- P3: manual real AWS profile이 추가된다면 문서에서 실험용 short SDK timeout을 권장해야 한다.

근거:

- spec은 publish-failure report, local failure counter, metadata failure capture, 명시적 `CancellationException` 처리를 요구한다.
- 기본 테스트는 결정적인 fake 또는 mock operation을 사용한다.

### 3. 보안

민감 데이터 노출, credential leakage, IMDS 경계, 안전한 기본값을 검토했다.

- P0/P1: 없음.
- P2: log event 생성은 free-form request field를 sanitize하고 credential, token, header, environment 값, 전체 exception stack을 피해야 한다.
- P2: IMDS 테스트는 README 경고만 확인하는 데 그치지 말고 service가 credential document path를 절대 읽지 않는다는 점을 assert해야 한다.
- P3: README는 IMDS를 workshop의 credential mechanism으로 제시하지 않으면서 표준 AWS SDK credential provider 동작을 짧게 안내할 수 있다.

근거:

- spec은 IMDS가 opt-in이며 credential provider 또는 startup probe가 아니라고 명시한다.
- spec은 IMDS security credential document와 temporary credential 값을 명시적으로 제외한다.

### 4. 운영자

CI 등록, rollback, observability, namespace ownership, runbook 명확성을 검토했다.

- P0/P1: 없음.
- P2: Step 3 plan은 정확한 `.github/workflows/Examples.yml` path filter/job 수정과 `scripts/smoke-validate.sh` stale-count 업데이트를 포함해야 한다.
- P2: README는 local run command, optional real AWS profile command, 필요한 environment variable, real CloudWatch resource의 cleanup/cost warning을 포함해야 한다.

근거:

- spec은 Examples workflow 등록, observability smoke 등록, `actionlint`, `stale-check`를 이미 명시한다.
- optional real AWS mode는 manual이며 CI 범위 밖이다.

### 5. 개발자/API

module shape, Kotlin/Spring convention, dependency realism, maintainability를 검토했다.

- P0/P1: 없음.
- P2: implementation plan은 sibling source만 보지 말고 resolved `bluetape4k-aws` artifact 기준으로 정확한 public API signature를 검증해야 한다.
- P2: 새 DTO data class는 workspace Kotlin rule을 만족하도록 `java.io.Serializable`을 구현하고 `serialVersionUID`를 정의해야 한다.
- P3: module package는 예를 들어 `io.bluetape4k.workshop.aws.observability`처럼 좁게 유지한다.

근거:

- spec은 root BOM과 versionless `bluetape4k-aws` alias를 사용한다.
- `settings.gradle.kts`는 새 AWS module path를 자동 등록한다.

### 6. 사용자/호출자

learner ergonomics, misuse resistance, README flow, diagram, unsupported capability를 검토했다.

- P0/P1: 없음.
- P2: README 예시는 successful telemetry report와 failed telemetry report를 모두 보여 주어 learner가 local simulation과 failure boundary를 이해하게 해야 한다.
- P2: diagram은 local fake operation을 real AWS managed service와 시각적으로 구분하고, official AWS icon은 managed service에만 사용해야 한다.
- P2: sequence diagram은 numbered call, transparent alt/else body, branch-specific line color, full-size PNG visual inspection evidence를 사용해야 한다.

근거:

- spec은 bilingual README, layered architecture diagram, best-practices sequence diagram, official AWS CloudWatch icon, full-size visual inspection을 요구한다.

## 통합 검토

design을 막는 P0/P1 이슈는 없다. design은 issue #317, milestone scope, repository module-registration model, 문서화된 `bluetape4k-aws` 경계와 일치한다.

- CloudWatch와 CloudWatch Logs는 public Spring-facing operation을 통해 시연된다.
- Micrometer 지원은 registry replacement나 scheduled exporter가 아니라 manual snapshot publisher이다.
- IMDS는 명시적 opt-in이며 credential strategy가 아니다.
- 기본 테스트와 CI는 local, fast, credential-free 상태를 유지한다.

Step 3 plan으로 이월해야 할 항목:

1. metric publish, logs publish, meter snapshot publish, optional IMDS read에 대한 partial-failure semantic을 정의한다.
2. `CancellationException` propagation 테스트를 추가한다.
3. IMDS credential document path를 읽지 않는다는 점을 assert한다.
4. resolved dependency artifact 기준으로 public API signature를 검증한다.
5. 정확한 Examples workflow 및 smoke-validation 수정 내용을 포함한다.
6. diagram이 local fake bean과 real AWS managed service를 구분하고 sequence/style/geometry/endpoint/connector audit 및 visual inspection을 모두 통과하게 한다.

## 기각한 항목

- 예제를 증명하기 위해 real AWS integration test를 추가하지 않는다. 이는 issue의 credential-free default-test 경계를 위반한다.
- `micrometer-registry-cloudwatch`를 추가하지 않는다. workshop target은 published `CloudWatchMeterPublishingOperations` snapshot publisher이다.
- IMDS를 automatic credential discovery로 모델링하지 않는다. workshop은 IMDS를 명시적인 safe metadata read로만 제시해야 한다.

## 열린 질문

없음.
