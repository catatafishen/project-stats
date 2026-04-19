package com.github.projectstats

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.GeneratedSourcesFilter
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import org.jetbrains.jps.model.java.JavaResourceRootType
import org.jetbrains.jps.model.java.JavaSourceRootType
import org.jetbrains.jps.model.module.JpsModuleSourceRootType
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object ProjectScanner {

    private const val MAX_FILE_BYTES = 4L * 1024 * 1024 // skip files larger than 4 MiB

    /** Common build-output and VCS directories — skipped early to keep VFS walks fast. */
    private val SKIPPED_DIRS = setOf(
        ".git", ".hg", ".idea", "node_modules", "build", "out", "target", ".gradle", "dist"
    )

    fun scan(project: Project, indicator: ProgressIndicator?): ScanResult {
        val started = System.currentTimeMillis()

        indicator?.text = "Reading git history"
        val commitCounts: Map<String, Int> = GitCommitCountCalculator.compute(project, indicator)

        indicator?.text = "Scanning project files"

        val rootManager = ProjectRootManager.getInstance(project)
        val fileIndex = rootManager.fileIndex
        val projectBase: VirtualFile? = project.guessProjectDir()

        val roots = LinkedHashSet<VirtualFile>()
        roots.addAll(rootManager.contentRoots)
        if (projectBase != null) roots.add(projectBase)

        val toProcess = collectFiles(roots, indicator)
        val files = classifyInParallel(toProcess, project, fileIndex, projectBase, commitCounts, indicator)

        var totalLines = 0L
        var nonBlank = 0L
        var codeL = 0L
        var complexityTotal = 0L
        var size = 0L
        var commitsTotal = 0L
        for (stat in files) {
            totalLines += stat.totalLines
            nonBlank += stat.nonBlankLines
            codeL += stat.codeLines
            complexityTotal += stat.complexity
            size += stat.sizeBytes
            commitsTotal += stat.commitCount
        }

        return ScanResult(
            files = files,
            totalLines = totalLines,
            nonBlankLines = nonBlank,
            codeLines = codeL,
            complexity = complexityTotal,
            sizeBytes = size,
            fileCount = files.size.toLong(),
            commitCount = commitsTotal,
            scannedMillis = System.currentTimeMillis() - started,
        )
    }

    /** Phase 1: walk VFS (fast, sequential) to collect candidate files, skipping known build/VCS dirs. */
    private fun collectFiles(roots: Set<VirtualFile>, indicator: ProgressIndicator?): List<VirtualFile> {
        val toProcess = ArrayList<VirtualFile>(4096)
        val seenPaths = HashSet<String>(4096)
        for (root in roots) {
            indicator?.checkCanceled()
            VfsUtilCore.visitChildrenRecursively(root, object : VirtualFileVisitor<Any?>() {
                override fun visitFile(file: VirtualFile): Boolean {
                    indicator?.checkCanceled()
                    if (file.isDirectory) return file.name !in SKIPPED_DIRS
                    if (seenPaths.add(file.path)) toProcess += file
                    return true
                }
            })
        }
        return toProcess
    }

    /** Phase 2: classify in parallel. ReadAction is a shared read lock — multiple workers OK. */
    private fun classifyInParallel(
        toProcess: List<VirtualFile>,
        project: Project,
        fileIndex: ProjectFileIndex,
        projectBase: VirtualFile?,
        commitCounts: Map<String, Int>,
        indicator: ProgressIndicator?,
    ): List<FileStat> {
        val parallelism = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val executor = Executors.newFixedThreadPool(parallelism) { r ->
            Thread(r, "codescape-scanner").apply { isDaemon = true }
        }
        val files = ArrayList<FileStat>(toProcess.size)
        val progressStep = (toProcess.size / 100).coerceAtLeast(50)
        try {
            val futures = toProcess.map { file ->
                executor.submit(Callable {
                    ReadAction.compute<FileStat?, RuntimeException> {
                        classify(file, project, fileIndex, projectBase)
                    }?.let { base ->
                        if (commitCounts.isEmpty()) base
                        else base.copy(commitCount = commitCounts[file.path] ?: 0)
                    }
                })
            }
            for ((idx, f) in futures.withIndex()) {
                indicator?.checkCanceled()
                val stat = awaitFuture(f)
                if ((idx + 1) % progressStep == 0) {
                    indicator?.text2 = "Scanning ${toProcess[idx].presentableUrl}"
                }
                if (stat != null) files += stat
            }
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(2, TimeUnit.SECONDS)
        }
        return files
    }

    private fun <T> awaitFuture(f: java.util.concurrent.Future<T>): T = try {
        f.get()
    } catch (e: java.util.concurrent.ExecutionException) {
        val cause = e.cause
        if (cause is RuntimeException) throw cause
        throw RuntimeException(cause ?: e)
    }

    private fun classify(
        file: VirtualFile,
        project: Project,
        fileIndex: ProjectFileIndex,
        projectBase: VirtualFile?,
    ): FileStat? {
        if (!file.isValid || file.isDirectory) return null
        if (fileIndex.isExcluded(file)) return null
        // Ignore files that are in libraries (JARs, SDK sources)
        if (fileIndex.isInLibrary(file)) return null

        val size = file.length
        if (size <= 0) return null
        val ext = file.extension?.lowercase().orEmpty()
        if (size > MAX_FILE_BYTES) {
            // still count its size/file but skip LOC
            return FileStat(
                relativePath = relPath(file, projectBase),
                language = languageName(file.fileType),
                extension = ext,
                module = moduleName(file, fileIndex),
                category = categorize(file, fileIndex),
                totalLines = 0,
                nonBlankLines = 0,
                codeLines = 0,
                complexity = 0,
                sizeBytes = size,
            )
        }

        val fileType = file.fileType
        val isBinary = fileType.isBinary
        var total = 0
        var nonBlank = 0
        var codeL = 0
        var complexity = 0
        if (!isBinary) {
            try {
                val bytes = file.contentsToByteArray(false)
                val text = String(bytes, file.charset)
                val stats = LineCounter.count(text, ext)
                total = stats.total
                nonBlank = stats.nonBlank
                codeL = stats.code
                complexity = stats.complexity
            } catch (_: Throwable) {
                // ignore unreadable files; keep size only
            }
        }

        // PSI-based complexity is more accurate for Java/Kotlin (handles operators, no false positives
        // from string literals). Falls back to the keyword count already computed above for other languages.
        // Restrict to extensions we actually treat as code to avoid parsing JSON/XML/YAML/MD trees that
        // yield no branches but still cost CPU. Binary files are also skipped.
        if (!isBinary && LineCounter.DECISION_KEYWORDS.containsKey(ext)) {
            PsiComplexityCalculator.calculate(file, project)?.let { complexity = it }
        }

        return FileStat(
            relativePath = relPath(file, projectBase),
            language = languageName(fileType),
            extension = ext,
            module = moduleName(file, fileIndex),
            category = categorize(file, fileIndex),
            totalLines = total,
            nonBlankLines = nonBlank,
            codeLines = codeL,
            complexity = complexity,
            sizeBytes = size,
        )
    }

    private fun relPath(file: VirtualFile, base: VirtualFile?): String {
        if (base == null) return file.path
        val rel = VfsUtilCore.getRelativePath(file, base, '/')
        return rel ?: file.path
    }

    private fun languageName(fileType: FileType): String {
        val n = fileType.name
        return when {
            n.equals("PLAIN_TEXT", true) -> "Text"
            n.isBlank() -> "Other"
            else -> n
        }
    }

    private fun moduleName(file: VirtualFile, idx: ProjectFileIndex): String {
        return idx.getModuleForFile(file)?.name ?: "<no module>"
    }

    private fun categorize(file: VirtualFile, idx: ProjectFileIndex): SourceCategory {
        // Generated sources: ask the platform extension point. Use the project from module if available.
        val module = idx.getModuleForFile(file)
        if (module != null && GeneratedSourcesFilter.isGeneratedSourceByAnyFilter(file, module.project)) {
            return SourceCategory.GENERATED
        }
        val rootType: JpsModuleSourceRootType<*>? = idx.getContainingSourceRootType(file)
        return when (rootType) {
            JavaSourceRootType.SOURCE -> SourceCategory.SOURCE
            JavaSourceRootType.TEST_SOURCE -> SourceCategory.TEST
            JavaResourceRootType.RESOURCE -> SourceCategory.RESOURCES
            JavaResourceRootType.TEST_RESOURCE -> SourceCategory.TEST_RESOURCES
            null -> categorizeByPath(file)
            else -> SourceCategory.SOURCE
        }
    }

    /** Heuristic categorization for files not under a configured source root. */
    private fun categorizeByPath(file: VirtualFile): SourceCategory {
        val path = file.path.lowercase()
        return when {
            "/test/" in path || path.endsWith("/test") || "/tests/" in path -> SourceCategory.TEST
            "/generated/" in path || "/gen/" in path || "/build/" in path || "/out/" in path -> SourceCategory.GENERATED
            "/resources/" in path -> SourceCategory.RESOURCES
            else -> SourceCategory.OTHER
        }
    }
}
