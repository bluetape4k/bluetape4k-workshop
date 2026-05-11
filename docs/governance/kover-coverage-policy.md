# Kover Coverage Policy

## Current Status

`bluetape4k-workshop` is a workshop/demo repository and does not enforce Kover
coverage thresholds.

## Policy

Status: documented workshop/demo exception.

The repository demonstrates bluetape4k integrations across many frameworks and
runtime combinations. Build and test health are the primary signals; coverage is
not a production release gate.

## Threshold Plan

- Keep examples compiling and tests passing.
- Use coverage reports only as an informational signal when an example becomes a
  reusable production template.

## CI/Nightly Contract

CI/Nightly run build/test signals. Coverage reports, if added, must remain
informational and must not introduce a failing threshold by default.
