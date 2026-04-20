package com.github.projectstats.coverage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CoverageMatcherTest {

    private fun data(vararg pairs: Pair<String, FileCoverage>) =
        CoverageData(CoverageFormat.LCOV, "x", pairs.toMap())

    @Test
    fun `exact path match`() {
        val m = CoverageMatcher(data("src/main/Foo.kt" to FileCoverage(2, 4)))
        val cov = m.match("src/main/Foo.kt")!!
        assertEquals(4, cov.coverableLines)
    }

    @Test
    fun `suffix match handles absolute coverage paths`() {
        val m = CoverageMatcher(data("/abs/build/src/main/Foo.kt" to FileCoverage(1, 3)))
        val cov = m.match("src/main/Foo.kt")!!
        assertEquals(3, cov.coverableLines)
    }

    @Test
    fun `suffix match handles jacoco-style package keys`() {
        val m = CoverageMatcher(data("com/example/foo/MyClass.kt" to FileCoverage(7, 10)))
        val cov = m.match("src/main/kotlin/com/example/foo/MyClass.kt")!!
        assertEquals(10, cov.coverableLines)
    }

    @Test
    fun `prefers entry with more coverable lines on tie`() {
        val m = CoverageMatcher(
            data(
                "a/Foo.kt" to FileCoverage(1, 2),
                "b/Foo.kt" to FileCoverage(5, 10),
            )
        )
        // Both end with "Foo.kt" suffix; the larger one wins.
        val cov = m.match("Foo.kt")!!
        assertEquals(10, cov.coverableLines)
    }

    @Test
    fun `windows backslashes are normalised`() {
        val m = CoverageMatcher(data("src/main/Foo.kt" to FileCoverage(1, 2)))
        val cov = m.match("src\\main\\Foo.kt")!!
        assertEquals(2, cov.coverableLines)
    }

    @Test
    fun `returns null on miss`() {
        val m = CoverageMatcher(data("Foo.kt" to FileCoverage(1, 2)))
        assertNull(m.match("Bar.kt"))
    }
}
