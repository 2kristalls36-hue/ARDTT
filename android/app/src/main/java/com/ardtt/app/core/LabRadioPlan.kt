package com.ardtt.app.core

/**
 * Dual-radio lab session: Wi‑Fi (or VPS TCP) is the control plane to the
 * agent; the tunnel underlay under test stays on cellular (operator БС).
 *
 * A companion process that only "turns Wi‑Fi on" is not enough. Default
 * [pickBestUnderlayNetwork] scores a live AP above LTE, and
 * [autoUsesDirectOnWifi] then skips Bypass. Lab mode must force the
 * effective underlay to cellular while C2 sockets bind to Wi‑Fi.
 */
enum class LabControlLink {
    Wifi,
    CellularToVps,
    None,
}

data class LabRadioPlan(
    val control: LabControlLink,
    val testUnderlay: UnderlayKind,
    val ready: Boolean,
    val reason: String,
)

fun labEffectiveUnderlayKind(
    labCellularOnly: Boolean,
    raw: UnderlayKind,
    cellularAvailable: Boolean,
): UnderlayKind {
    if (labCellularOnly && cellularAvailable) return UnderlayKind.Cellular
    return raw
}

fun planLabRadios(
    wifiUp: Boolean,
    cellularUp: Boolean,
    labCellularOnly: Boolean,
    pathMode: ConnPathMode = ConnPathMode.Auto,
    vpsTcpOnCellular: Boolean = false,
): LabRadioPlan {
    val raw = classifyUnderlayKind(wifi = wifiUp, cellular = cellularUp)
    val testUnderlay = labEffectiveUnderlayKind(
        labCellularOnly = labCellularOnly,
        raw = raw,
        cellularAvailable = cellularUp,
    )
    val control = when {
        wifiUp -> LabControlLink.Wifi
        vpsTcpOnCellular -> LabControlLink.CellularToVps
        else -> LabControlLink.None
    }
    if (!cellularUp) {
        return LabRadioPlan(
            control = control,
            testUnderlay = testUnderlay,
            ready = false,
            reason = "нет LTE — белый список оператора не на чем проверить",
        )
    }
    if (control == LabControlLink.None) {
        return LabRadioPlan(
            control = control,
            testUnderlay = testUnderlay,
            ready = false,
            reason = "нет канала к агенту: включите Wi‑Fi управления или TCP до VPS по LTE",
        )
    }
    if (!labCellularOnly && wifiUp) {
        val wouldSkip = autoUsesDirectOnWifi(pathMode, raw)
        return LabRadioPlan(
            control = control,
            testUnderlay = testUnderlay,
            ready = false,
            reason = if (wouldSkip) {
                "Wi‑Fi поднят, lab-режим выкл. — Auto возьмёт Direct и не увидит БС"
            } else {
                "Wi‑Fi поднят, lab-режим выкл. — зонд и handover смотрят на AP, не на LTE"
            },
        )
    }
    if (testUnderlay != UnderlayKind.Cellular) {
        return LabRadioPlan(
            control = control,
            testUnderlay = testUnderlay,
            ready = false,
            reason = "тестовый underlay не LTE",
        )
    }
    return LabRadioPlan(
        control = control,
        testUnderlay = testUnderlay,
        ready = true,
        reason = when (control) {
            LabControlLink.Wifi ->
                "управление по Wi‑Fi, туннель и зонд на LTE"
            LabControlLink.CellularToVps ->
                "управления по Wi‑Fi нет — канал к агенту по TCP VPS на LTE"
            LabControlLink.None ->
                "нет канала"
        },
    )
}
