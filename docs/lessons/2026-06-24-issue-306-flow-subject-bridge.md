# Lessons Learned — Issue 306 Flow Subject Bridge (2026-06-24)

**Related issue**: #306
**Affected module**: `:flow-extensions-subject-bridge`

## L1: Subject examples need explicit subscriber readiness

### Problem

`PublishSubject` drops pre-subscription events and `MulticastSubject` can suspend producer progress until the expected collectors are registered. A workshop example that emits immediately after launching collectors can become flaky or teach the wrong contract.

### Lesson

Use `awaitCollector` / `awaitCollectors` in examples and tests whenever the point of the example depends on active hot-stream subscribers.

## L2: Subject mutation should stay behind the bridge boundary

### Problem

Exposing Subject instances directly is shorter, but it encourages application code to mutate hot streams from many places.

### Lesson

Workshop examples should expose read-only `Flow` views and callback-style bridge methods. The README can still name the backing Subject type without making arbitrary mutation look like the normal application architecture.
