package com.ardtt.app.deploy

import org.junit.Assert.assertEquals
import org.junit.Test

class DeployShadeTest {
    @Test
    fun titlesDistinguishInstallUpdateAndUninstall() {
        assertEquals("Установка деплоя", DeployShade.title(isUpdate = false))
        assertEquals("Обновление деплоя", DeployShade.title(isUpdate = true))
        assertEquals(
            "Удаление деплоя",
            DeployShade.title(isUpdate = false, isUninstall = true),
        )
        assertEquals(
            "Удаление деплоя",
            DeployShade.title(isUpdate = true, isUninstall = true),
        )
    }

    @Test
    fun progressMapsFractionToPercent() {
        assertEquals(0, DeployShade.progressPercent(-1f))
        assertEquals(0, DeployShade.progressPercent(0f))
        assertEquals(50, DeployShade.progressPercent(0.5f))
        assertEquals(74, DeployShade.progressPercent(0.74f))
        assertEquals(100, DeployShade.progressPercent(1f))
        assertEquals(100, DeployShade.progressPercent(1.4f))
    }

    @Test
    fun contentPrefersStepThenHost() {
        assertEquals(
            "Сборка bypass (3/6)",
            DeployShade.contentText("Сборка bypass (3/6)", "vps.example"),
        )
        assertEquals("vps.example", DeployShade.contentText("  ", "vps.example"))
        assertEquals("Идёт установка…", DeployShade.contentText("", ""))
        assertEquals(
            "Идёт удаление…",
            DeployShade.contentText("", "", isUninstall = true),
        )
    }

    @Test
    fun finishedFallsBackToReadyOrError() {
        assertEquals("Готово", DeployShade.finishedText(success = true, message = ""))
        assertEquals("Ошибка", DeployShade.finishedText(success = false, message = "  "))
        assertEquals(
            "Стек установлен",
            DeployShade.finishedText(success = true, message = "Стек установлен"),
        )
    }
}
