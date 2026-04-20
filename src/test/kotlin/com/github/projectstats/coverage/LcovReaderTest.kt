package com.github.projectstats.coverage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LcovReaderTest {

    @Test
    fun `parses single record`() {
        val text = """
            TN:
            SF:src/main/kotlin/Foo.kt
            DA:1,1
            DA:2,0
            DA:3,3
            LF:3
            LH:2
            end_of_record
        """.trimIndent()
        val data = LcovReader.parse(text, "lcov.info")
        assertNotNull(data)
        assertEquals(CoverageFormat.LCOV, data!!.format)
        val cov = data.perFile["src/main/kotlin/Foo.kt"]
        assertNotNull(cov)
        assertEquals(3, cov!!.coverableLines)
        assertEquals(2, cov.coveredLines)
    }

    @Test
    fun `merges duplicate SF entries`() {
        val text = """
            SF:Foo.kt
            DA:1,1
            DA:2,0
            end_of_record
            SF:Foo.kt
            DA:3,5
            end_of_record
        """.trimIndent()
        val data = LcovReader.parse(text, "lcov.info")!!
        val cov = data.perFile["Foo.kt"]!!
        assertEquals(3, cov.coverableLines)
        assertEquals(2, cov.coveredLines)
    }

    @Test
    fun `returns null for unrecognised input`() {
        assertNull(LcovReader.parse("not lcov at all", "x"))
    }
}
