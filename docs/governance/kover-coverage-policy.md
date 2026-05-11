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
- Add local coverage checks only for examples that become reusable production
  templates.

## CI/Nightly Contract

CI/Nightly run build/test signals. No `koverVerify` task is required unless a
module is promoted from demo code to a maintained template.
