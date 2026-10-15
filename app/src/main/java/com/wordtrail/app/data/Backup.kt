package com.wordtrail.app.data

import com.google.gson.*
import java.time.LocalDate

data class BackupEntry(val word: Word, val progress: Progress?, val days: List<String>)
data class BackupFile(val format: String = "wordtrail-backup", val version: Int = 1,
    val exportedAt: Long, val entries: List<BackupEntry>)

object BackupCodec {
    const val MAX_BYTES = 10 * 1024 * 1024
    private val gson = GsonBuilder().setPrettyPrinting().create()
    fun encode(backup: BackupFile): String {
        require(backup.entries.size <= 10000) { "原版 JSON 学习备份最多支持 10000 条，当前词条过多，未写入文件" }
        return gson.toJson(backup).also {
            require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "学习备份超过 10 MB，未写入文件" }
        }
    }
    fun decode(text: String): BackupFile {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "备份文件超过 10 MB" }
        val root = JsonParser.parseString(text).asJsonObject
        require(root.get("format")?.asString == "wordtrail-backup") { "不是拾词备份文件" }
        require(root.get("version")?.asInt == 1) { "备份版本暂不支持，请升级应用" }
        val backup = gson.fromJson(root, BackupFile::class.java)
        require(backup.exportedAt > 0)
        require(backup.entries.size <= 10000) { "备份最多支持 10000 个词条" }
        val ids = HashSet<String>()
        backup.entries.forEach { entry ->
            validateWord(entry.word)
            require(ids.add(entry.word.id)) { "备份存在重复词条" }
            entry.progress?.let { p ->
                require(p.wordId == entry.word.id)
                require(p.viewCount in 0..1_000_000_000L)
                require(p.firstViewed >= 0 && p.lastViewed >= p.firstViewed)
                require(p.favoriteUpdatedAt >= 0 && p.masteredUpdatedAt >= 0)
                require(if (p.viewCount == 0L) p.firstViewed == 0L && p.lastViewed == 0L else p.firstViewed > 0)
            }
            require(entry.days.size <= 50000)
            entry.days.forEach { require(LocalDate.parse(it).toString() == it) }
            require(entry.days.isEmpty() || (entry.progress?.viewCount ?: 0) > 0L)
        }
        return backup
    }
    fun validateWord(w: Word) {
        require(w.id.matches(Regex("[a-z][a-z0-9_-]{0,79}")))
        require(w.english.isNotBlank() && w.meaning.isNotBlank()) { "英文单词和中文释义必填" }
        listOf(w.english, w.phonetic, w.partOfSpeech, w.meaning, w.example, w.translation).forEach {
            require(it.length <= 2000) { "词条字段过长" }
        }
    }
}
