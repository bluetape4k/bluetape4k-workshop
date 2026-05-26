package io.bluetape4k.workshop.imageprocessing.advanced.model

/**
 * Discriminator for rows in `image_objects`.
 *
 * ## Kinds
 * - [ORIGINAL] — the source image uploaded by the client
 * - [VARIANT]  — a derived image (e.g. thumbnail, webp, 2x) produced during processing
 */
enum class ImageObjectKind {
    ORIGINAL,
    VARIANT,
}
