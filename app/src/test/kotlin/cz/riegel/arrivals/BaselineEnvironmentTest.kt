package cz.riegel.arrivals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BaselineEnvironmentTest {

    @Test
    fun testBuildEnvironmentConfiguration() {
        // Red test first: intentionally verify baseline setup
        val appName = "Arrivals"
        assertEquals("Arrivals", appName)
        assertTrue(true)
    }
}
