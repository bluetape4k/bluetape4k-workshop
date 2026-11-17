# Image Barcode API

[한국어](README.ko.md) | English

This Spring Boot example consumes the `bluetape4k-images` barcode API through the
`bluetape4k-dependencies` `2.1.0-SNAPSHOT` BOM. Individual Bluetape module versions remain
versionless in the catalog. The application code stays provider-neutral and wires the ZXing
implementation only at the configuration boundary.

## Why this example exists

Barcode extraction sits on an untrusted upload boundary: a client wants a quick
scan result, while the decoder must not receive an oversized or malformed image
before the service can reject it. This example shows the business-shaped API
contract for that small but common ingestion step without coupling the
application service to ZXing.

The design keeps provider choice at configuration and puts bounded metadata and
pixel checks before the decoder, with the strict Bluetape helper as the final
defense. The deterministic fixture routes make success, no-result, malformed
input, and HTTP `413` behavior inspectable; they are not a production upload,
virus-scanning, or moderation policy.

`BarcodeExtractionServiceTest` and `BarcodeApiApplicationTest`, run by the
`test` command below, are the test-backed verification for this boundary. They
cover formats, limits, malformed input, provider failure, cancellation, and the
HTTP contract rather than claiming scanner accuracy for every camera or label.

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

## Design and verification

The request path checks upload metadata and dimensions before decoding, then passes the image through
the bounded decoder before calling the injected `BarcodeReader`. This keeps the HTTP 413 response
independent of the ZXing adapter. `BarcodeExtractionServiceTest` covers supported image formats,
size and dimension rejection, malformed input, provider failures, and cancellation;
`BarcodeApiApplicationTest` checks the HTTP contract.

## Run

```bash
./gradlew :image-processing-barcode-api:test
./gradlew :image-processing-barcode-api:bootRun
```

The HTTP surface is `POST /api/barcodes/extract` with a multipart `file` part. The fixture routes
are intentionally deterministic and are for learning and contract tests, not production uploads.
The service maps its own size preflight failures to HTTP `413`, while preserving provider-neutral
`BarcodeException` failures and coroutine cancellation.
