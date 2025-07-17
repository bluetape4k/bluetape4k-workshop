package io.bluetape4k.okio

import okio.Buffer

/**
 * Buffer의 내용을 `cursor`를 통해 읽기 작업([block]) 수행합니다.
 */
inline fun <R> Buffer.readUnsafe(
    cursor: Buffer.UnsafeCursor = Buffer.UnsafeCursor(),
    block: (cursor: Buffer.UnsafeCursor) -> R,
): R =
    readUnsafe(cursor).use { block(it) }

/**
 * Buffer의 내용을 `cursor`를 통해 읽기/쓰기 작업([block]) 수행합니다.
 */
inline fun <R> Buffer.readAndWriteUnsafe(
    cursor: Buffer.UnsafeCursor = Buffer.UnsafeCursor(),
    block: (cursor: Buffer.UnsafeCursor) -> R,
): R =
    readAndWriteUnsafe(cursor).use { block(it) }
