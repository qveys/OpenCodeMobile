package org.opencodemobile.shared.security.identity

import java.security.MessageDigest

internal actual fun sha256Digest(data: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(data)