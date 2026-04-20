package com.github.projectstats.coverage

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor

/**
 * Auto-discovery + parsing for coverage reports anywhere under the project base.
 *
 * Discovery rules (first match wins, in this order):
 *  1. **LCOV** — `lcov.info`, `coverage/lcov.info`, `coverage.lcov`
 *  2. **Cobertura XML** — `coverage.xml`, `cobertura-coverage.xml`, `coverage/cobertura-coverage.xml`
 *  3. **JaCoCo XML** — anywhere under `build/reports/jacoco/` or `target/site/jacoco/jacoco.xml`
 *
 * If multiple JaCoCo reports exist (per-module aggregation), they are merged.
 *
 * Paths under `node_modules`, `.git`, and other build/VCS dirs are skipped to keep the walk fast.
 */
object CoverageLoader {

    private val LOG = logger<CoverageLoader>()

    private val LCOV_NAMES = setOf("lcov.info", "coverage.lcov")
    private val COBERTURA_NAMES = setOf("cobertura-coverage.xml", "coverage.xml")
    private val SKIPPED_DIRS = setOf(".git", ".hg", ".idea", "node_modules", ".gradle", "dist")

    /**
     * Scan [projectBase] for a coverage report. Returns merged coverage data, or null if nothing is found.
     */
    fun load(projectBase: VirtualFile, indicator: ProgressIndicator?): CoverageData? {
        indicator?.text2 = "Looking for coverage reports"
        val lcov = ArrayList<VirtualFile>(2)
        val cobertura = ArrayList<VirtualFile>(2)
        val jacoco = ArrayList<VirtualFile>(8)

        VfsUtilCore.visitChildrenRecursively(projectBase, object : VirtualFileVisitor<Any?>() {
            override fun visitFile(file: VirtualFile): Boolean {
                if (file.isDirectory) {
                    return file.name !in SKIPPED_DIRS
                }
                val name = file.name
                val nameLower = name.lowercase()
                when {
                    nameLower in LCOV_NAMES -> lcov.add(file)
                    nameLower in COBERTURA_NAMES -> cobertura.add(file)
                    nameLower.startsWith("jacoco") && nameLower.endsWith(".xml") -> jacoco.add(file)
                }
                return true
            }
        })

        // LCOV first (broadest coverage of languages), then Cobertura, then JaCoCo.
        lcov.firstOrNull()?.let { return readSafely(it, LcovReader) }
        cobertura.firstOrNull()?.let { return readSafely(it, CoberturaReader) }
        if (jacoco.isNotEmpty()) {
            return readJacocoMerged(jacoco)
        }
        return null
    }

    private fun readSafely(file: VirtualFile, reader: CoverageReader): CoverageData? {
        return try {
            val text = String(file.contentsToByteArray(), file.charset)
            reader.parse(text, file.name)
        } catch (e: Exception) {
            LOG.warn("Failed to read ${reader.format} report at ${file.path}: ${e.message}")
            null
        }
    }

    private fun readJacocoMerged(files: List<VirtualFile>): CoverageData? {
        val merged = HashMap<String, FileCoverage>()
        var anyOk = false
        for (f in files) {
            val data = readSafely(f, JacocoReader) ?: continue
            anyOk = true
            for ((path, cov) in data.perFile) {
                val prev = merged[path]
                merged[path] = if (prev == null) cov
                else FileCoverage(prev.coveredLines + cov.coveredLines, prev.coverableLines + cov.coverableLines)
            }
        }
        if (!anyOk || merged.isEmpty()) return null
        val label = if (files.size == 1) files[0].name else "${files.size} JaCoCo reports"
        return CoverageData(CoverageFormat.JACOCO, label, merged)
    }
}
