package com.ardtt.app.deploy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeployProgressCopyTest {
    private val cascade = DeployHopTrack(
        cascade = true,
        entryHost = "45.129.2.3",
        exitHost = "2.26.125.160",
        activeHost = "2.26.125.160",
    )

    @Test
    fun percentLabelIsWholePercent() {
        assertEquals("0%", DeployProgressCopy.percentLabel(-0.2f))
        assertEquals("0%", DeployProgressCopy.percentLabel(0f))
        assertEquals("42%", DeployProgressCopy.percentLabel(0.42f))
        assertEquals("100%", DeployProgressCopy.percentLabel(1f))
        assertEquals("100%", DeployProgressCopy.percentLabel(1.7f))
    }

    @Test
    fun cascadeStepNamesVpsAndIp() {
        assertEquals("VPS 2", DeployProgressCopy.slotLabel(cascade, "2.26.125.160"))
        assertEquals("VPS 1", DeployProgressCopy.slotLabel(cascade, "45.129.2.3:22"))
        assertEquals(
            "VPS 2 · 2.26.125.160 · Загрузка архива стека…",
            DeployProgressCopy.step(cascade, "2.26.125.160", "Загрузка архива стека…"),
        )
        assertEquals(
            "VPS 1 · 45.129.2.3 · Подключение SSH…",
            DeployProgressCopy.step(cascade, "45.129.2.3", "45.129.2.3 · Подключение SSH…"),
        )
    }

    @Test
    fun standaloneStepKeepsIpAndDetail() {
        val single = DeployHopTrack(entryHost = "10.0.0.1")
        assertNull(DeployProgressCopy.slotLabel(single, "10.0.0.1"))
        assertEquals(
            "10.0.0.1 · Сборка единого образа",
            DeployProgressCopy.step(single, "10.0.0.1", "Сборка единого образа"),
        )
    }
}
