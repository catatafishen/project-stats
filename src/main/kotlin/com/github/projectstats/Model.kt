package com.github.projectstats

/**
 * Classification of a single file in the project.
 */
enum class SourceCategory(val display: String) {
    SOURCE("Sources"),
    TEST("Tests"),
    RESOURCES("Resources"),
    TEST_RESOURCES("Test Resources"),
    GENERATED("Generated"),
    OTHER("Other");
}

/**
 * Per-file record aggregated later by different dimensions.
 */
data class FileStat(
    val relativePath: String,
    val language: String,
    val extension: String,
    val module: String,
    val category: SourceCategory,
    val totalLines: Int,
    val nonBlankLines: Int,
    val codeLines: Int,
    val complexity: Int,
    val sizeBytes: Long,
    val commitCount: Int = 0,
    val fileCount: Int = 1,
    /** Number of executable/instrumentable lines reported as covered. */
    val coveredLines: Int = 0,
    /** Number of executable/instrumentable lines in the file (covered + uncovered). 0 = no coverage data. */
    val coverableLines: Int = 0,
)

/**
 * A group (bucket) shown in the UI: one slice of the breakdown.
 */
data class StatGroup(
    val key: String,
    val totalLines: Long,
    val nonBlankLines: Long,
    val codeLines: Long,
    val complexity: Long,
    val sizeBytes: Long,
    val fileCount: Long,
    val commitCount: Long,
    val children: List<StatGroup> = emptyList(),
    val coveredLines: Long = 0,
    val coverableLines: Long = 0,
) {
    fun value(metric: Metric): Long = when (metric) {
        Metric.LOC -> totalLines
        Metric.NON_BLANK_LOC -> nonBlankLines
        Metric.CODE_LOC -> codeLines
        Metric.COMPLEXITY -> complexity
        Metric.SIZE -> sizeBytes
        Metric.FILE_COUNT -> fileCount
        Metric.COMMIT_COUNT -> commitCount
        Metric.COVERED_LOC -> coveredLines
        Metric.UNCOVERED_LOC -> (coverableLines - coveredLines).coerceAtLeast(0)
    }

    /** Coverage as a fraction in [0, 1], or null if the group has no coverage data. */
    fun coverageFraction(): Double? {
        if (coverableLines <= 0) return null
        return coveredLines.toDouble() / coverableLines.toDouble()
    }
}

enum class Metric(val display: String) {
    LOC("Total LOC"),
    NON_BLANK_LOC("Non-blank LOC"),
    CODE_LOC("Code LOC"),
    COMPLEXITY("Complexity"),
    SIZE("File size"),
    FILE_COUNT("File count"),
    COMMIT_COUNT("Commits"),
    COVERED_LOC("Covered LOC"),
    UNCOVERED_LOC("Uncovered LOC");

    override fun toString() = display
}

enum class GroupBy(val display: String) {
    LANGUAGE("Language"),
    MODULE("Module"),
    CATEGORY("Source category"),
    DIRECTORY("Directory tree");

    override fun toString() = display
}

/**
 * Full scan result; filter/group transformations are applied by [StatsAggregator].
 */
data class ScanResult(
    val files: List<FileStat>,
    val totalLines: Long,
    val nonBlankLines: Long,
    val codeLines: Long,
    val complexity: Long,
    val sizeBytes: Long,
    val fileCount: Long,
    val commitCount: Long,
    val scannedMillis: Long,
    val coveredLines: Long = 0,
    val coverableLines: Long = 0,
    /** Description of where coverage data came from (e.g. "lcov.info"), or null if none was loaded. */
    val coverageSource: String? = null,
)
