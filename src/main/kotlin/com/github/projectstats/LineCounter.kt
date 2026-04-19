package com.github.projectstats

/**
 * Pure (IntelliJ-free) line-counting and cyclomatic-complexity heuristics.
 *
 * Extracted from [ProjectScanner] so the text-scanning logic can be unit-tested
 * without spinning up an IDE test fixture. ProjectScanner still owns the VFS walk,
 * module classification, and PSI-based complexity — this object just classifies
 * a file's textual content given its extension.
 */
object LineCounter {

    data class LineStats(
        val total: Int,
        val nonBlank: Int,
        val code: Int,
        val complexity: Int,
    )

    data class CommentStyle(
        val line: String? = null,
        val blockOpen: String? = null,
        val blockClose: String? = null,
    )

    val COMMENT_STYLES: Map<String, CommentStyle> = mapOf(
        // JVM / Kotlin / Groovy / Scala
        "java" to CommentStyle("//", "/*", "*/"),
        "kt" to CommentStyle("//", "/*", "*/"),
        "kts" to CommentStyle("//", "/*", "*/"),
        "groovy" to CommentStyle("//", "/*", "*/"),
        "gradle" to CommentStyle("//", "/*", "*/"),
        "scala" to CommentStyle("//", "/*", "*/"),
        "clj" to CommentStyle(";"),
        // C / C++ / Objective-C
        "c" to CommentStyle("//", "/*", "*/"),
        "h" to CommentStyle("//", "/*", "*/"),
        "cpp" to CommentStyle("//", "/*", "*/"),
        "cc" to CommentStyle("//", "/*", "*/"),
        "cxx" to CommentStyle("//", "/*", "*/"),
        "hpp" to CommentStyle("//", "/*", "*/"),
        "m" to CommentStyle("//", "/*", "*/"),
        // Web / scripting
        "js" to CommentStyle("//", "/*", "*/"),
        "jsx" to CommentStyle("//", "/*", "*/"),
        "ts" to CommentStyle("//", "/*", "*/"),
        "tsx" to CommentStyle("//", "/*", "*/"),
        "mjs" to CommentStyle("//", "/*", "*/"),
        "css" to CommentStyle(null, "/*", "*/"),
        "scss" to CommentStyle("//", "/*", "*/"),
        "less" to CommentStyle("//", "/*", "*/"),
        // Systems languages
        "go" to CommentStyle("//", "/*", "*/"),
        "rs" to CommentStyle("//", "/*", "*/"),
        "swift" to CommentStyle("//", "/*", "*/"),
        "cs" to CommentStyle("//", "/*", "*/"),
        "dart" to CommentStyle("//", "/*", "*/"),
        "php" to CommentStyle("//", "/*", "*/"),
        // Scripting / data
        "py" to CommentStyle("#"),
        "rb" to CommentStyle("#"),
        "sh" to CommentStyle("#"),
        "bash" to CommentStyle("#"),
        "zsh" to CommentStyle("#"),
        "fish" to CommentStyle("#"),
        "yaml" to CommentStyle("#"),
        "yml" to CommentStyle("#"),
        "toml" to CommentStyle("#"),
        "r" to CommentStyle("#"),
        "pl" to CommentStyle("#"),
        "pm" to CommentStyle("#"),
        "tf" to CommentStyle("#", "/*", "*/"),
        // SQL / functional
        "sql" to CommentStyle("--", "/*", "*/"),
        "hs" to CommentStyle("--", "{-", "-}"),
        "lua" to CommentStyle("--", "--[[", "]]"),
        // Markup
        "html" to CommentStyle(null, "<!--", "-->"),
        "htm" to CommentStyle(null, "<!--", "-->"),
        "xml" to CommentStyle(null, "<!--", "-->"),
        "xhtml" to CommentStyle(null, "<!--", "-->"),
        "svg" to CommentStyle(null, "<!--", "-->"),
    )

