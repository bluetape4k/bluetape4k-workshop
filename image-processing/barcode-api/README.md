# Image Barcode API

This Spring Boot example consumes the released `bluetape4k-images` 0.4.0 barcode API through the
`bluetape4k-dependencies` 1.4.0 BOM. It keeps the application code provider-neutral and wires the
ZXing implementation only at the configuration boundary.

## What it demonstrates

- `ImmutableImage.extractBarcodes(reader)` with `BarcodeReader` and `BarcodeResult`.
- Encoded byte, decoded side, and decoded pixel limits before provider invocation.
- PNG, JPEG, and WebP content-type validation with cancellation-safe multipart reads.
- Deterministic `/sample`, `/no-result`, and `/malformed` routes for smoke tests.

## Run

```bash
./gradlew :image-processing-barcode-api:test
./gradlew :image-processing-barcode-api:bootRun
```

The HTTP surface is `POST /api/barcodes/extract` with a multipart `file` part. The fixture routes
are intentionally deterministic and are for learning and contract tests, not production uploads.
