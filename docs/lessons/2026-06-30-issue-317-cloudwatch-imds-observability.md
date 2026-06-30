# Issue #317 CloudWatch IMDS Observability

- Context: Added an AWS observability workshop module for CloudWatch metric/log
  publish intent, Micrometer meter snapshots, and explicit IMDS metadata
  boundaries.
- Decision: Keep the default runtime local-first with local operation beans and
  disabled bluetape4k AWS auto-configuration. Real CloudWatch and IMDS behavior
  remains manual opt-in through `real-aws`.
- Outcome: The module compiles and tests without real AWS credentials. README
  pairs explain local runs, optional real AWS runs, failure behavior, and IMDS
  non-credential semantics.
- Diagram lesson: The bluetape4k diagram checklist now requires both skill
  audits and repo-local validators. Use official catalog icons for real managed
  services, keep local fake adapters visually distinct, and run full-size PNG
  eye inspection after any font or layout change.
- Future agents: If a global diagram validator fails on an unrelated legacy
  diagram, either fix that diagram or add it to the script's legacy list with a
  clear reason before claiming repository-level diagram validation.
