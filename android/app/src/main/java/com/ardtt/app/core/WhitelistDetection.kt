package com.ardtt.app.core

/**
 * Operator whitelist (БС) detection is a cellular-only problem: no Wi‑Fi or
 * Ethernet underlay has been observed filtering the direct path. The scoring
 * machinery stays in the tree for the day that changes, but every other
 * transport is stubbed to a plain Direct verdict instead of paying for a probe
 * round whose result is thrown away.
 *
 * Re-enabling a transport is one entry in [DETECTED_TRANSPORTS].
 */
object WhitelistDetection {
    private val DETECTED_TRANSPORTS = setOf(UnderlayKind.Cellular)

    fun appliesTo(kind: UnderlayKind): Boolean = kind in DETECTED_TRANSPORTS

    fun appliesTo(key: NetworkKey?): Boolean = key != null && appliesTo(key.transport)

    /** Score carried by a stubbed transport; never a reason to take Bypass. */
    const val STUB_SCORE_PERCENT: Int = 0

    val stubRestriction: RestrictionHint = RestrictionHint.None
}
