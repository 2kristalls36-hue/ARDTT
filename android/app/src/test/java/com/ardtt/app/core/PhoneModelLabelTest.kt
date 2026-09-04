package com.ardtt.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneModelLabelTest {
    @Test
    fun usesModelWhenManufacturerAlreadyPresent() {
        assertEquals("Google Pixel 8", PhoneModelLabel.format("Google", "Google Pixel 8"))
        assertEquals("Pixel 8", PhoneModelLabel.format("", "Pixel 8"))
    }

    @Test
    fun prefixesManufacturerWhenMissingFromModel() {
        assertEquals("Samsung SM-S918B", PhoneModelLabel.format("samsung", "SM-S918B"))
    }

    @Test
    fun blankModelIsEmpty() {
        assertEquals("", PhoneModelLabel.format("Google", "  "))
        assertEquals("", PhoneModelLabel.format("Google", null))
    }
}
