package com.ardtt.app.core

import android.os.Build

/** Human-readable phone model for admin presence / client cards. */
object PhoneModelLabel {
    fun current(): String = format(Build.MANUFACTURER, Build.MODEL)

    fun format(manufacturer: String?, model: String?): String {
        val m = model?.trim().orEmpty()
        if (m.isEmpty()) return ""
        val brand = manufacturer?.trim().orEmpty()
        if (brand.isEmpty() || m.contains(brand, ignoreCase = true)) return m
        val titled = brand.replaceFirstChar { ch ->
            if (ch.isLowerCase()) ch.titlecase() else ch.toString()
        }
        return "$titled $m"
    }
}
