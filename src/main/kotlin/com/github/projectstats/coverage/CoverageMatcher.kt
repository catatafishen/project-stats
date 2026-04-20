package com.github.projectstats.coverage

/**
 * Resolves coverage entries to project files.
 *
 * Coverage tools are inconsistent about the path format they emit:
 *  - LCOV may use absolute paths, paths relative to the test runner CWD, or just bare filenames.
 *  - Cobertura uses paths relative to a `<source>` root configured at report-generation time.
 *  - JaCoCo uses `package/sourcefile` (e.g. `com/example/Foo.kt`), with no source-root prefix.
 *
 * Strategy: index coverage entries by all their path suffixes; for each project file, look up
 * the longest suffix that matches. This is O(N + M) preprocessing + O(depth) per lookup, and
 * tolerates all three path styles above.
 */
class CoverageMatcher(private val data: CoverageData) {

    /** suffix -> covered/coverable, where suffix is "tail/of/path". Stored normalised with '/'. */
    private val suffixIndex: Map<String, FileCoverage>

    init {
        // For each coverage entry path, register all its suffixes (full path, last 2 segments, …, last 1).
        // When two coverage entries share a suffix, we keep the one with more coverable lines (larger /
        // more authoritative), which protects against tiny duplicate <class> entries that some tools emit.
        val acc = HashMap<String, FileCoverage>(data.perFile.size * 2)
        for ((rawPath, cov) in data.perFile) {
            val parts = rawPath.replace('\\', '/').trim('/').split('/').filter { it.isNotEmpty() }
            if (parts.isEmpty()) continue
            for (i in parts.indices) {
                val suffix = parts.subList(i, parts.size).joinToString("/")
                val prev = acc[suffix]
                if (prev == null || cov.coverableLines > prev.coverableLines) {
                    acc[suffix] = cov
                }
            }
        }
        suffixIndex = acc
    }

    /**
     * Look up coverage for [relativePath]. Returns null if no entry matches.
     *
     * Tries the full normalised path first (cheapest), then progressively shorter suffixes. This
     * yields the *longest* matching suffix, which minimises false positives on common filenames
     * like `index.js` or `__init__.py`.
     */
    fun match(relativePath: String): FileCoverage? {
        val parts = relativePath.replace('\\', '/').trim('/').split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        for (i in parts.indices) {
            val suffix = parts.subList(i, parts.size).joinToString("/")
            val hit = suffixIndex[suffix]
            if (hit != null) return hit
        }
        return null
    }
}
