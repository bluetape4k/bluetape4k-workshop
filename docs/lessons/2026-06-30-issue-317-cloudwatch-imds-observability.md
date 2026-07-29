# Issue #317 CloudWatch IMDS Observability

- 배경: CloudWatch metric/log publish intent, Micrometer meter snapshot, 명시적 IMDS
  metadata boundary를 다루는 AWS observability workshop module을 추가했다.
- 결정: default runtime은 local operation bean과 비활성화된 bluetape4k AWS
  auto-configuration으로 local-first를 유지한다. 실제 CloudWatch 및 IMDS behavior는
  `real-aws`를 통한 manual opt-in으로 남긴다.
- 결과: module은 실제 AWS credential 없이 compile/test된다. README pair는 local run,
  optional real AWS run, failure behavior, IMDS non-credential semantic을 설명한다.
- Diagram lesson: bluetape4k diagram checklist는 이제 skill audit과 repo-local validator를
  모두 요구한다. real managed service에는 official catalog icon을 사용하고, local fake
  adapter는 시각적으로 구분하며, font 또는 layout 변경 뒤에는 full-size PNG eye inspection을
  실행한다.
- 향후 작업자: global diagram validator가 관련 없는 legacy diagram에서 실패하면,
  repository-level diagram validation을 주장하기 전에 그 diagram을 수정하거나 명확한 이유와
  함께 script의 legacy list에 추가한다.
