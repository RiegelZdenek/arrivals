package cz.riegel.arrivals.domain

object ReleaseVersion {
    /**
     * Calculates the monotonic integer versionCode from SemVer components:
     * (MAJOR * 10000) + (MINOR * 100) + PATCH
     */
    fun calculateVersionCode(major: Int, minor: Int, patch: Int): Int {
        require(major >= 0) { "Major version must be non-negative: $major" }
        require(minor in 0..99) { "Minor version must be between 0 and 99: $minor" }
        require(patch in 0..99) { "Patch version must be between 0 and 99: $patch" }
        return (major * 10000) + (minor * 100) + patch
    }

    /**
     * Parses a SemVer tag (e.g. "v1.2.3" or "1.2.3") into (major, minor, patch).
     */
    fun parseSemVer(tag: String): Triple<Int, Int, Int> {
        val cleanTag = tag.trim().removePrefix("v")
        val regex = Regex("""^(\d+)\.(\d+)\.(\d+)$""")
        val match = regex.matchEntire(cleanTag)
            ?: throw IllegalArgumentException("Invalid SemVer tag: '$tag'. Expected format: [v]MAJOR.MINOR.PATCH")
        val (major, minor, patch) = match.destructured
        return Triple(major.toInt(), minor.toInt(), patch.toInt())
    }

    /**
     * Derives (versionName, versionCode) from a tag.
     */
    fun deriveVersionMetadata(tag: String): Pair<String, Int> {
        val (major, minor, patch) = parseSemVer(tag)
        val versionName = "$major.$minor.$patch"
        val versionCode = calculateVersionCode(major, minor, patch)
        return Pair(versionName, versionCode)
    }
}
