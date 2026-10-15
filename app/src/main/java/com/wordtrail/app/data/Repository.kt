package com.wordtrail.app.data

import android.content.Context
import androidx.room.withTransaction
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

class Repository(private val context: Context, val db: WordDatabase) {
    val words = db.dao().observeActiveWords()
    val books = db.dao().observeBooks()
    val days = db.dao().observeDays()
    suspend fun seed() = withContext(Dispatchers.IO) {
        val words = context.assets.open("words.json").bufferedReader().use {
            Gson().fromJson(it, Array<Word>::class.java).toList()
        }
        require(words.size >= 300 && words.map { it.id }.distinct().size == words.size)
        words.forEach(BackupCodec::validateWord)
        db.withTransaction {
            val dao = db.dao()
            if (dao.book(WordDatabase.BUILTIN_ID) == null) {
                dao.book(WordBook(WordDatabase.BUILTIN_ID, "内置常用词", "320 个常用词", "内置", builtIn = true))
                dao.library(LibraryState(activeBookId = WordDatabase.BUILTIN_ID))
            }
            dao.words(words)
            dao.members(words.map { BookWord(WordDatabase.BUILTIN_ID, it.id) })
        }
    }
    suspend fun all() = db.dao().activeWords()
    suspend fun view(id: String, now: Long = System.currentTimeMillis(), day: String = LocalDate.now().toString()) {
        db.withTransaction {
            val p = db.dao().progress(id) ?: Progress(id)
            db.dao().save(p.copy(firstViewed = if (p.firstViewed == 0L) now else minOf(now, p.firstViewed),
                lastViewed = maxOf(now, p.lastViewed), viewCount = p.viewCount + 1))
            db.dao().day(DailyView(id, day))
        }
    }
    suspend fun favorite(id: String) = db.withTransaction {
        val p = db.dao().progress(id) ?: Progress(id)
        db.dao().save(p.copy(favorite = !p.favorite, favoriteUpdatedAt = maxOf(System.currentTimeMillis(), p.favoriteUpdatedAt + 1)))
    }
    suspend fun mastered(id: String) = db.withTransaction {
        val p = db.dao().progress(id) ?: Progress(id)
        db.dao().save(p.copy(mastered = !p.mastered, masteredUpdatedAt = maxOf(System.currentTimeMillis(), p.masteredUpdatedAt + 1)))
    }
    suspend fun export(): String = withContext(Dispatchers.IO) {
        db.withTransaction {
            val days = db.dao().days().groupBy { it.wordId }
            BackupCodec.encode(BackupFile(exportedAt = System.currentTimeMillis(), entries = db.dao().all().map {
                BackupEntry(it.word, it.progress, days[it.word.id].orEmpty().map { d -> d.day })
            }))
        }
    }
    suspend fun import(text: String): Int = withContext(Dispatchers.IO) {
        // 全部校验通过后才开始事务，坏文件不会部分覆盖已有数据。
        val backup = BackupCodec.decode(text)
        db.withTransaction {
            val existing = db.dao().all().map { it.word.id }.toSet()
            val added = backup.entries.filter { it.word.id !in existing }.map { it.word }
            db.dao().words(added)
            if (added.isNotEmpty()) {
                val id = "backup-restored"
                val book = db.dao().book(id) ?: WordBook(id, "备份恢复词库", source = "JSON 备份")
                db.dao().book(book.copy(deleted = false))
                db.dao().members(added.map { BookWord(id, it.id) })
                if (db.dao().activeBookId() == null) db.dao().library(LibraryState(activeBookId = id))
            }
            backup.entries.forEach { e ->
                e.progress?.let { db.dao().save(StudyLogic.merge(db.dao().progress(e.word.id), it)) }
                e.days.forEach { db.dao().day(DailyView(e.word.id, it)) }
            }
        }
        backup.entries.count { (it.progress?.viewCount ?: 0) > 0 }
    }
}
