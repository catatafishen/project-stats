package com.github.projectstats

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FormattingTest {

    @Test
    fun `compactCount shortens large numbers with one decimal place`() {
        assertEquals("999", compactCount(999))
        assertEquals("1.0K", compactCount(1_000))
        assertEquals("32.9K", compactCount(32_941))
        assertEquals("1.2M", compactCount(1_234_567))
    }

    @Test
    fun `humanBytes keeps readable units for table display`() {
        assertEquals("999 B", humanBytes(999))
        assertEquals("1.0 KB", humanBytes(1_024))
        assertEquals("1.0 MB", humanBytes(1_048_576))
    }
}
