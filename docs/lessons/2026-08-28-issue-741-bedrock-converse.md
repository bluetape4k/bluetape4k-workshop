# Issue #741 Bedrock Converse consumer 경계

## Context

워크숍에 AWS Kotlin SDK Bedrock Converse/ConverseStream 소비자 예제가 없었다.
실제 AWS 호출을 기본 경로에 넣으면 credential resolution, 비용, network
의존성이 smoke와 CI를 오염시키므로 upstream bluetape4k helper를 소비하는
작은 local-first 모듈이 필요했다.

## Decision or Finding

- `aws/bedrock-converse/`를 독립 consumer 모듈로 추가한다.
- `BedrockPrompt`는 non-blank `modelId`와 `prompt`만 보유하고 모델별 request
  형식은 upstream `userMessageOf`와 `converse*` helper에 위임한다.
- `BedrockConverseService.converse`는 작업마다 client를 소유하고 결과를
  `textOrEmpty()`로 변환한 뒤 닫는다.
- `stream`은 collection 전에는 client나 native SDK를 만들지 않는 cold
  `Flow<String>`이며, collection마다 독립된 client scope를 만든다.
- collector cancellation은 native stream과 client close로 전파하고,
  `CancellationException`과 native failure의 identity는 변경하지 않는다.
- 기본 application은 mode 안내만 출력한다. `real-aws`는 명시적인 client
  factory를 주입할 때만 사용할 수 있으며 prompt, response, credential,
  endpoint는 log와 report에 기록하지 않는다.

## Outcome

request mapping, cold collection, cancellation/close 순서, native failure
identity, 입력 검증을 fake client로 검증하는 AWS 예제가 추가되었다. Gradle
catalog에는 AWS Kotlin Bedrock Runtime alias를 `aws-kotlin` version ref로
등록하고, smoke/CI/coverage matrix와 영문·국문 README를 함께 등록했다.

## Verification

- `:aws-bedrock-converse:test --tests '*BedrockConverseServiceTest'` 통과
- `scripts/smoke-validate.sh`의 `all-smoke`, `aws`, `stale-check`에 모듈 등록
- 기본 실행은 credential과 network를 요구하지 않음
- `git diff --check`와 Korean terminology audit 통과

## Future Guidance

새 AWS consumer가 추가될 때도 실제 서비스 호출을 기본 smoke에 넣지 말고,
upstream helper를 재사용하는 request/lifecycle 경계를 먼저 고정한다. live AWS
경로는 loopback 또는 HTTPS endpoint, 명시적 credential provider, 비용과 cleanup
조건을 README에 적은 뒤 별도 opt-in으로만 노출한다.
