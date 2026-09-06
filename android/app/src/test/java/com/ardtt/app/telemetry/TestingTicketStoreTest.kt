package com.ardtt.app.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TestingTicketStoreTest {
    @Test
    fun uploadPromptSkippedWhenDraftIsFilled() {
        assertTrue(testingUploadNeedsCommentPrompt(""))
        assertTrue(testingUploadNeedsCommentPrompt("   "))
        assertFalse(testingUploadNeedsCommentPrompt("не поднимается обход"))
    }

    @Test
    fun ticketsGetSequentialNumbersAndKeepComments() {
        val dir = Files.createTempDirectory("tickets").toFile()
        val store = TestingTicketStore(File(dir, "testing_tickets.json"))
        store.saveDraft("черновик")
        val first = store.register("после Wi‑Fi туннель не встал", logName = "log-a.json")
        val second = store.register("только комментарий")
        assertEquals(1, first.number)
        assertEquals("после Wi‑Fi туннель не встал", first.comment)
        assertEquals("log-a.json", first.logName)
        assertEquals(2, second.number)
        assertEquals("", second.logName)
        val loaded = store.load()
        assertEquals("черновик", loaded.draftComment)
        assertEquals(3, loaded.nextNumber)
        assertEquals(listOf(2, 1), loaded.tickets.map { it.number })
        assertEquals("Обращение №12", testingTicketTitle(12))
    }

    @Test
    fun jsonRoundTripKeepsDraftAndHistory() {
        val state = TestingTicketState(
            draftComment = "ожидал reconnect",
            nextNumber = 4,
            tickets = listOf(
                TestingTicket(3, "каскад не поднялся", 1_700_000_000_000L, "a.json"),
                TestingTicket(2, "второй", 1_700_000_000_100L),
            ),
        )
        val restored = TestingTicketStore.parse(TestingTicketStore.encode(state))
        assertEquals("ожидал reconnect", restored.draftComment)
        assertEquals(4, restored.nextNumber)
        assertEquals(listOf(3, 2), restored.tickets.map { it.number })
        assertEquals("каскад не поднялся", restored.tickets.first().comment)
    }
}
