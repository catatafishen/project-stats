package com.github.projectstats.coverage

/**
 * LCOV `.info` parser.
 *
 * LCOV is the most portable coverage format. It is emitted by Istanbul (JS/TS), c8, Vitest,
 * Jest, Karma, gcov, llvm-cov, gcov2lcov (Go), grcov (Rust), Swift Package Manager, etc.
 *
 * The format is line-oriented; we only need:
 *  - `SF:<file path>`  — start of a per-file record
 *  - `DA:<line>,<hits>` — line execution count (hits == 0 → uncovered)
 *  - `end_of_record`   — terminates a per-file record
 *
 * `LF:` (lines found) and `LH:` (lines hit) are summary fields that we recompute from `DA`
 * to be tolerant of off-by-one errors in malformed reports.
 */
object LcovReader : CoverageReader {
    override val format: CoverageFormat = CoverageFormat.LCOV

    override fun parse(text: String, sourceLabel: String): CoverageData? {
        if ("SF:" !in text) return null
        val perFile = HashMap<String, FileCoverage>()
        var currentFile: String? = null
        var covered = 0
        var coverable = 0
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            when {
                line.startsWith("SF:") -> {
                    currentFile = line.substring(3).trim()
                    covered = 0
                    coverable = 0
                }
                line.startsWith("DA:") -> {
                    val payload = line.substring(3)
                    val comma = payload.indexOf(',')
                    if (comma > 0) {
                        val hits = payload.substring(comma + 1).substringBefore(',').toIntOrNull() ?: 0
                        coverable++
                        if (hits > 0) covered++
                    }
                }
                line == "end_of_record" -> {
                    val f = currentFile
                    if (f != null && coverable > 0) {
                        // Merge if the same file appears more than once.
                        val prev = perFile[f]
                        perFile[f] = if (prev == null) FileCoverage(covered, coverable)
                        else FileCoverage(prev.coveredLines + covered, prev.coverableLines + coverable)
                    }
                    currentFile = null
                    covered = 0
                    coverable = 0
                }
            }
        }
        if (perFile.isEmpty()) return null
        return CoverageData(format, sourceLabel, perFile)
    }
}
