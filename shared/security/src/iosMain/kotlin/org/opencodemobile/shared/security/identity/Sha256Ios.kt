package org.opencodemobile.shared.security.identity

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256

@OptIn(ExperimentalForeignApi::class)
internal actual fun sha256Digest(data: ByteArray): ByteArray {
    val output = ByteArray(32)
    data.usePinned { input ->
        output.usePinned { out ->
            CC_SHA256(input.addressOf(0), data.size.toUInt(), out.addressOf(0).reinterpret())
        }
    }
    return output
}