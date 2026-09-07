package com.ardtt.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BypassWorkersTest {
    @Test
    fun tcpDefaultMatchesLabAndProvision() {
        assertEquals(3, BypassWorkers.DEFAULT)
        assertTrue(BypassWorkers.DEFAULT in BypassWorkers.MIN..BypassWorkers.MAX)
        assertEquals(9, BypassWorkers.MAX)
    }
}
