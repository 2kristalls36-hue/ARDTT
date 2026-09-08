package com.ardtt.app.ui.exceptions

internal enum class ExceptionsEmptyKind {
    HasRows,
    NoData,
    NoMatches,
}

internal object ExceptionsCatalog {
    fun emptyKind(total: Int, visible: Int, query: String): ExceptionsEmptyKind {
        if (visible > 0) return ExceptionsEmptyKind.HasRows
        return if (query.isBlank()) ExceptionsEmptyKind.NoData else ExceptionsEmptyKind.NoMatches
    }

    fun offersClearSearch(kind: ExceptionsEmptyKind): Boolean =
        kind == ExceptionsEmptyKind.NoMatches
}
