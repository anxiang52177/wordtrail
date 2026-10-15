package com.wordtrail.app.data

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class StudyLogicTest {
    private fun word(id: String, meaning: String = "测试") = Word(id, id, "/test/", "n.", meaning, "A test.", "一个测试。")
    @Test fun newModeOnlySelectsUnseenAndReportsExhaustion() {
        val old = WordState(word("old"), Progress("old", 1, 2, 3))
        val new = WordState(word("new"), null)
        repeat(100) { assertEquals("new", StudyLogic.pick(listOf(old, new), StudyMode.NEW, null)?.word?.id) }
        assertNull(StudyLogic.pick(listOf(old), StudyMode.NEW, null))
        assertNull(StudyLogic.pick(listOf(new), StudyMode.REVIEW, null))
    }
    @Test fun reviewPrefersUnmasteredAndOldestFive() {
        val words = (1..12).map { WordState(word("word$it"), Progress("word$it", 1, it.toLong(), 1, mastered = it == 1)) }
        repeat(100) { seed ->
            val id = StudyLogic.pick(words, StudyMode.REVIEW, "word2", Random(seed))!!.word.id
            assertTrue(id in (3..7).map { "word$it" })
        }
    }
    @Test fun reviewAllowsOnlyAvailableWord() {
        val w = WordState(word("one"), Progress("one", 1, 1, 1, mastered = true))
        assertEquals(w, StudyLogic.pick(listOf(w), StudyMode.REVIEW, "one"))
    }
    @Test fun searchIsCaseInsensitiveAndSupportsChineseAndFilters() {
        val a = WordState(word("apple", "苹果"), Progress("apple", 1, 5, 1, favorite = true))
        val b = WordState(word("book", "书"), Progress("book", 1, 9, 1, mastered = true))
        val words = listOf(a, b, WordState(word("unseen"), null))
        assertEquals(listOf(a), StudyLogic.filter(words, " APP ", "all", false))
        assertEquals(listOf(a), StudyLogic.filter(words, "苹果", "all", false))
        assertEquals(listOf(b, a), StudyLogic.filter(words, "", "all", false))
        assertEquals(listOf(b), StudyLogic.filter(words, "", "mastered", false))
        assertEquals(listOf(a), StudyLogic.filter(words, "", "unmastered", false))
        assertEquals(listOf(a), StudyLogic.filter(words, "", "all", true))
        assertTrue(StudyLogic.filter(words, "%", "all", false).isEmpty())
    }
    @Test fun mergingIsIdempotentAndUsesSeparateStateTimestamps() {
        val local = Progress("apple", 100, 500, 7, favorite = true, mastered = false, favoriteUpdatedAt = 600, masteredUpdatedAt = 300)
        val incoming = Progress("apple", 50, 400, 4, favorite = false, mastered = true, favoriteUpdatedAt = 200, masteredUpdatedAt = 700)
        val merged = StudyLogic.merge(local, incoming)
        assertEquals(50L, merged.firstViewed); assertEquals(500L, merged.lastViewed); assertEquals(7L, merged.viewCount)
        assertTrue(merged.favorite); assertTrue(merged.mastered)
        assertEquals(merged, StudyLogic.merge(merged, incoming))
    }
    @Test fun backupRoundTripPreservesAllFields() {
        val original = BackupFile(exportedAt = 123, entries = listOf(BackupEntry(word("apple", "苹果"),
            Progress("apple", 1, 5, 2, true, true, 6, 7), listOf("2026-10-15"))))
        assertEquals(original, BackupCodec.decode(BackupCodec.encode(original)))
    }
    @Test fun invalidBackupsAreRejected() {
        val valid = BackupFile(exportedAt = 123, entries = listOf(BackupEntry(word("apple"), Progress("apple", 1, 5, 2), listOf("2026-10-15"))))
        val invalid = listOf("{}", "not json", BackupCodec.encode(valid.copy(version = 99)),
            BackupCodec.encode(valid.copy(entries = valid.entries + valid.entries)),
            BackupCodec.encode(valid.copy(entries = listOf(valid.entries[0].copy(days = listOf("2026-02-30"))))),
            BackupCodec.encode(valid.copy(entries = listOf(valid.entries[0].copy(progress = Progress("apple", 10, 1, -1))))))
        invalid.forEach { json ->
            try { BackupCodec.decode(json); fail("应拒绝无效备份") } catch (_: Exception) { /* 预期失败 */ }
        }
    }
}
