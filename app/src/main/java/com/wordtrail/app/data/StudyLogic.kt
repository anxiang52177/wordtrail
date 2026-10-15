package com.wordtrail.app.data

import kotlin.random.Random

enum class StudyMode { NEW, REVIEW }
object StudyLogic {
    fun pick(words: List<WordState>, mode: StudyMode, current: String?, random: Random = Random.Default): WordState? {
        val pool = words.filter { if (mode == StudyMode.NEW) (it.progress?.viewCount ?: 0) == 0L else (it.progress?.viewCount ?: 0) > 0L }
        if (pool.isEmpty()) return null
        val candidates = pool.filter { it.word.id != current }.ifEmpty { pool }
        if (mode == StudyMode.NEW) return candidates.random(random)
        // 先复习未掌握词，再从最久未看的少量词条中随机抽取。
        val priority = candidates.filter { it.progress?.mastered != true }.ifEmpty { candidates }
        val oldest = priority.sortedBy { it.progress?.lastViewed ?: 0 }.take(5)
        return oldest.random(random)
    }
    fun filter(words: List<WordState>, query: String, filter: String, favoritesOnly: Boolean): List<WordState> {
        val q = query.trim()
        return words.filter {
            val p = it.progress
            (if (favoritesOnly) p?.favorite == true else (p?.viewCount ?: 0) > 0L) &&
                (q.isEmpty() || it.word.english.contains(q, true) || it.word.meaning.contains(q, true)) &&
                when (filter) { "favorite" -> p?.favorite == true; "unmastered" -> p?.mastered != true; "mastered" -> p?.mastered == true; else -> true }
        }.sortedByDescending { it.progress?.lastViewed ?: 0 }
    }
    fun merge(old: Progress?, incoming: Progress): Progress {
        if (old == null) return incoming
        return incoming.copy(
            firstViewed = listOf(old.firstViewed, incoming.firstViewed).filter { it > 0 }.minOrNull() ?: 0,
            lastViewed = maxOf(old.lastViewed, incoming.lastViewed), viewCount = maxOf(old.viewCount, incoming.viewCount),
            favorite = if (old.favoriteUpdatedAt >= incoming.favoriteUpdatedAt) old.favorite else incoming.favorite,
            mastered = if (old.masteredUpdatedAt >= incoming.masteredUpdatedAt) old.mastered else incoming.mastered,
            favoriteUpdatedAt = maxOf(old.favoriteUpdatedAt, incoming.favoriteUpdatedAt),
            masteredUpdatedAt = maxOf(old.masteredUpdatedAt, incoming.masteredUpdatedAt))
    }
}
