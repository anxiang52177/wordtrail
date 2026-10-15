package com.wordtrail.app.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "words")
data class Word(@PrimaryKey val id: String, val english: String, val phonetic: String,
    val partOfSpeech: String, val meaning: String, val example: String, val translation: String)

@Entity(tableName = "progress", foreignKeys = [ForeignKey(entity = Word::class,
    parentColumns = ["id"], childColumns = ["wordId"], onDelete = ForeignKey.NO_ACTION)])
data class Progress(@PrimaryKey val wordId: String, val firstViewed: Long = 0,
    val lastViewed: Long = 0, val viewCount: Long = 0, val favorite: Boolean = false,
    val mastered: Boolean = false,
    @ColumnInfo(defaultValue = "0") val favoriteUpdatedAt: Long = 0,
    @ColumnInfo(defaultValue = "0") val masteredUpdatedAt: Long = 0)

@Entity(tableName = "daily_views", primaryKeys = ["wordId", "day"], foreignKeys = [ForeignKey(
    entity = Word::class, parentColumns = ["id"], childColumns = ["wordId"], onDelete = ForeignKey.NO_ACTION)])
data class DailyView(val wordId: String, val day: String)

data class WordState(@Embedded val word: Word, @Relation(parentColumn = "id", entityColumn = "wordId") val progress: Progress?)

@Entity(tableName = "wordbooks")
data class WordBook(@PrimaryKey val id: String, val name: String, val description: String = "",
    val source: String = "自建", val builtIn: Boolean = false, val deleted: Boolean = false)

@Entity(tableName = "book_words", primaryKeys = ["bookId", "wordId"],
    indices = [Index("wordId")], foreignKeys = [
        ForeignKey(entity = WordBook::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Word::class, parentColumns = ["id"], childColumns = ["wordId"], onDelete = ForeignKey.NO_ACTION)])
data class BookWord(val bookId: String, val wordId: String)

@Entity(tableName = "library_state")
data class LibraryState(@PrimaryKey val slot: Int = 0, val activeBookId: String?)

data class BookSummary(@Embedded val book: WordBook, val wordCount: Int, val active: Boolean)

@Dao
interface WordDao {
    @Transaction @Query("SELECT words.* FROM words INNER JOIN book_words ON words.id = book_words.wordId INNER JOIN wordbooks ON wordbooks.id = book_words.bookId WHERE wordbooks.deleted = 0 AND wordbooks.id = (SELECT activeBookId FROM library_state WHERE slot = 0) ORDER BY english")
    fun observeActiveWords(): Flow<List<WordState>>
    @Transaction @Query("SELECT words.* FROM words INNER JOIN book_words ON words.id = book_words.wordId INNER JOIN wordbooks ON wordbooks.id = book_words.bookId WHERE wordbooks.deleted = 0 AND wordbooks.id = (SELECT activeBookId FROM library_state WHERE slot = 0) ORDER BY english")
    suspend fun activeWords(): List<WordState>
    @Query("SELECT wordbooks.*, (SELECT COUNT(*) FROM book_words WHERE bookId = wordbooks.id) AS wordCount, CASE WHEN id = (SELECT activeBookId FROM library_state WHERE slot = 0) THEN 1 ELSE 0 END AS active FROM wordbooks ORDER BY builtIn DESC, name COLLATE NOCASE, id")
    fun observeBooks(): Flow<List<BookSummary>>
    @Query("SELECT * FROM wordbooks ORDER BY builtIn DESC, name, id") suspend fun books(): List<WordBook>
    @Query("SELECT * FROM wordbooks WHERE id = :id") suspend fun book(id: String): WordBook?
    @Query("SELECT activeBookId FROM library_state WHERE slot = 0") suspend fun activeBookId(): String?
    @Upsert suspend fun book(book: WordBook)
    @Upsert suspend fun library(state: LibraryState)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun members(members: List<BookWord>)
    @Query("SELECT words.* FROM words INNER JOIN book_words ON words.id = book_words.wordId WHERE bookId = :id") suspend fun bookWords(id: String): List<Word>
    @Query("DELETE FROM wordbooks WHERE id = :id") suspend fun deleteBook(id: String)
    @Query("DELETE FROM daily_views WHERE wordId NOT IN (SELECT wordId FROM book_words)") suspend fun deleteOrphanDays()
    @Query("DELETE FROM progress WHERE wordId NOT IN (SELECT wordId FROM book_words)") suspend fun deleteOrphanProgress()
    @Query("DELETE FROM words WHERE id NOT IN (SELECT wordId FROM book_words)") suspend fun deleteOrphanWords()
    @Transaction @Query("SELECT * FROM words ORDER BY english") fun observeWords(): Flow<List<WordState>>
    @Transaction @Query("SELECT * FROM words ORDER BY english") suspend fun all(): List<WordState>
    @Query("SELECT * FROM daily_views") fun observeDays(): Flow<List<DailyView>>
    @Query("SELECT * FROM daily_views") suspend fun days(): List<DailyView>
    @Query("SELECT * FROM progress WHERE wordId = :id") suspend fun progress(id: String): Progress?
    // Upsert 不删除旧词条，避免 REPLACE 的删除行为破坏外键关系。
    @Upsert suspend fun words(words: List<Word>)
    @Upsert suspend fun save(progress: Progress)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun day(day: DailyView)
}

@Database(entities = [Word::class, Progress::class, DailyView::class, WordBook::class, BookWord::class, LibraryState::class], version = 3, exportSchema = true)
abstract class WordDatabase : RoomDatabase() {
    abstract fun dao(): WordDao
    companion object {
        const val BUILTIN_ID = "builtin"
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS wordbooks (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, description TEXT NOT NULL, source TEXT NOT NULL, builtIn INTEGER NOT NULL, deleted INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS book_words (bookId TEXT NOT NULL, wordId TEXT NOT NULL, PRIMARY KEY(bookId, wordId), FOREIGN KEY(bookId) REFERENCES wordbooks(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(wordId) REFERENCES words(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_book_words_wordId ON book_words(wordId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS library_state (slot INTEGER NOT NULL PRIMARY KEY, activeBookId TEXT)")
                db.execSQL("INSERT INTO wordbooks VALUES ('builtin','内置常用词','升级前的全部词条与记录','内置 / 旧版词库',1,0)")
                db.execSQL("INSERT INTO book_words SELECT 'builtin', id FROM words")
                db.execSQL("INSERT INTO library_state VALUES (0,'builtin')")
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE progress ADD COLUMN favoriteUpdatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE progress ADD COLUMN masteredUpdatedAt INTEGER NOT NULL DEFAULT 0")
            }
        }
        fun open(context: Context, name: String = "wordtrail.db") =
            Room.databaseBuilder(context, WordDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
    }
}
