# image-processing-ocr-api

[한국어](README.ko.md) | English

## Overview

**image-processing-ocr-api** is a Spring Boot 4 multipart API example for
`bluetape4k-images-ocr`. It validates JPEG, PNG, or WebP uploads, decodes the
image with `immutableImageOf`, and returns a structured OCR response. The default
path validates the image and returns a deterministic `UNAVAILABLE` fallback
without requiring native Tesseract.

Use this module when you want to learn the OCR API boundary. It is not a
production upload service.

## Architecture

![image-processing-ocr-api architecture](../../docs/images/readme-diagrams/image-ocr-api-readme-architecture-01.png)

The flow is top-to-bottom: `ImageOcrController` accepts multipart input,
`ImageOcrServiceImpl` validates metadata and decoded pixels, then either
short-circuits to a fallback response or calls a bounded native `OcrEngine`.

## Request Flow

![image-processing-ocr-api sequence](../../docs/images/readme-diagrams/image-ocr-api-readme-sequence-01.png)

The default smoke path validates a real decodable image, skips Tesseract, and
returns `UNAVAILABLE`. Native OCR is opt-in through `workshop.ocr.native-enabled`
or `-Docr.enabled=true`.

## Endpoint

```text
POST /api/images/ocr
Content-Type: multipart/form-data

file      required image/jpeg, image/png, or image/webp
language  optional repeated or comma-separated Tesseract language codes
```

```bash
curl -F "file=@docs/images/readme-diagrams/image-ocr-api-readme-architecture-01.png;type=image/png" \
  -F "language=eng" \
  http://localhost:8080/api/images/ocr
```

Invalid uploads return `400 Bad Request`. Valid uploads that cannot run native
OCR return structured `200 OK` data with `status=UNAVAILABLE`.

## Fallback Response

```json
{
  "requestId": "ocr-sample-request",
  "status": "UNAVAILABLE",
  "engine": "disabled",
  "languages": ["eng"],
  "confidence": null,
  "text": "",
  "blocks": [],
  "warnings": [
    "Native OCR is disabled. Enable workshop.ocr.native-enabled=true or -Docr.enabled=true."
  ]
}
```

## Completed Response

```json
{
  "requestId": "ocr-sample-request",
  "status": "COMPLETED",
  "engine": "tesseract",
  "languages": ["eng", "kor"],
  "confidence": null,
  "text": "Bluetape OCR\nSecond line",
  "blocks": [
    { "index": 0, "text": "Bluetape OCR", "confidence": null },
    { "index": 1, "text": "Second line", "confidence": null }
  ],
  "warnings": [
    "Confidence is not available from the current OCR engine."
  ]
}
```

`confidence` is nullable because the current `OcrResult` contract exposes text
and effective options, not per-line or per-word confidence.

## Configuration

| Property | Default | Purpose |
|---|---:|---|
| `workshop.ocr.native-enabled` | `false` | Enables the native `TesseractOcrEngine` bean |
| `workshop.ocr.max-upload-bytes` | `5242880` | Service byte limit |
| `workshop.ocr.max-image-pixels` | `12000000` | Decoded image pixel budget |
| `workshop.ocr.timeout` | `10s` | Native OCR timeout |
| `workshop.ocr.languages` | `eng` | Default language list |
| `workshop.ocr.tessdata-path` | empty | Optional Tesseract trained-data directory |
| `spring.servlet.multipart.max-file-size` | `5MB` | Container multipart limit |
| `spring.servlet.multipart.max-request-size` | `5MB` | Container multipart request limit |

Keep the Spring multipart limits aligned with `workshop.ocr.max-upload-bytes`.

## Run

Default fallback mode:

```bash
./gradlew :image-processing-ocr-api:bootRun
curl -F "file=@docs/images/readme-diagrams/image-ocr-api-readme-architecture-01.png;type=image/png" \
  http://localhost:8080/api/images/ocr
```

Native OCR mode:

```bash
./gradlew :image-processing-ocr-api:bootRun \
  --args='--workshop.ocr.native-enabled=true --workshop.ocr.tessdata-path=/opt/homebrew/share/tessdata'
```

You can also run tests or the app with `-Docr.enabled=true`, but the default test
suite uses fake engines for completed responses. Use the manual `bootRun` plus
curl path when local Tesseract and language packs are installed.

## Native Prerequisites

```bash
# macOS
brew install tesseract
ls /opt/homebrew/share/tessdata/eng.traineddata

# Ubuntu/Debian
sudo apt-get update
sudo apt-get install -y tesseract-ocr tesseract-ocr-eng
ls /usr/share/tesseract-ocr/5/tessdata/eng.traineddata
```

Set `workshop.ocr.tessdata-path` when Tesseract cannot find trained data through
its default lookup path.

For Korean OCR, install and verify `kor.traineddata`, then send
`-F "language=eng,kor"` in the curl command.

## Troubleshooting

| Symptom | Response | Check |
|---|---|---|
| Missing Tesseract library or tessdata | `200 OK`, `status=UNAVAILABLE` | Install Tesseract and set `workshop.ocr.tessdata-path` when needed |
| Missing language pack | `200 OK`, `status=UNAVAILABLE` or low-quality text | Verify `eng.traineddata`, `kor.traineddata`, or the requested pack |
| Empty, corrupt, or non-image bytes | `400 Bad Request` | Use a real JPEG, PNG, or WebP file |
| Declared type does not match bytes | `400 Bad Request` | Keep `;type=image/png` aligned with the actual file format |
| Unsupported subtype such as GIF | `400 Bad Request` | Convert to JPEG, PNG, or WebP |
| Image exceeds pixel budget | `400 Bad Request` | Resize below `workshop.ocr.max-image-pixels` |

## Workshop Boundary

This example has no authentication, antivirus scanning, persistence, rate
limiting, storage policy, queueing, audit workflow, PII management, or production
upload hardening. OCR text may contain sensitive data. The service never logs
uploaded bytes or OCR text; production systems need explicit redaction and
retention policies.

## Tests

```bash
# Deterministic smoke path. Native OCR stays disabled.
./gradlew :image-processing-ocr-api:test

# Local opt-in path. Requires native Tesseract and language packs.
./gradlew :image-processing-ocr-api:test -Docr.enabled=true
```

The tests inject a fake `OcrEngine` for completed responses and verify fallback,
validation, sanitized failure mapping, language normalization, and cancellation
propagation.

## Dependency Note

The module uses the root `bluetape4k-dependencies` BOM. Do not add an individual
`bluetape4k-image` BOM or hard-coded OCR artifact version.
