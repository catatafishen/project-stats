package com.github.projectstats.coverage

/**
 * Parses a coverage report into [CoverageData]. Implementations are stateless and side-effect free
 * so they can be unit-tested without a [com.intellij.openapi.vfs.VirtualFile].
 */
interface CoverageReader {
    val format: CoverageFormat

    /** Parse the report's textual content. Returns null if the input is unrecognisable for this format. */
    fun parse(text: String, sourceLabel: String): CoverageData?
}