    val DECISION_KEYWORDS: Map<String, Array<String>> = mapOf(
        // JVM / Kotlin / Groovy / Scala
        "java" to arrayOf("if", "for", "while", "do", "case", "catch"),
        "kt" to arrayOf("if", "for", "while", "do", "when", "catch"),
        "kts" to arrayOf("if", "for", "while", "do", "when", "catch"),
        "groovy" to arrayOf("if", "for", "while", "do", "case", "catch"),
        "gradle" to arrayOf("if", "for", "while", "do", "case", "catch"),
        "scala" to arrayOf("if", "for", "while", "do", "case", "catch", "match"),
        // C / C++ / Objective-C
        "c" to arrayOf("if", "for", "while", "do", "case", "catch"),
        "h" to arrayOf("if", "for", "while", "do", "case", "catch"),
        "cpp" to arrayOf("if", "for", "while", "do", "case", "catch"),
        "cc" to arrayOf("if", "for", "while", "do", "case", "catch"),
        "cxx" to arrayOf("if", "for", "while", "do", "case", "catch"),
        "hpp" to arrayOf("if", "for", "while", "do", "case", "catch"),
        "m" to arrayOf("if", "for", "while", "do", "case", "catch"),
        // Web / scripting
        "js" to arrayOf("if", "for", "while", "do", "case", "catch"),
        "jsx" to arrayOf("if", "for", "while", "do", "case", "catch"),
        "ts" to arrayOf("if", "for", "while", "do", "case", "catch"),
        "tsx" to arrayOf("if", "for", "while", "do", "case", "catch"),
        "mjs" to arrayOf("if", "for", "while", "do", "case", "catch"),
        // Systems
        "go" to arrayOf("if", "for", "switch", "case", "select"),
        "rs" to arrayOf("if", "for", "while", "loop", "match"),
        "swift" to arrayOf("if", "for", "while", "do", "case", "catch", "guard"),
        "cs" to arrayOf("if", "for", "foreach", "while", "do", "case", "catch"),
        "dart" to arrayOf("if", "for", "while", "do", "case", "catch"),
        "php" to arrayOf("if", "for", "foreach", "while", "do", "case", "catch"),
        // Scripting / data
        "py" to arrayOf("if", "elif", "for", "while", "except"),
        "rb" to arrayOf("if", "elsif", "unless", "for", "while", "rescue", "until"),
        "sh" to arrayOf("if", "elif", "for", "while", "case"),
        "bash" to arrayOf("if", "elif", "for", "while", "case"),
        "zsh" to arrayOf("if", "elif", "for", "while", "case"),
        // SQL / functional
        "sql" to arrayOf("case", "when", "if"),
        "lua" to arrayOf("if", "elseif", "for", "while", "repeat"),
        "r" to arrayOf("if", "for", "while"),
        "pl" to arrayOf("if", "elsif", "for", "foreach", "while", "unless", "until"),
        "pm" to arrayOf("if", "elsif", "for", "foreach", "while", "unless", "until"),
    )

    fun count(text: String, extension: String): LineStats {
        val ext = extension.lowercase()
        val style = COMMENT_STYLES[ext]
        val keywords = DECISION_KEYWORDS[ext]
        val codeBuffer = if (keywords != null) StringBuilder(128) else null
        var total = 0
        var nonBlank = 0
        var codeL = 0
        var complexity = 0
        var inBlock = false
        var lineStart = 0
        var i = 0
        while (i <= text.length) {
            if (i == text.length || text[i] == '\n') {
                total++
                val lineEnd = if (i > lineStart && text[i - 1] == '\r') i - 1 else i
                val delta = processLine(text, lineStart, lineEnd, style, keywords, codeBuffer, inBlock)
                nonBlank += delta.nonBlank
                codeL += delta.code
                complexity += delta.complexity
                inBlock = delta.inBlock
                lineStart = i + 1
            }
            i++
        }
        if (text.isNotEmpty() && text.last() == '\n') total = (total - 1).coerceAtLeast(0)
        return LineStats(total, nonBlank, codeL, complexity)
    }

    /** Per-line counts accumulated by [processLine]. */
    private data class LineDelta(val nonBlank: Int, val code: Int, val complexity: Int, val inBlock: Boolean)

