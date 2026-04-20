package com.github.projectstats.coverage

/**
 * Per-file coverage record. Counts are in *instrumentable* (executable) lines, not source lines.
 */
data class FileCoverage(val coveredLines: Int, val coverableLines: Int)

/**
 * Format produced by some test runner / coverage tool. We support the formats with the broadest
 * cross-language reach.
 */
enum class CoverageFormat(val display: String) {
    LCOV("LCOV"),
    COBERTURA("Cobertura XML"),
    JACOCO("JaCoCo XML"),
}

/**
 * Coverage data keyed by file path.
 *
 * Path keys may be absolute paths, project-relative paths, or partial suffix paths — the actual
 * matching against [com.github.projectstats.FileStat.relativePath] is done by [CoverageMatcher].
 */
data class CoverageData(
    val format: CoverageFormat,
    val sourceLabel: String,
    val perFile: Map<String, FileCoverage>,
)
