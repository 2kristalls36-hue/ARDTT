package com.ardtt.app.ui.exceptions

import androidx.compose.ui.unit.dp
import com.ardtt.app.ui.theme.ArdttSpacing
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

    @Test
    fun loadingStubsContinueToTheBottomOfTheList() {
        val stride = appsLoadingStubStride()
        val top = ArdttSpacing.Small
        val exact = stride * 8 + top
        assertEquals(8, appsLoadingStubCount(exact, stride, top))
        val pastSearch = stride * 12 + top + 1.dp
        val count = appsLoadingStubCount(pastSearch, stride, top)
        assertTrue(count > 9)
        assertTrue(stride * count >= pastSearch - top)
    }
}
