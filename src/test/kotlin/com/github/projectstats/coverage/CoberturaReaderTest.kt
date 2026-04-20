package com.github.projectstats.coverage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CoberturaReaderTest {

    @Test
    fun `parses class lines`() {
        val xml = """
            <?xml version="1.0"?>
            <coverage>
              <packages>
                <package name="foo">
                  <classes>
                    <class filename="src/foo/bar.py">
                      <lines>
                        <line number="1" hits="2"/>
                        <line number="2" hits="0"/>
                        <line number="3" hits="5"/>
                      </lines>
                    </class>
                  </classes>
                </package>
              </packages>
            </coverage>
        """.trimIndent()
        val data = CoberturaReader.parse(xml, "coverage.xml")
        assertNotNull(data)
        assertEquals(CoverageFormat.COBERTURA, data!!.format)
        val cov = data.perFile["src/foo/bar.py"]!!
        assertEquals(3, cov.coverableLines)
        assertEquals(2, cov.coveredLines)
    }

    @Test
    fun `returns null for non-cobertura input`() {
        assertNull(CoberturaReader.parse("<other/>", "x"))
    }
}
