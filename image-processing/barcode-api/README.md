# Image Barcode API

[한국어](README.ko.md) | English

This Spring Boot example consumes the `bluetape4k-images` barcode API through the
`bluetape4k-dependencies` `2.0.0-SNAPSHOT` BOM. Individual Bluetape module versions remain
versionless in the catalog. The application code stays provider-neutral and wires the ZXing
implementation only at the configuration boundary.

## What it demonstrates

- `ImmutableImage.extractBarcodes(reader)` with `BarcodeReader` and `BarcodeResult`.
- `immutableExternalImageOf(bytes, ImageDecodeLimits)` as the final bounded decode boundary before
  a provider can inspect pixels. The helper rejects oversized encoded input and unknown or
  oversized dimensions before an unrestricted decoder call.
- A bounded dimension/metadata preflight that preserves the example's stable HTTP `413` response;
  the strict helper remains the final defense after that preflight.
- PNG, JPEG, and WebP decoding, malformed/unknown-dimension rejection, and cancellation-safe
  multipart reads.
- Deterministic `/sample`, `/no-result`, and `/malformed` routes for smoke tests.

## Run

```bash
./gradlew :image-processing-barcode-api:test
./gradlew :image-processing-barcode-api:bootRun
```

The HTTP surface is `POST /api/barcodes/extract` with a multipart `file` part. The fixture routes
are intentionally deterministic and are for learning and contract tests, not production uploads.
The service maps its own size preflight failures to HTTP `413`, while preserving provider-neutral
`BarcodeException` failures and coroutine cancellation.
