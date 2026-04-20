package com.github.projectstats.coverage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class JacocoReaderTest {

    @Test
    fun `parses LINE counters per sourcefile`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <report name="demo">
              <package name="com/example/foo">
                <sourcefile name="MyClass.kt">
                  <line nr="1" mi="0" ci="3"/>
                  <counter type="INSTRUCTION" missed="2" covered="8"/>
                  <counter type="LINE" missed="2" covered="8"/>
                </sourcefile>
                <sourcefile name="Other.kt">
                  <counter type="LINE" missed="0" covered="4"/>
                </sourcefile>
              </package>
            </report>
        """.trimIndent()
        val data = JacocoReader.parse(xml, "jacoco.xml")
        assertNotNull(data)
        val a = data!!.perFile["com/example/foo/MyClass.kt"]!!
        assertEquals(10, a.coverableLines)
        assertEquals(8, a.coveredLines)
        val b = data.perFile["com/example/foo/Other.kt"]!!
        assertEquals(4, b.coverableLines)
        assertEquals(4, b.coveredLines)
    }

    @Test
    fun `returns null for non-jacoco input`() {
        assertNull(JacocoReader.parse("<coverage/>", "x"))
    }
}