    private fun processLine(
        text: String,
        lineStart: Int,
        lineEnd: Int,
        style: CommentStyle?,
        keywords: Array<String>?,
        codeBuffer: StringBuilder?,
        inBlock: Boolean,
    ): LineDelta {
        if (!hasNonWhitespace(text, lineStart, lineEnd)) return LineDelta(0, 0, 0, inBlock)
        if (style == null) {
            val cplx = if (keywords != null) countKeywordsInRange(text, lineStart, lineEnd, keywords) else 0
            return LineDelta(nonBlank = 1, code = 1, complexity = cplx, inBlock = inBlock)
        }
        codeBuffer?.setLength(0)
        val (hasCode, newInBlock) = classifyLine(text, lineStart, lineEnd, inBlock, style, codeBuffer)
        val cplx = if (hasCode && codeBuffer != null && keywords != null) {
            countKeywordsInBuffer(codeBuffer, keywords)
        } else 0
        return LineDelta(nonBlank = 1, code = if (hasCode) 1 else 0, complexity = cplx, inBlock = newInBlock)
    }

    private fun hasNonWhitespace(text: String, start: Int, end: Int): Boolean {
        for (j in start until end) {
            val c = text[j]
            if (c != ' ' && c != '\t') return true
        }
        return false
    }

    internal fun classifyLine(
        text: String,
        lineStart: Int,
        lineEnd: Int,
        inBlockIn: Boolean,
        style: CommentStyle,
        codeBuffer: StringBuilder? = null,
    ): Pair<Boolean, Boolean> {
        var hasCode = false
        var inBlock = inBlockIn
        var j = lineStart
        while (j < lineEnd) {
            if (inBlock) {
                val (next, stillInBlock) = advanceInBlock(text, j, style)
                j = next
                inBlock = stillInBlock
            } else {
                val step = advanceOutsideBlock(text, j, lineEnd, style, codeBuffer)
                if (step.isLineCommentStart) break
                if (step.enteredBlock) inBlock = true
                if (step.consumedCode) hasCode = true
                j = step.next
            }
        }
        return Pair(hasCode, inBlock)
    }

    private fun advanceInBlock(text: String, j: Int, style: CommentStyle): Pair<Int, Boolean> {
        val bc = style.blockClose
        return if (bc != null && text.startsWith(bc, j)) Pair(j + bc.length, false) else Pair(j + 1, true)
    }

    private data class OutsideStep(
        val next: Int,
        val enteredBlock: Boolean,
        val isLineCommentStart: Boolean,
        val consumedCode: Boolean,
    )

    private fun advanceOutsideBlock(
        text: String,
        j: Int,
        lineEnd: Int,
        style: CommentStyle,
        codeBuffer: StringBuilder?,
    ): OutsideStep {
        val bo = style.blockOpen
        if (bo != null && text.startsWith(bo, j)) {
            return OutsideStep(
                next = j + bo.length,
                enteredBlock = true,
                isLineCommentStart = false,
                consumedCode = false
            )
        }
        val lc = style.line
        if (lc != null && text.startsWith(lc, j)) {
            return OutsideStep(next = lineEnd, enteredBlock = false, isLineCommentStart = true, consumedCode = false)
        }
        val c = text[j]
        val isCode = c != ' ' && c != '\t'
        codeBuffer?.append(c)
        return OutsideStep(next = j + 1, enteredBlock = false, isLineCommentStart = false, consumedCode = isCode)
    }

    internal fun countKeywordsInRange(text: String, start: Int, end: Int, keywords: Array<String>): Int {
        var count = 0
        for (kw in keywords) {
            count += countOneKeywordInRange(text, start, end, kw)
        }
        return count
    }

    private fun countOneKeywordInRange(text: String, start: Int, end: Int, kw: String): Int {
        var count = 0
        var pos = start
        while (pos < end) {
            val idx = text.indexOf(kw, pos)
            if (idx == -1 || idx >= end) break
            if (isWordBoundaryAt(text, idx, kw.length)) count++
            pos = idx + 1
        }
        return count
    }

    private fun isWordBoundaryAt(text: String, idx: Int, len: Int): Boolean {
        val before = if (idx > 0) text[idx - 1] else ' '
        val after = if (idx + len < text.length) text[idx + len] else ' '
        return !before.isLetterOrDigit() && before != '_' && !after.isLetterOrDigit() && after != '_'
    }

    internal fun countKeywordsInBuffer(buf: StringBuilder, keywords: Array<String>): Int {
        val s = buf.toString()
        return countKeywordsInRange(s, 0, s.length, keywords)
    }
}
