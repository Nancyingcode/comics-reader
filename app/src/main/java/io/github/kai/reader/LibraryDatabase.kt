package io.github.kai.reader

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

data class BookSummary(
    val id: String,
    val name: String,
    val sourceUri: String,
    val isArchive: Boolean,
    val cover: ReaderPage,
    val chapterCount: Int,
    val pageCount: Int,
    val indexedAt: Long,
    val lastChapterName: String?,
    val lastPageNumber: Int?,
    val lastPageFraction: Double?,
    val lastReadAt: Long?,
    val isRemote: Boolean = false,
    val unindexedChapterCount: Int = 0,
)

data class ChapterSummary(
    val id: String,
    val name: String,
    val pageCount: Int,
    val lastPageNumber: Int?,
    val lastPageFraction: Double?,
    val isRemote: Boolean = false,
    val isOffline: Boolean = false,
) {
    val isIndexed: Boolean get() = !isRemote || pageCount > 0
}

data class BookDetails(val book: BookSummary, val chapters: List<ChapterSummary>)

internal data class LibraryImage(
    val id: String,
    val name: String,
    val width: Int,
    val height: Int,
    val isBaselineJpeg: Boolean,
    val remoteVersion: String? = null,
) {
    constructor(page: ReaderPage) : this(
        page.id, page.name, page.width, page.height, page.isBaselineJpeg, page.remoteVersion,
    )

    fun readerPage() = ReaderPage(id, name, width, height, isBaselineJpeg, remoteVersion = remoteVersion)
}

@Entity(tableName = "books", indices = [Index(value = ["sourceKey", "isArchive"], unique = true)])
internal data class LibraryBook(
    @PrimaryKey val id: String,
    val sourceKey: String,
    val sourceUri: String,
    val isArchive: Boolean,
    val name: String,
    @Embedded(prefix = "cover_") val cover: LibraryImage,
    val chapterCount: Int,
    val pageCount: Int,
    val indexedAt: Long,
    val lastChapterKey: String?,
    val lastReadAt: Long?,
)

