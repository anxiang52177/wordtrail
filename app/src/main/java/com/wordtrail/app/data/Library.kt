package com.wordtrail.app.data

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID

fun wordKey(text: String): String = text.trim().lowercase(Locale.ROOT)
data class ImportResult(val bookId: String, val added: Int, val updated: Int, val skipped: Int)

private fun checkedName(name: String): String = name.trim().also {
    require(it.isNotEmpty() && it.length <= 80) { "词库名称须为 1～80 个字符" }
}

suspend fun Repository.createBook(name: String, description: String): String = withContext(Dispatchers.IO) {
    val book = WordBook(UUID.randomUUID().toString(), checkedName(name), description.trim().take(1000))
    db.withTransaction {
        db.dao().book(book)
        if (db.dao().activeBookId() == null) db.dao().library(LibraryState(activeBookId = book.id))
    }
    book.id
}

suspend fun Repository.renameBook(id: String, name: String) = db.withTransaction {
    val book = db.dao().book(id) ?: error("词库已不存在")
    db.dao().book(book.copy(name = checkedName(name)))
}

suspend fun Repository.selectBook(id: String) = db.withTransaction {
    require(db.dao().book(id)?.deleted == false) { "此词库已删除，请先恢复" }
    db.dao().library(LibraryState(activeBookId = id))
}

// 软删除、恢复与当前词库切换同一事务提交；隐藏内置词库只修改数据库。
suspend fun Repository.trashBooks(ids: Set<String>, restore: Boolean = false, permanent: Boolean = false) = withContext(Dispatchers.IO) {
    db.withTransaction {
        val dao = db.dao()
        ids.forEach { id ->
            val book = dao.book(id) ?: error("词库已不存在")
            if (permanent) {
                require(book.deleted && !book.builtIn) { "仅能永久删除回收站中的自建词库" }
                dao.deleteBook(id)
            } else dao.book(book.copy(deleted = !restore))
        }
        if (permanent) {
            // 共享词仍有成员关系，不会清理它的词条、查看记录或状态。
            dao.deleteOrphanDays(); dao.deleteOrphanProgress(); dao.deleteOrphanWords()
        }
        val active = dao.activeBookId()
        if (active == null || dao.book(active)?.deleted != false) {
            dao.library(LibraryState(activeBookId = dao.books().firstOrNull { !it.deleted }?.id))
        }
    }
}

suspend fun Repository.previewImport(parsed: ParsedImport, target: String?): ImportPreview = withContext(Dispatchers.IO) {
    val existing = if (target == null) emptySet() else {
        val book = db.dao().book(target)
        require(book != null && !book.deleted && !book.builtIn) { "只能追加到可用的自建词库" }
        db.dao().bookWords(target).map { wordKey(it.english) }.toSet()
    }
    val seen = existing.toMutableSet()
    ImportPreview(parsed, parsed.words.count { !seen.add(wordKey(it.english)) }, target)
}

suspend fun Repository.commitImport(preview: ImportPreview, name: String, description: String,
    update: Boolean, source: String): ImportResult = withContext(Dispatchers.IO) {
    require(preview.parsed.words.isNotEmpty()) { "没有有效词条可导入" }
    db.withTransaction {
        val dao = db.dao()
        val book = if (preview.target == null) WordBook(UUID.randomUUID().toString(), checkedName(name), description.trim().take(1000), source)
        else dao.book(preview.target) ?: error("目标词库已不存在")
        require(!book.deleted && !book.builtIn) { "只能追加到可用的自建词库" }
        dao.book(book)
        val existing = dao.bookWords(book.id).associateBy { wordKey(it.english) }.toMutableMap()
        val changed = linkedMapOf<String, Word>()
        val members = mutableListOf<BookWord>()
        var added = 0; var updated = 0; var skipped = 0
        preview.parsed.words.forEach { incoming ->
            val key = wordKey(incoming.english)
            val old = existing[key]
            if (old == null) {
                val word = incoming.copy(id = "custom-${UUID.randomUUID()}")
                existing[key] = word; changed[word.id] = word; members += BookWord(book.id, word.id); added++
            } else if (update) {
                // 沿用词条 ID，不触碰 progress；空的可选字段保留原内容。
                val word = old.copy(english = incoming.english, meaning = incoming.meaning,
                    phonetic = incoming.phonetic.ifBlank { old.phonetic }, partOfSpeech = incoming.partOfSpeech.ifBlank { old.partOfSpeech },
                    example = incoming.example.ifBlank { old.example }, translation = incoming.translation.ifBlank { old.translation })
                existing[key] = word; changed[word.id] = word; updated++
            } else skipped++
        }
        // 分批提交给 Room，全部批次仍处于同一事务中。
        changed.values.chunked(500).forEach { dao.words(it) }
        members.chunked(500).forEach { dao.members(it) }
        if (dao.activeBookId() == null) dao.library(LibraryState(activeBookId = book.id))
        ImportResult(book.id, added, updated, skipped)
    }
}
