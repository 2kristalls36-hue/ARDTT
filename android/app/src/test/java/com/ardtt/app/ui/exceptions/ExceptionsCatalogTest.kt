package com.ardtt.app.ui.exceptions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExceptionsCatalogTest {
    @Test
    fun emptyListIsNoDataNotAFailedSearch() {
        assertEquals(
            ExceptionsEmptyKind.NoData,
            ExceptionsCatalog.emptyKind(total = 0, visible = 0, query = ""),
        )
        assertFalse(ExceptionsCatalog.offersClearSearch(ExceptionsEmptyKind.NoData))
    }

    @Test
    fun filteredOutRowsOfferClearSearch() {
        assertEquals(
            ExceptionsEmptyKind.NoMatches,
            ExceptionsCatalog.emptyKind(total = 12, visible = 0, query = "xyz"),
        )
        assertTrue(ExceptionsCatalog.offersClearSearch(ExceptionsEmptyKind.NoMatches))
    }

    @Test
    fun visibleRowsAreNotAnEmptyState() {
        assertEquals(
            ExceptionsEmptyKind.HasRows,
            ExceptionsCatalog.emptyKind(total = 12, visible = 3, query = "vk"),
        )
    }
}
