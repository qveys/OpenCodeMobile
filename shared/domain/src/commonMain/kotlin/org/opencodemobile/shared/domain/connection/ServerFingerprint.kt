package org.opencodemobile.shared.domain.connection

/**
 * SHA-256 digest of the leaf certificate's SubjectPublicKeyInfo (SPKI).
 *
 * This is the value pinned per server profile by trust-on-first-use (TOFU)
 * verification (`docs/ARCHITECTURE.md` §"Server identity verification (T1)").
 * It mirrors the SSH host-key model: the identity is the public key, not the
 * enclosing certificate, so a server may rotate its certificate as long as it
 * keeps the same key pair.
 *
 * Equality is constant-shape content equality over the 32 digest bytes; the
 * class is immutable and copy-free from the caller's perspective.
 */
public class ServerFingerprint private constructor(
    private val bytes: ByteArray,
) {
    init {
        require(bytes.size == SHA256_LENGTH_BYTES) {
            "A SHA-256 fingerprint must be $SHA256_LENGTH_BYTES bytes, got ${bytes.size}"
        }
    }

    /** Defensive copy of the raw 32 digest bytes. */
    public val sha256: ByteArray
        get() = bytes.copyOf()

    /** Lowercase, unprefixed hex (64 characters). */
    public val hex: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        buildString(bytes.size * 2) {
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                append(HEX_DIGITS[v ushr 4])
                append(HEX_DIGITS[v and 0x0F])
            }
        }
    }

    /** Colon-separated hex, the convention used by SSH/TLS tooling for display. */
    public val colonSeparated: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        bytes.joinToString(separator = ":") { b ->
            val v = b.toInt() and 0xFF
            buildString(2) {
                append(HEX_DIGITS[v ushr 4])
                append(HEX_DIGITS[v and 0x0F])
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is ServerFingerprint && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = bytes.contentHashCode()

    /** Renders as colon-separated hex so logs/screens never hide the real value. */
    override fun toString(): String = colonSeparated

    public companion object {
        public const val SHA256_LENGTH_BYTES: Int = 32
        public const val HEX_LENGTH: Int = SHA256_LENGTH_BYTES * 2

        private const val HEX_DIGITS = "0123456789abcdef"

        /** Wraps raw SHA-256 digest bytes (copied). */
        public fun of(digest: ByteArray): ServerFingerprint = ServerFingerprint(digest.copyOf())

        /**
         * Parses a hex string, optionally colon-separated, as produced by
         * [colonSeparated] or by `openssl x509 -pubkey | openssl pkey -pubin
         * -outform der | openssl dgst -sha256`-style tooling.
         *
         * @throws IllegalArgumentException when the input is not a valid SHA-256 fingerprint.
         */
        public fun fromHex(hex: String): ServerFingerprint {
            val compact = hex.filter { it != ':' && !it.isWhitespace() }
            require(compact.length == HEX_LENGTH) {
                "Expected $HEX_LENGTH hex characters, got ${compact.length}"
            }
            val out = ByteArray(SHA256_LENGTH_BYTES)
            for (i in out.indices) {
                val high = compact[i * 2].digitToIntOrFail()
                val low = compact[i * 2 + 1].digitToIntOrFail()
                out[i] = ((high shl 4) or low).toByte()
            }
            return ServerFingerprint(out)
        }

        private fun Char.digitToIntOrFail(): Int {
            val v = when (this) {
                in '0'..'9' -> this - '0'
                in 'a'..'f' -> this - 'a' + 10
                in 'A'..'F' -> this - 'A' + 10
                else -> throw IllegalArgumentException("Invalid hex character '$this'")
            }
            return v
        }
    }
}