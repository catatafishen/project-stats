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
    val sizeBytes: Long,
    val fileCount: Int = 1,
)

/**
 * A group (bucket) shown in the UI: one slice of the breakdown.
 */
data class StatGroup(
    val key: String,
    val totalLines: Long,
    val nonBlankLines: Long,
    val sizeBytes: Long,
    val fileCount: Long,
    val children: List<StatGroup> = emptyList(),
) {
    fun value(metric: Metric): Long = when (metric) {
        Metric.LOC -> totalLines
        Metric.NON_BLANK_LOC -> nonBlankLines
        Metric.SIZE -> sizeBytes
        Metric.FILE_COUNT -> fileCount
    }
}

enum class Metric(val display: String) {
    LOC("Total LOC"),
    NON_BLANK_LOC("Non-blank LOC"),
    SIZE("File size"),
    FILE_COUNT("File count");
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
    val sizeBytes: Long,
    val fileCount: Long,
    val scannedMillis: Long,
)
