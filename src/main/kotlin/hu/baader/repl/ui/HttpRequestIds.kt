package hu.baader.repl.ui

/** Stable identifiers shared by request creation, copying and renaming. */
internal object HttpRequestIds {
    private val invalidCharacters = "[^a-z0-9-]+".toRegex()
    private val repeatedHyphens = "-+".toRegex()

    fun unique(preferred: String, occupied: Collection<String>): String {
        val base = preferred.lowercase()
            .replace(invalidCharacters, "-")
            .replace(repeatedHyphens, "-")
            .trim('-')
            .ifBlank { "http-case" }
        val existing = occupied.toSet()
        if (base !in existing) return base
        var suffix = 1
        while ("$base-$suffix" in existing) suffix++
        return "$base-$suffix"
    }
}
