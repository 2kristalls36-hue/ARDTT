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
    fun commentPreviewShowsASingleLineSnippet() {
        assertEquals("", testingCommentPreview(""))
        assertEquals("", testingCommentPreview("   "))
        assertEquals("короткий текст", testingCommentPreview("короткий текст"))
        assertEquals("после Wi‑Fi туннель не встал", testingCommentPreview("после Wi‑Fi\nтуннель не встал"))
        val long = "x".repeat(TESTING_COMMENT_PREVIEW_CHARS + 8)
        val preview = testingCommentPreview(long)
        assertTrue(preview.endsWith("…"))
        assertEquals(TESTING_COMMENT_PREVIEW_CHARS + 1, preview.length)
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
    fun registerOrReuseKeepsNumberForTheSameLog() {
        val dir = Files.createTempDirectory("tickets").toFile()
        val store = TestingTicketStore(File(dir, "testing_tickets.json"))
        val first = store.registerOrReuse("первый раз", "log-a.json")
        val retry = store.registerOrReuse("повтор с правкой", "log-a.json")
        val other = store.registerOrReuse("другой файл", "log-b.json")
        assertEquals(1, first.number)
        assertEquals(1, retry.number)
        assertEquals("повтор с правкой", retry.comment)
        assertEquals(2, other.number)
        val loaded = store.load()
        assertEquals(listOf(2, 1), loaded.tickets.map { it.number })
        assertEquals("повтор с правкой", loaded.tickets.single { it.number == 1 }.comment)
        assertEquals(3, loaded.nextNumber)
    }

    @Test
    fun parseRepairsNextNumberAndDropsDuplicateIds() {
        val restored = TestingTicketStore.parse(
            """
            {"draftComment":"","nextNumber":1,"tickets":[
              {"number":3,"comment":"a","createdAtMs":1,"logName":""},
              {"number":3,"comment":"dup","createdAtMs":2,"logName":""},
              {"number":1,"comment":"b","createdAtMs":3,"logName":""}
            ]}
            """.trimIndent(),
        )
        assertEquals(listOf(3, 1), restored.tickets.map { it.number })
        assertEquals("a", restored.tickets.first().comment)
        assertEquals(4, restored.nextNumber)
        assertEquals(4, testingTicketNextNumber(1, restored.tickets))
    }

    @Test
    fun registerSkipsOccupiedNumbersFromCorruptFile() {
        val dir = Files.createTempDirectory("tickets").toFile()
        val file = File(dir, "testing_tickets.json")
        file.writeText(
            """{"draftComment":"","nextNumber":1,"tickets":[{"number":5,"comment":"old","createdAtMs":1,"logName":""}]}""",
        )
        val store = TestingTicketStore(file)
        assertEquals(6, store.register("новое").number)
        assertEquals(7, store.load().nextNumber)
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

    @Test
    fun perLogDraftsRoundTripAndDropBlanks() {
        val dir = Files.createTempDirectory("tickets").toFile()
        val store = TestingTicketStore(File(dir, "testing_tickets.json"))
        store.saveDrafts(
            mapOf(
                "log-a.json" to "после Wi‑Fi туннель не встал",
                "  " to "skip",
                "log-b.json" to "   ",
            ),
        )
        val loaded = store.load()
        assertEquals(mapOf("log-a.json" to "после Wi‑Fi туннель не встал"), loaded.drafts)
        val restored = TestingTicketStore.parse(TestingTicketStore.encode(loaded))
        assertEquals(loaded.drafts, restored.drafts)
    }

    @Test
    fun rememberUploadUsesServerNumberAndKeepsReadState() {
        val dir = Files.createTempDirectory("tickets").toFile()
        val store = TestingTicketStore(File(dir, "testing_tickets.json"))
        val local = store.register("локальный fallback", "log-a.json")
        assertEquals(1, local.number)
        val fromServer = store.rememberUpload(
            comment = "после Wi‑Fi туннель не встал",
            logName = "log-a.json",
            serverNumber = 12,
            read = true,
            processedAt = "2026-09-06T12:00:00Z",
            processedBy = "author",
            reviewNote = "разобрано",
        )
        assertEquals(12, fromServer.number)
        assertTrue(fromServer.read)
        assertEquals("разобрано", fromServer.reviewNote)
        val loaded = store.load()
        assertEquals(listOf(12), loaded.tickets.map { it.number })
        assertEquals(13, loaded.nextNumber)
        assertTrue(loaded.tickets.first().read)
        assertEquals("Прочитано", testingTicketReadLabel(true))
        assertEquals("Ожидает разбора", testingTicketReadLabel(false))
        assertEquals("Обращение", testingTicketTitle(0))
    }

    @Test
    fun applyServerStatusesMarksReadAndAddsMissingTickets() {
        val dir = Files.createTempDirectory("tickets").toFile()
        val store = TestingTicketStore(File(dir, "testing_tickets.json"))
        store.rememberUpload("ждёт разбора", "log-a.json", serverNumber = 4)
        store.applyServerStatuses(
            listOf(
                TestingTicketStatus(
                    logName = "log-a.json",
                    number = 4,
                    read = true,
                    processedAt = "2026-09-06T12:00:00Z",
                    processedBy = "author",
                    reviewNote = "принято",
                ),
                TestingTicketStatus(
                    logName = "log-b.json",
                    number = 9,
                    comment = "каскад не поднялся",
                    uploadedAtMs = 1_700_000_000_000L,
                ),
            ),
        )
        val loaded = store.load()
        assertEquals(listOf(9, 4), loaded.tickets.map { it.number })
        val first = loaded.tickets.single { it.number == 4 }
        assertTrue(first.read)
        assertEquals("принято", first.reviewNote)
        val added = loaded.tickets.single { it.number == 9 }
        assertEquals("каскад не поднялся", added.comment)
        assertEquals("log-b.json", added.logName)
        assertFalse(added.read)
    }

    @Test
    fun jsonRoundTripKeepsReadMarker() {
        val state = TestingTicketState(
            nextNumber = 5,
            tickets = listOf(
                TestingTicket(
                    number = 4,
                    comment = "обход не встал",
                    createdAtMs = 1_700_000_000_000L,
                    logName = "a.json",
                    read = true,
                    processedAt = "2026-09-06T12:00:00Z",
                    processedBy = "author",
                    reviewNote = "ок",
                ),
            ),
        )
        val restored = TestingTicketStore.parse(TestingTicketStore.encode(state))
        assertTrue(restored.tickets.single().read)
        assertEquals("ок", restored.tickets.single().reviewNote)
        assertEquals("author", restored.tickets.single().processedBy)
    }
}
