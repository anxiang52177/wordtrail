package com.wordtrail.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryPersistenceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: WordDatabase
    private lateinit var repo: Repository
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(context, WordDatabase::class.java).build(); repo = Repository(context, db) }
    @After fun close() { db.close() }
    private suspend fun add(text: String, target: String? = null, update: Boolean = false): ImportResult =
        repo.commitImport(repo.previewImport(WordImport.parse(text, "tsv"), target), "测试词库", "描述", update, "粘贴导入")

    @Test fun createRenameSwitchAndCaseInsensitiveAppendPreserveProgress() = runBlocking {
        val first = add("apple\t苹果\t/old/\tn.\tOld example\t旧例句\nApple\t重复")
        assertEquals(1, first.added); assertEquals(1, first.skipped)
        val word = db.dao().bookWords(first.bookId).single()
        repo.view(word.id, 100, "2026-10-15"); repo.favorite(word.id); repo.mastered(word.id)
        val before = db.dao().progress(word.id)
        val preview = repo.previewImport(WordImport.parse("APPLE\t更新苹果\nbook\t书籍\nBOOK\t又重复", "tsv"), first.bookId)
        assertEquals(2, preview.duplicates)
        val result = repo.commitImport(preview, "", "", true, "文件导入")
        assertEquals(1, result.added); assertEquals(2, result.updated)
        val updated = db.dao().bookWords(first.bookId).first { it.id == word.id }
        assertEquals("更新苹果", updated.meaning); assertEquals("/old/", updated.phonetic); assertEquals("Old example", updated.example)
        assertEquals(before, db.dao().progress(word.id))
        repo.renameBook(first.bookId, "重命名后的词库")
        assertEquals("重命名后的词库", db.dao().book(first.bookId)!!.name)
        val second = repo.createBook("空词库", "")
        repo.selectBook(second); assertTrue(repo.all().isEmpty())
        repo.selectBook(first.bookId); assertEquals(2, repo.all().size)
        assertEquals(before, db.dao().progress(word.id))
    }

    @Test fun deleteCurrentAndLastRestoreAndBuiltinStaysHiddenAfterSeed() = runBlocking {
        repo.seed()
        val custom = add("apple\t自建苹果").bookId
        val word = db.dao().bookWords(custom).single()
        repo.view(word.id, 100, "2026-10-15")
        repo.trashBooks(setOf(WordDatabase.BUILTIN_ID))
        assertEquals(custom, db.dao().activeBookId())
        repo.seed(); assertTrue(db.dao().book(WordDatabase.BUILTIN_ID)!!.deleted)
        assertEquals(custom, db.dao().activeBookId())
        repo.trashBooks(setOf(custom))
        assertNull(db.dao().activeBookId()); assertTrue(repo.all().isEmpty())
        repo.seed(); assertNull(db.dao().activeBookId())
        repo.trashBooks(setOf(custom), restore = true)
        assertEquals(custom, db.dao().activeBookId()); assertEquals(1L, repo.all().single().progress!!.viewCount)
        assertEquals(1, db.dao().days().size)
    }

    @Test fun permanentDeletionPreservesSharedWordsAndTheirRecords() = runBlocking {
        val first = add("shared\t共享\nprivate\t独占").bookId
        val words = db.dao().bookWords(first)
        val shared = words.first { it.english == "shared" }; val privateWord = words.first { it.english == "private" }
        val second = repo.createBook("共享词库", "")
        db.dao().members(listOf(BookWord(second, shared.id)))
        words.forEach { repo.view(it.id, 100, "2026-10-15"); repo.favorite(it.id) }
        repo.trashBooks(setOf(first)); repo.trashBooks(setOf(first), permanent = true)
        assertNull(db.dao().book(first)); assertNull(db.dao().progress(privateWord.id))
        assertEquals(listOf(shared.id), db.dao().all().map { it.word.id })
        assertEquals(1L, db.dao().progress(shared.id)!!.viewCount); assertTrue(db.dao().progress(shared.id)!!.favorite)
        assertEquals(listOf(shared.id), db.dao().days().map { it.wordId })
    }

    @Test fun batchDeleteAndRestoreAreAtomic() = runBlocking {
        val a = add("a\t甲").bookId; val b = add("b\t乙").bookId
        try { repo.trashBooks(setOf(a, "missing")); fail() } catch (_: IllegalStateException) { }
        assertFalse(db.dao().book(a)!!.deleted)
        repo.trashBooks(setOf(a, b)); assertNull(db.dao().activeBookId())
        repo.trashBooks(setOf(a, b), restore = true)
        assertTrue(db.dao().books().none { it.deleted }); assertNotNull(db.dao().activeBookId())
    }

    @Test fun invalidTargetAndWriteFailureDoNotLeavePartialLibrary() = runBlocking {
        repo.seed()
        try { repo.previewImport(WordImport.parse("a\t甲", "tsv"), WordDatabase.BUILTIN_ID); fail() } catch (_: IllegalArgumentException) { }
        val initial = db.dao().all()
        val books = db.dao().books()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_import BEFORE INSERT ON words WHEN NEW.english = 'fail' BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        try { add("good\t有效\nfail\t失败"); fail() } catch (_: Exception) { }
        assertEquals(books, db.dao().books()); assertEquals(initial, db.dao().all())
    }

    @Test fun largeLibraryWritesAndDuplicatesAreIdempotent() = runBlocking {
        val text = (1..5000).joinToString("\n") { "word$it\t中文$it" }
        val result = add(text)
        assertEquals(5000, result.added)
        assertEquals(5000, add(text, result.bookId).skipped)
        assertEquals(5000, db.dao().bookWords(result.bookId).size)
    }

    @Test fun recycleBinAndActiveIdSurviveReopen() = runBlocking {
        val name = "library-reopen.db"; context.deleteDatabase(name)
        var disk = WordDatabase.open(context, name)
        try {
            var repository = Repository(context, disk)
            repository.seed()
            val id = repository.createBook("自建", "描述")
            val preview = repository.previewImport(WordImport.parse("apple\t苹果", "tsv"), id)
            repository.commitImport(preview, "", "", false, "粘贴")
            repository.selectBook(id)
            val wordId = repository.all().single().word.id
            repository.view(wordId, 100, "2026-10-15")
            repository.trashBooks(setOf(id)); repository.renameBook(id, "回收站名称")
            disk.close(); disk = WordDatabase.open(context, name); repository = Repository(context, disk)
            repository.seed()
            assertTrue(disk.dao().book(id)!!.deleted); assertEquals("回收站名称", disk.dao().book(id)!!.name)
            assertEquals(WordDatabase.BUILTIN_ID, disk.dao().activeBookId())
            repository.trashBooks(setOf(id), restore = true); repository.selectBook(id)
            assertEquals(1L, repository.all().single().progress!!.viewCount)
        } finally { disk.close(); context.deleteDatabase(name) }
    }

    @Test fun migrationFromVersionTwoRetainsIdsAndProgress() = runBlocking {
        val name = "library-v2.db"; context.deleteDatabase(name)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            old.execSQL("CREATE TABLE words (id TEXT NOT NULL PRIMARY KEY, english TEXT NOT NULL, phonetic TEXT NOT NULL, partOfSpeech TEXT NOT NULL, meaning TEXT NOT NULL, example TEXT NOT NULL, translation TEXT NOT NULL)")
            old.execSQL("CREATE TABLE progress (wordId TEXT NOT NULL PRIMARY KEY, firstViewed INTEGER NOT NULL, lastViewed INTEGER NOT NULL, viewCount INTEGER NOT NULL, favorite INTEGER NOT NULL, mastered INTEGER NOT NULL, favoriteUpdatedAt INTEGER NOT NULL DEFAULT 0, masteredUpdatedAt INTEGER NOT NULL DEFAULT 0, FOREIGN KEY(wordId) REFERENCES words(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
            old.execSQL("CREATE TABLE daily_views (wordId TEXT NOT NULL, day TEXT NOT NULL, PRIMARY KEY(wordId, day), FOREIGN KEY(wordId) REFERENCES words(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
            old.execSQL("INSERT INTO words VALUES ('legacy','legacy','','','旧词','','')")
            old.execSQL("INSERT INTO progress VALUES ('legacy',100,200,2,1,1,150,160)")
            old.execSQL("INSERT INTO daily_views VALUES ('legacy','2026-10-15')"); old.version = 2
        }
        val disk = WordDatabase.open(context, name)
        try {
            val repository = Repository(context, disk)
            assertEquals("legacy", repository.all().single().word.id)
            assertEquals(2L, repository.all().single().progress!!.viewCount)
            assertEquals(150L, repository.all().single().progress!!.favoriteUpdatedAt)
            assertEquals(1, disk.dao().days().size)
            repository.seed(); assertTrue(repository.all().any { it.word.id == "legacy" })
        } finally { disk.close(); context.deleteDatabase(name) }
    }
}
