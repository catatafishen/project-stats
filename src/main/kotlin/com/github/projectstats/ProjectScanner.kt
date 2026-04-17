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
        var codeL = 0L
        var size = 0L

        val rootManager = ProjectRootManager.getInstance(project)
        val fileIndex = rootManager.fileIndex
        val projectBase: VirtualFile? = project.baseDir

        val roots = LinkedHashSet<VirtualFile>()
        roots.addAll(rootManager.contentRoots)
        if (projectBase != null) roots.add(projectBase)

        val seenPaths = HashSet<String>(4096)
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
                    if (!seenPaths.add(file.path)) return true
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
                    codeL += stat.codeLines
                    size += stat.sizeBytes
                    return true
                }
            })
        }

        return ScanResult(
            files = files,
            totalLines = totalLines,
            nonBlankLines = nonBlank,
            codeLines = codeL,
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
                codeLines = 0,
                sizeBytes = size,
            )
        }

        val fileType = file.fileType
        val isBinary = fileType.isBinary
        var total = 0
        var nonBlank = 0
        var codeL = 0
        if (!isBinary) {
            try {
                val bytes = file.contentsToByteArray(false)
                val charset = file.charset
                val text = String(bytes, charset)
                val ext = file.extension?.lowercase() ?: ""
                val style = COMMENT_STYLES[ext]
                var inBlock = false
                var lineStart = 0
                var i = 0
                while (i <= text.length) {
                    if (i == text.length || text[i] == '\n') {
                        total++
                        val lineEnd = if (i > lineStart && text[i - 1] == '\r') i - 1 else i
                        var hasContent = false
                        for (j in lineStart until lineEnd) {
                            val c = text[j]
                            if (c != ' ' && c != '\t') { hasContent = true; break }
                        }
                        if (hasContent) {
                            nonBlank++
                            if (style == null) {
                                codeL++
                            } else {
                                val (hasCode, newInBlock) = classifyLine(text, lineStart, lineEnd, inBlock, style)
                                inBlock = newInBlock
                                if (hasCode) codeL++
                            }
                        }
                        lineStart = i + 1
                    }
                    i++
                }
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
            codeLines = codeL,
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

    // ---- Comment detection ----

    private data class CommentStyle(
        val line: String? = null,
        val blockOpen: String? = null,
        val blockClose: String? = null,
    )

    private val COMMENT_STYLES = mapOf(
        // JVM / Kotlin / Groovy / Scala
        "java"   to CommentStyle("//", "/*", "*/"),
        "kt"     to CommentStyle("//", "/*", "*/"),
        "kts"    to CommentStyle("//", "/*", "*/"),
        "groovy" to CommentStyle("//", "/*", "*/"),
        "gradle" to CommentStyle("//", "/*", "*/"),
        "scala"  to CommentStyle("//", "/*", "*/"),
        "clj"    to CommentStyle(";"),
        // C / C++ / Objective-C
        "c"   to CommentStyle("//", "/*", "*/"),
        "h"   to CommentStyle("//", "/*", "*/"),
        "cpp" to CommentStyle("//", "/*", "*/"),
        "cc"  to CommentStyle("//", "/*", "*/"),
        "cxx" to CommentStyle("//", "/*", "*/"),
        "hpp" to CommentStyle("//", "/*", "*/"),
        "m"   to CommentStyle("//", "/*", "*/"),
        // Web / scripting
        "js"   to CommentStyle("//", "/*", "*/"),
        "jsx"  to CommentStyle("//", "/*", "*/"),
        "ts"   to CommentStyle("//", "/*", "*/"),
        "tsx"  to CommentStyle("//", "/*", "*/"),
        "mjs"  to CommentStyle("//", "/*", "*/"),
        "css"  to CommentStyle(null, "/*", "*/"),
        "scss" to CommentStyle("//", "/*", "*/"),
        "less" to CommentStyle("//", "/*", "*/"),
        // Systems languages
        "go"    to CommentStyle("//", "/*", "*/"),
        "rs"    to CommentStyle("//", "/*", "*/"),
        "swift" to CommentStyle("//", "/*", "*/"),
        "cs"    to CommentStyle("//", "/*", "*/"),
        "dart"  to CommentStyle("//", "/*", "*/"),
        "php"   to CommentStyle("//", "/*", "*/"),
        // Scripting / data
        "py"   to CommentStyle("#"),
        "rb"   to CommentStyle("#"),
        "sh"   to CommentStyle("#"),
        "bash" to CommentStyle("#"),
        "zsh"  to CommentStyle("#"),
        "fish" to CommentStyle("#"),
        "yaml" to CommentStyle("#"),
        "yml"  to CommentStyle("#"),
        "toml" to CommentStyle("#"),
        "r"    to CommentStyle("#"),
        "pl"   to CommentStyle("#"),
        "pm"   to CommentStyle("#"),
        "tf"   to CommentStyle("#", "/*", "*/"),
        // SQL / functional
        "sql" to CommentStyle("--", "/*", "*/"),
        "hs"  to CommentStyle("--", "{-", "-}"),
        "lua" to CommentStyle("--", "--[[", "]]"),
        // Markup
        "html"  to CommentStyle(null, "<!--", "-->"),
        "htm"   to CommentStyle(null, "<!--", "-->"),
        "xml"   to CommentStyle(null, "<!--", "-->"),
        "xhtml" to CommentStyle(null, "<!--", "-->"),
        "svg"   to CommentStyle(null, "<!--", "-->"),
    )

    /**
     * Scans one line of [text] from [lineStart] to [lineEnd] (exclusive) respecting comment syntax.
     * Returns whether the line contains any non-comment, non-whitespace code character
     * and the updated block-comment state after the line.
     */
    private fun classifyLine(
        text: String,
        lineStart: Int,
        lineEnd: Int,
        inBlockIn: Boolean,
        style: CommentStyle,
    ): Pair<Boolean, Boolean> {
        var hasCode = false
        var inBlock = inBlockIn
        var j = lineStart
        while (j < lineEnd) {
            if (inBlock) {
                val bc = style.blockClose
                if (bc != null && text.startsWith(bc, j)) {
                    inBlock = false
                    j += bc.length
                } else {
                    j++
                }
            } else {
                val bo = style.blockOpen
                val lc = style.line
                when {
                    bo != null && text.startsWith(bo, j) -> { inBlock = true; j += bo.length }
                    lc != null && text.startsWith(lc, j) -> break // rest of line is comment
                    else -> {
                        val c = text[j]
                        if (c != ' ' && c != '\t') hasCode = true
                        j++
                    }
                }
            }
        }
        return Pair(hasCode, inBlock)
    }
}
