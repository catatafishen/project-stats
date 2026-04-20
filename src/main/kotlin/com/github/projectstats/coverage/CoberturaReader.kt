package com.github.projectstats.coverage

import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/**
 * Cobertura XML parser.
 *
 * Cobertura is the format produced by `coverage.py` (Python), .NET (`coverlet`), PHPUnit,
 * Karma's cobertura reporter, and many JVM Gradle/Maven coverage plugins.
 *
 * Schema (relevant parts):
 * ```
 * <coverage>
 *   <packages>
 *     <package name="...">
 *       <classes>
 *         <class filename="src/foo/bar.py">
 *           <lines>
 *             <line number="12" hits="3"/>
 *             <line number="13" hits="0"/>
 * ```
 */
object CoberturaReader : CoverageReader {
    override val format: CoverageFormat = CoverageFormat.COBERTURA

    override fun parse(text: String, sourceLabel: String): CoverageData? {
        if ("<coverage" !in text || "<class" !in text) return null
        val perFile = HashMap<String, IntArray>() // [covered, coverable]
        val factory = SAXParserFactory.newInstance().apply {
            // Disable external entity resolution (XXE protection).
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isNamespaceAware = false
        }
        val parser = factory.newSAXParser()
        var currentFile: String? = null
        try {
            parser.parse(text.byteInputStream(), object : DefaultHandler() {
                override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes) {
                    when (qName) {
                        "class" -> {
                            currentFile = attrs.getValue("filename")?.takeIf { it.isNotBlank() }
                        }
                        "line" -> {
                            val f = currentFile ?: return
                            val hits = attrs.getValue("hits")?.toIntOrNull() ?: return
                            val arr = perFile.getOrPut(f) { IntArray(2) }
                            arr[1]++
                            if (hits > 0) arr[0]++
                        }
                    }
                }

                override fun endElement(uri: String?, localName: String?, qName: String) {
                    if (qName == "class") currentFile = null
                }
            })
        } catch (_: Exception) {
            return null
        }
        if (perFile.isEmpty()) return null
        return CoverageData(format, sourceLabel, perFile.mapValues { (_, v) -> FileCoverage(v[0], v[1]) })
    }
}
