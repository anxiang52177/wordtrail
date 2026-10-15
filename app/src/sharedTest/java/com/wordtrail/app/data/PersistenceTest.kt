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
class PersistenceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val word = Word("apple", "apple", "/ˈæpəl/", "n.", "苹果", "I ate an apple.", "我吃了一个苹果。")
    @Test fun recordsSurviveCloseAndReopenAndSeedUpdate() = runBlocking {
        val name = "persistence-test.db"
        context.deleteDatabase(name)
        var db = WordDatabase.open(context, name)
        try {
            db.dao().words(listOf(word))
            val repo = Repository(context, db)
            repo.view("apple", 100, "2026-10-14"); repo.view("apple", 200, "2026-10-15"); repo.view("apple", 300, "2026-10-15")
            repo.favorite("apple"); repo.mastered("apple")
            db.close()
            db = WordDatabase.open(context, name)
            db.dao().words(listOf(word.copy(meaning = "苹果（更新释义）")))
            val state = db.dao().all().single()
            assertEquals(3L, state.progress!!.viewCount)
            assertEquals(100L, state.progress.firstViewed); assertEquals(300L, state.progress.lastViewed)
            assertTrue(state.progress.favorite); assertTrue(state.progress.mastered)
            assertEquals(2, db.dao().days().size)
            assertEquals("苹果（更新释义）", state.word.meaning)
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun exportImportIsIdempotentAndInvalidInputIsAtomic() = runBlocking {
        val source = Room.inMemoryDatabaseBuilder(context, WordDatabase::class.java).build()
        val target = Room.inMemoryDatabaseBuilder(context, WordDatabase::class.java).build()
        try {
            source.dao().words(listOf(word))
            val a = Repository(context, source); val b = Repository(context, target)
            a.view("apple", 123, "2026-10-15"); a.favorite("apple"); a.mastered("apple")
            val json = a.export()
            b.import(json); b.import(json)
            assertEquals(source.dao().all(), target.dao().all())
            assertEquals(source.dao().days(), target.dao().days())
            try { b.import(json.replace("2026-10-15", "bad-date")); fail() } catch (_: Exception) { }
            assertEquals(source.dao().all(), target.dao().all())
        } finally { source.close(); target.close() }
    }
    @Test fun migrationFromVersionOnePreservesRecords() = runBlocking {
        val name = "migration-test.db"
        context.deleteDatabase(name)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            old.execSQL("CREATE TABLE words (id TEXT NOT NULL PRIMARY KEY, english TEXT NOT NULL, phonetic TEXT NOT NULL, partOfSpeech TEXT NOT NULL, meaning TEXT NOT NULL, example TEXT NOT NULL, translation TEXT NOT NULL)")
            old.execSQL("CREATE TABLE progress (wordId TEXT NOT NULL PRIMARY KEY, firstViewed INTEGER NOT NULL, lastViewed INTEGER NOT NULL, viewCount INTEGER NOT NULL, favorite INTEGER NOT NULL, mastered INTEGER NOT NULL, FOREIGN KEY(wordId) REFERENCES words(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
            old.execSQL("CREATE TABLE daily_views (wordId TEXT NOT NULL, day TEXT NOT NULL, PRIMARY KEY(wordId, day), FOREIGN KEY(wordId) REFERENCES words(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
            old.execSQL("INSERT INTO words VALUES ('apple','apple','/ˈæpəl/','n.','苹果','An apple.','一个苹果。')")
            old.execSQL("INSERT INTO progress VALUES ('apple',100,200,2,1,1)")
            old.execSQL("INSERT INTO daily_views VALUES ('apple','2026-10-15')")
            old.version = 1
        }
        val db = WordDatabase.open(context, name)
        try {
            val p = db.dao().all().single().progress!!
            assertEquals(2L, p.viewCount); assertTrue(p.favorite); assertTrue(p.mastered)
            assertEquals(0L, p.favoriteUpdatedAt); assertEquals(0L, p.masteredUpdatedAt)
            assertEquals(1, db.dao().days().size)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
