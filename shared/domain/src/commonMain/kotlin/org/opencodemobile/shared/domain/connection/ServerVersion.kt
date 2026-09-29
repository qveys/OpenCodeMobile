package org.opencodemobile.shared.domain.connection

/**
 * A parsed OpenCode Server version string (`major.minor.patch`).
 *
 * The server reports its version as a string in the handshake
 * (`GET /global/health` → `version`); this type is the parsed form used by the
 * [CompatibilityProfile] gate (`docs/ARCHITECTURE.md` §4.4). Parsing is
 * deliberately strict: [parseOrNull] returns null for anything that is not a
 * dotted numeric version, which the handshake maps to an incomplete-handshake
 * failure rather than guessing.
 */
public data class ServerVersion(
    public val major: Int,
    public val minor: Int,
    public val patch: Int,
) : Comparable<ServerVersion> {

    init {
        require(major >= 0 && minor >= 0 && patch >= 0) {
            "ServerVersion components must not be negative: $major.$minor.$patch"
        }
    }

    override fun compareTo(other: ServerVersion): Int {
        val byMajor = major.compareTo(other.major)
        if (byMajor != 0) return byMajor
        val byMinor = minor.compareTo(other.minor)
        if (byMinor != 0) return byMinor
        return patch.compareTo(other.patch)
    }

    override fun toString(): String = "$major.$minor.$patch"

    public companion object {
        /**
         * Parses `major`, `major.minor`, or `major.minor.patch`, ignoring
         * surrounding whitespace. Missing components default to `0`.
         *
         * Returns null when [raw] is blank, has more than three components,
         * or has a non-numeric component. A null result is an incomplete
         * handshake, never an implicit "compatible".
         */
        public fun parseOrNull(raw: String): ServerVersion? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null

            val parts = trimmed.split('.')
            if (parts.size > 3) return null

            val numbers = ArrayList<Int>(parts.size)
            for (part in parts) {
                if (part.isEmpty() || !part.all { it.isDigit() }) return null
                numbers.add(part.toInt())
            }

            return ServerVersion(
                major = numbers[0],
                minor = numbers.getOrElse(1) { 0 },
                patch = numbers.getOrElse(2) { 0 },
            )
        }
    }
}