@Entity(
    tableName = "chapters",
    primaryKeys = ["bookId", "id"],
    foreignKeys = [ForeignKey(
        entity = LibraryBook::class,
        parentColumns = ["id"],
        childColumns = ["bookId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["bookId", "sourceKey"], unique = true), Index(value = ["bookId", "position"])],
)
internal data class LibraryChapter(
    val bookId: String,
    val id: String,
    val sourceKey: String,
    val name: String,
    val position: Int,
    val pageCount: Int,
    val sourceVersion: String? = null,
)

@Entity(
    tableName = "pages",
    primaryKeys = ["bookId", "chapterId", "id"],
    foreignKeys = [ForeignKey(
        entity = LibraryChapter::class,
        parentColumns = ["bookId", "id"],
        childColumns = ["bookId", "chapterId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [
        Index(value = ["bookId", "chapterId", "sourceKey"], unique = true),
        Index(value = ["bookId", "chapterId", "position"]),
    ],
)
internal data class LibraryPage(
    val bookId: String,
    val chapterId: String,
    val sourceKey: String,
    val position: Int,
    @Embedded val image: LibraryImage,
)

@Entity(
    tableName = "reading_progress",
    primaryKeys = ["bookId", "chapterKey"],
    foreignKeys = [ForeignKey(
        entity = LibraryBook::class,
        parentColumns = ["id"],
        childColumns = ["bookId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["chapterKey", "updatedAt"])],
)
internal data class LibraryProgress(
    val bookId: String,
    // A chapter can disappear from a refreshed index and still be restored if it returns.
    val chapterKey: String,
    val pageKey: String,
    val pagePosition: Int?,
    val pageFraction: Double,
    val updatedAt: Long,
)

internal data class BookSummaryRow(
    @Embedded val book: LibraryBook,
    val lastChapterName: String?,
    val lastPageNumber: Int?,
    val lastPageFraction: Double?,
    val unindexedChapterCount: Int,
) {
    fun summary(): BookSummary {
        val isRemote = RemoteLocation.fromId(book.sourceUri) != null
        return BookSummary(
            book.id, book.name, book.sourceUri, book.isArchive, book.cover.readerPage(), book.chapterCount,
            book.pageCount, book.indexedAt, lastChapterName, lastPageNumber, lastPageFraction, book.lastReadAt,
            isRemote, if (isRemote) unindexedChapterCount else 0,
        )
    }
}

internal data class ChapterSummaryRow(
    val id: String,
    val name: String,
    val pageCount: Int,
    val lastPageNumber: Int?,
    val lastPageFraction: Double?,
) {
    fun summary(isRemote: Boolean, isOffline: Boolean) =
        ChapterSummary(id, name, pageCount, lastPageNumber, lastPageFraction, isRemote, isOffline)
}

private const val PAGE_PROGRESS_COLUMNS = """
    CASE WHEN r.pageKey IS NULL OR c.pageCount = 0 THEN NULL
         WHEN p.position IS NOT NULL THEN p.position + 1
         ELSE MIN(COALESCE(r.pagePosition, 0) + 1, c.pageCount) END AS lastPageNumber,
    CASE WHEN p.position IS NOT NULL THEN r.pageFraction ELSE NULL END AS lastPageFraction
"""

private const val PROGRESS_JOINS = """
    LEFT JOIN reading_progress r ON r.bookId = c.bookId AND r.chapterKey = c.sourceKey
    LEFT JOIN pages p ON p.bookId = c.bookId AND p.chapterId = c.id AND p.sourceKey = r.pageKey
"""

private const val BOOK_SUMMARIES = """
    SELECT b.*, c.name AS lastChapterName, $PAGE_PROGRESS_COLUMNS,
        (SELECT COUNT(*) FROM chapters pending WHERE pending.bookId = b.id AND pending.pageCount = 0)
            AS unindexedChapterCount
    FROM books b
    LEFT JOIN chapters c ON c.bookId = b.id AND c.sourceKey = b.lastChapterKey
    $PROGRESS_JOINS
"""

@Dao
internal interface LibraryDao {
    @Query("$BOOK_SUMMARIES ORDER BY b.lastReadAt DESC, b.indexedAt DESC, b.name")
    fun observeBooks(): Flow<List<BookSummaryRow>>

    @Query("$BOOK_SUMMARIES WHERE b.id = :bookId")
    suspend fun bookSummary(bookId: String): BookSummaryRow?

    @Query("SELECT * FROM books WHERE id = :bookId")
    suspend fun book(bookId: String): LibraryBook?

    @Query("SELECT * FROM books WHERE sourceKey = :sourceKey AND isArchive = :isArchive")
    suspend fun bookForSource(sourceKey: String, isArchive: Boolean): LibraryBook?

    @Query("""
        SELECT c.id, c.name, c.pageCount, $PAGE_PROGRESS_COLUMNS
        FROM chapters c $PROGRESS_JOINS
        WHERE c.bookId = :bookId ORDER BY c.position
    """)
    suspend fun chapterSummaries(bookId: String): List<ChapterSummaryRow>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY position")
    suspend fun chapters(bookId: String): List<LibraryChapter>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId AND id = :chapterId")
    suspend fun chapter(bookId: String, chapterId: String): LibraryChapter?

    @Query("""
        SELECT c.* FROM chapters c
        LEFT JOIN reading_progress r ON r.bookId = c.bookId AND r.chapterKey = c.sourceKey
        WHERE c.bookId = :bookId AND (c.pageCount > 0 OR :canOpenUnindexed)
        ORDER BY c.sourceKey IS :lastChapterKey DESC, r.updatedAt DESC, c.position
        LIMIT 1
    """)
    suspend fun chapterToResume(bookId: String, lastChapterKey: String?, canOpenUnindexed: Boolean): LibraryChapter?

    @Query("SELECT * FROM pages WHERE bookId = :bookId AND chapterId = :chapterId ORDER BY position")
    suspend fun pages(bookId: String, chapterId: String): List<LibraryPage>

    @Query("SELECT EXISTS(SELECT 1 FROM pages WHERE bookId = :bookId AND id = :pageId)")
    suspend fun hasPage(bookId: String, pageId: String): Boolean

    @Query("""
        SELECT p.* FROM chapters c JOIN pages p ON p.bookId = c.bookId AND p.chapterId = c.id
        WHERE c.bookId = :bookId ORDER BY c.position, p.position LIMIT 1
    """)
    suspend fun firstPage(bookId: String): LibraryPage?

    @Query("SELECT * FROM reading_progress WHERE bookId = :bookId")
    suspend fun bookProgress(bookId: String): List<LibraryProgress>

    @Query("SELECT * FROM reading_progress WHERE bookId = :bookId AND chapterKey = :chapterKey")
    suspend fun chapterProgress(bookId: String, chapterKey: String): LibraryProgress?

    @Query("SELECT * FROM reading_progress WHERE chapterKey = :chapterKey ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latestProgressForSource(chapterKey: String): LibraryProgress?

    @Query("SELECT MAX(lastReadAt) FROM books")
    suspend fun latestReadAt(): Long?

    @Upsert
    suspend fun putBook(book: LibraryBook)

    @Query("UPDATE books SET lastChapterKey = :chapterKey, lastReadAt = :readAt WHERE id = :bookId")
    suspend fun markRead(bookId: String, chapterKey: String, readAt: Long)

    @Insert
    suspend fun putChapters(chapters: List<LibraryChapter>)

    @Query("UPDATE chapters SET pageCount = :pageCount, sourceVersion = :sourceVersion WHERE bookId = :bookId AND id = :chapterId")
    suspend fun updateChapterIndex(bookId: String, chapterId: String, pageCount: Int, sourceVersion: String?)

    @Query("DELETE FROM chapters WHERE bookId = :bookId")
    suspend fun deleteChapters(bookId: String)

    @Insert
    suspend fun putPages(pages: List<LibraryPage>)

    @Query("DELETE FROM pages WHERE bookId = :bookId AND chapterId = :chapterId")
    suspend fun deletePages(bookId: String, chapterId: String)

    @Upsert
    suspend fun putProgress(progress: List<LibraryProgress>)

    @Upsert
    suspend fun putProgress(progress: LibraryProgress)
}

@Database(
    entities = [LibraryBook::class, LibraryChapter::class, LibraryPage::class, LibraryProgress::class],
    version = 2,
    exportSchema = true,
)
internal abstract class LibraryDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao
}

internal val LIBRARY_MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE books ADD COLUMN cover_remoteVersion TEXT")
        db.execSQL("ALTER TABLE chapters ADD COLUMN sourceVersion TEXT")
        db.execSQL("ALTER TABLE pages ADD COLUMN remoteVersion TEXT")
    }
}
