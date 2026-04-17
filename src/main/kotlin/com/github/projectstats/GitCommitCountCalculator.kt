package com.github.projectstats

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Counts, per file, how many commits reachable from HEAD touched it.
 *
 * Strategy: one `git log --pretty=format: --name-only` invocation per distinct git
 * repository root found among the project's content roots. Output lists every file
 * touched by every commit (one line per file per commit); we simply tally occurrences.
 *
 * Fails gracefully when `git` is missing, the project is not a git repository, or the
 * subprocess errors — the metric returns 0 for every file in those cases.
 */
object GitCommitCountCalculator {

    private val LOG = Logger.getInstance(GitCommitCountCalculator::class.java)
    private const val PROCESS_TIMEOUT_SECONDS = 120L

    fun compute(project: Project, indicator: ProgressIndicator?): Map<String, Int> {
        val roots = discoverGitRoots(project)
        if (roots.isEmpty()) return emptyMap()
        val result = HashMap<String, Int>(4096)
        for (root in roots) {
            indicator?.checkCanceled()
            indicator?.text2 = "Reading git history for $root"
            try {
                readGitLog(root, result, indicator)
            } catch (e: Throwable) {
                if (e is InterruptedException) throw e
                LOG.info("git log failed for $root: ${e.message}")
            }
        }
        return result
    }

    private fun discoverGitRoots(project: Project): List<String> {
        val rootManager = ProjectRootManager.getInstance(project)
        val candidates = LinkedHashSet<String>()
        project.basePath?.let { candidates += it }
        for (cr in rootManager.contentRoots) candidates += cr.path
        val roots = LinkedHashSet<String>()
        for (c in candidates) findGitRoot(c)?.let { roots += it }
        return roots.toList()
    }

    private fun findGitRoot(startPath: String): String? {
        var dir: File? = File(startPath)
        while (dir != null) {
            // `.git` is a directory in regular repos and a file in worktrees/submodules.
            if (File(dir, ".git").exists()) return dir.absolutePath.replace('\\', '/')
            dir = dir.parentFile
        }
        return null
    }

    private fun readGitLog(
        repoRoot: String,
        accum: MutableMap<String, Int>,
        indicator: ProgressIndicator?,
    ) {
        val pb = ProcessBuilder(
            "git",
            "-c", "core.quotePath=false",
            "-C", repoRoot,
            "log",
            "--pretty=format:",
            "--name-only",
            "HEAD",
        )
        pb.redirectErrorStream(false)
        val proc: Process = try {
            pb.start()
        } catch (e: IOException) {
            LOG.info("Could not start git for $repoRoot: ${e.message}")
            return
        }
        try {
            proc.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                var lines = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isNotEmpty()) {
                        val abs = "$repoRoot/$line"
                        accum.merge(abs, 1, Int::plus)
                    }
                    if (++lines and 0x3FFF == 0) indicator?.checkCanceled()
                }
            }
            if (!proc.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                LOG.info("git log timed out for $repoRoot after ${PROCESS_TIMEOUT_SECONDS}s")
            }
        } finally {
            if (proc.isAlive) proc.destroyForcibly()
        }
    }
}
