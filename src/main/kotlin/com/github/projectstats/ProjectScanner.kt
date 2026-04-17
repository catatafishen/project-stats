package com.github.projectstats

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.GeneratedSourcesFilter
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import org.jetbrains.jps.model.java.JavaResourceRootType
import org.jetbrains.jps.model.java.JavaSourceRootType
import org.jetbrains.jps.model.module.JpsModuleSourceRootType

object ProjectScanner {

    private const val MAX_FILE_BYTES = 4L * 1024 * 1024 // skip files larger than 4 MiB

    fun scan(project: Project, indicator: ProgressIndicator?): ScanResult {
        val started = System.currentTimeMillis()
        val files = ArrayList<FileStat>(4096)
        var totalLines = 0L
        var nonBlank = 0L
        var size = 0L

        val rootManager = ProjectRootManager.getInstance(project)
        val fileIndex = rootManager.fileIndex
        val projectBase: VirtualFile? = project.baseDir

        val roots = LinkedHashSet<VirtualFile>()
        roots.addAll(rootManager.contentRoots)
        if (projectBase != null) roots.add(projectBase)

        var visited = 0
        for (root in roots) {
            indicator?.checkCanceled()
            VfsUtilCore.visitChildrenRecursively(root, object : VirtualFileVisitor<Any?>() {
                override fun visitFile(file: VirtualFile): Boolean {
                    indicator?.checkCanceled()
                    if (file.isDirectory) {
                        val name = file.name
                        // Skip common build output / VCS dirs early for speed
                        if (name == ".git" || name == ".hg" || name == ".idea" ||
                            name == "node_modules" || name == "build" || name == "out" ||
                            name == "target" || name == ".gradle" || name == "dist"
                        ) return false
                        return true
                    }
                    visited++
                    if (visited % 200 == 0) {
                        indicator?.text2 = "Scanning ${file.presentableUrl}"
                    }
                    val stat = ReadAction.compute<FileStat?, RuntimeException> {
                        classify(file, project, fileIndex, projectBase)
                    } ?: return true
                    files += stat
                    totalLines += stat.totalLines
                    nonBlank += stat.nonBlankLines
                    size += stat.sizeBytes
                    return true
                }
            })
        }

        return ScanResult(
            files = files,
            totalLines = totalLines,
            nonBlankLines = nonBlank,
            sizeBytes = size,
            fileCount = files.size.toLong(),
            scannedMillis = System.currentTimeMillis() - started,
        )
    }

    private fun classify(
        file: VirtualFile,
        @Suppress("UNUSED_PARAMETER") project: Project,
        fileIndex: ProjectFileIndex,
        projectBase: VirtualFile?,
    ): FileStat? {
        if (!file.isValid || file.isDirectory) return null
        if (fileIndex.isExcluded(file)) return null
        // Ignore files that are in libraries (JARs, SDK sources)
        if (fileIndex.isInLibrary(file)) return null

        val size = file.length
        if (size <= 0) return null
        if (size > MAX_FILE_BYTES) {
            // still count its size/file but skip LOC
            return FileStat(
                relativePath = relPath(file, projectBase),
                language = languageName(file.fileType),
                extension = file.extension?.lowercase() ?: "",
                module = moduleName(file, fileIndex),
                category = categorize(file, fileIndex),
                totalLines = 0,
                nonBlankLines = 0,
                sizeBytes = size,
            )
        }

        val fileType = file.fileType
        val isBinary = fileType.isBinary
        var total = 0
        var nonBlank = 0
        if (!isBinary) {
            try {
                val bytes = file.contentsToByteArray(false)
                // Use file's detected charset, falling back to UTF-8
                val charset = file.charset
                val text = String(bytes, charset)
                var lineStart = 0
                var i = 0
                while (i <= text.length) {
                    if (i == text.length || text[i] == '\n') {
                        total++
                        var blank = true
                        for (j in lineStart until i) {
                            val c = text[j]
                            if (c != ' ' && c != '\t' && c != '\r') { blank = false; break }
                        }
                        if (!blank) nonBlank++
                        lineStart = i + 1
                    }
                    i++
                }
                // Trailing newline shouldn't count as an extra empty line
                if (text.isNotEmpty() && text.last() == '\n') total = (total - 1).coerceAtLeast(0)
            } catch (_: Throwable) {
                // ignore unreadable files; keep size only
            }
        }

        return FileStat(
            relativePath = relPath(file, projectBase),
            language = languageName(fileType),
            extension = file.extension?.lowercase() ?: "",
            module = moduleName(file, fileIndex),
            category = categorize(file, fileIndex),
            totalLines = total,
            nonBlankLines = nonBlank,
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
        if (module != null) {
            if (GeneratedSourcesFilter.isGeneratedSourceByAnyFilter(file, module.project)) return SourceCategory.GENERATED
        }
        val rootType: JpsModuleSourceRootType<*>? = idx.getContainingSourceRootType(file)
        return when (rootType) {
            JavaSourceRootType.SOURCE -> SourceCategory.SOURCE
            JavaSourceRootType.TEST_SOURCE -> SourceCategory.TEST
            JavaResourceRootType.RESOURCE -> SourceCategory.RESOURCES
            JavaResourceRootType.TEST_RESOURCE -> SourceCategory.TEST_RESOURCES
            null -> {
                // Not under a configured source root: heuristic by path
                val path = file.path.lowercase()
                when {
                    "/test/" in path || path.endsWith("/test") || "/tests/" in path -> SourceCategory.TEST
                    "/generated/" in path || "/gen/" in path || "/build/" in path || "/out/" in path -> SourceCategory.GENERATED
                    "/resources/" in path -> SourceCategory.RESOURCES
                    else -> SourceCategory.OTHER
                }
            }
            else -> SourceCategory.SOURCE
        }
    }
}
