package com.nonamevpn.app.legal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TestingModeAgreementTest {
    @Test
    fun hasStructuredSectionsAndVersion() {
        assertEquals(1, TestingModeAgreement.VERSION)
        assertEquals("Соглашение о режиме тестирования", TestingModeAgreement.TITLE)
        assertEquals(9, TestingModeAgreement.sections.size)
        TestingModeAgreement.sections.forEachIndexed { index, section ->
            assertTrue("blank title at $index", section.title.isNotBlank())
            assertTrue("blank body at $index", section.body.length > 80)
            assertTrue("title should be numbered", section.title.startsWith("${index + 1}."))
        }
    }

    @Test
    fun coversDiagnosticsConsentAndVoluntaryHelp() {
        val text = TestingModeAgreement.plainText().lowercase()
        assertTrue(text.contains("телеметр") || text.contains("диагностич"))
        assertTrue(text.contains("устройств"))
        assertTrue(text.contains("добровольн"))
        assertTrue(text.contains("безвозмездн"))
        assertTrue(text.contains("ответственност"))
        assertTrue(text.contains("18 лет"))
        assertTrue(text.contains("российской федерации"))
        assertTrue(text.startsWith(TestingModeAgreement.TITLE.lowercase()))
        assertTrue(!text.startsWith("Разработчик не несёт"))
    }

    @Test
    fun doesNotLeadWithLiabilityWaiver() {
        val first = TestingModeAgreement.sections.first().body
        assertTrue(first.startsWith("Настоящее соглашение"))
        assertTrue(!first.contains("не несёт ответственности"))
        assertTrue(!first.contains("безвозмездн"))
    }
}
