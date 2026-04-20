package com.github.projectstats.coverage

import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/**
 * JaCoCo XML report parser.
 *
 * JaCoCo is the de-facto standard for JVM coverage. It produces line-level data per source file.
 * Files are reported by package name + sourcefilename — caller resolves them against source roots.
 *
 * Schema (relevant parts):
 * ```
 * <report>
 *   <package name="com/example/foo">
 *     <sourcefile name="MyClass.kt">
 *       <line nr="12" mi="0" ci="3"/>   <!-- mi = missed instructions, ci = covered instructions -->
 *       <counter type="LINE" missed="2" covered="8"/>
 * ```
 *
 * We use the `LINE` counter on each `sourcefile` for accurate covered/missed line counts (more
 * reliable than counting `<line>` elements, which use instruction granularity).
 *
 * Path keys are emitted as `package/sourcefile` (e.g. `com/example/foo/MyClass.kt`) so they can
 * be matched suffix-wise against `FileStat.relativePath`.
 */
object JacocoReader : CoverageReader {
    override val format: CoverageFormat = CoverageFormat.JACOCO

    override fun parse(text: String, sourceLabel: String): CoverageData? {
        if ("<report" !in text || "<sourcefile" !in text) return null
        val perFile = HashMap<String, FileCoverage>()
        val factory = SAXParserFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            // JaCoCo XML uses a DOCTYPE in older versions — accept it but disable external entities.
            try {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", false)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            } catch (_: Exception) {
                // best-effort hardening
            }
            isNamespaceAware = false
        }
        val parser = factory.newSAXParser()
        var currentPackage: String? = null
        var currentFile: String? = null
        try {
            parser.parse(text.byteInputStream(), object : DefaultHandler() {
                override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes) {
                    when (qName) {
                        "package" -> currentPackage = attrs.getValue("name")
                        "sourcefile" -> currentFile = attrs.getValue("name")
                        "counter" -> {
                            if (currentFile == null) return
                            if (attrs.getValue("type") != "LINE") return
                            val missed = attrs.getValue("missed")?.toIntOrNull() ?: return
                            val covered = attrs.getValue("covered")?.toIntOrNull() ?: return
                            val key = if (currentPackage.isNullOrBlank()) currentFile!!
                                      else "$currentPackage/$currentFile"
                            perFile[key] = FileCoverage(covered, covered + missed)
                        }
                    }
                }

                override fun endElement(uri: String?, localName: String?, qName: String) {
                    when (qName) {
                        "sourcefile" -> currentFile = null
                        "package" -> currentPackage = null
                    }
                }
            })
        } catch (_: Exception) {
            return null
        }
        if (perFile.isEmpty()) return null
        return CoverageData(format, sourceLabel, perFile)
    }
}
