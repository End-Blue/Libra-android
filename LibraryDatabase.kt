package com.libra.reader

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

// ══════════════════════════════════════════════════════════════════════════════
// ENTIDADES
// ══════════════════════════════════════════════════════════════════════════════

@Entity(tableName = "library_items")
data class LibraryItem(
    @PrimaryKey val id: String,
    val title: String,
    val author: String,
    val format: String,
    val localPath: String,
    val mediaType: String,
    val progress: Int = 0,
    val shelfOrientation: String = "VERTICAL",
    val sortOrder: Int = 0,
    val addedAt: Long = System.currentTimeMillis(),
    val coverPath: String? = null,
    @ColumnInfo(defaultValue = "0") val lastPosition: Long = 0,
    @ColumnInfo(defaultValue = "0") val durationMs: Long = 0,
    @ColumnInfo(defaultValue = "-1") val shelfX: Float = -1f,
    @ColumnInfo(defaultValue = "-1") val shelfY: Float = -1f,
    // Modo de paso de página: "HORIZONTAL" (swipe) o "VERTICAL" (scroll)
    @ColumnInfo(defaultValue = "VERTICAL") val readingMode: String = "VERTICAL",
    // Separador personalizado: null = ninguno, "COVER" = portada del libro
    @ColumnInfo(defaultValue = "") val bookmarkStyle: String = "",
    // Favorito
    @ColumnInfo(defaultValue = "0") val isFavorite: Int = 0
)

/** Subrayado o nota sobre un fragmento de texto */
@Entity(tableName = "highlights")
data class Highlight(
    @PrimaryKey val id: String,
    val bookId: String,
    val bookTitle: String,
    // Capítulo (índice en spine para EPUB) o página (PDF/CBZ) o párrafo (TXT/FB2)
    val chapterIndex: Int,
    val chapterTitle: String,   // nombre visible del capítulo o "Página N"
    val selectedText: String,
    val note: String = "",       // nota del lector (puede estar vacía)
    val colorArgb: Long = 0xFFFFF176L,
    val createdAt: Long = System.currentTimeMillis()
)

// ══════════════════════════════════════════════════════════════════════════════
// DAOs
// ══════════════════════════════════════════════════════════════════════════════

@Dao
interface LibraryDao {

    @Query("SELECT * FROM library_items ORDER BY sortOrder ASC, addedAt DESC")
    fun observeAll(): Flow<List<LibraryItem>>

    @Query("SELECT * FROM library_items ORDER BY addedAt ASC")
    suspend fun getAll(): List<LibraryItem>

    @Query("SELECT * FROM library_items WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): LibraryItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: LibraryItem)

    @Update
    suspend fun update(item: LibraryItem)

    @Query("DELETE FROM library_items WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM library_items")
    suspend fun count(): Int

    @Query("UPDATE library_items SET lastPosition = :position, progress = :progress WHERE id = :id")
    suspend fun updateProgress(id: String, position: Long, progress: Int)

    @Query("UPDATE library_items SET lastPosition = :position, progress = :progress, durationMs = :durationMs WHERE id = :id")
    suspend fun updatePlayback(id: String, position: Long, progress: Int, durationMs: Long)

    @Query("UPDATE library_items SET shelfX = :x, shelfY = :y WHERE id = :id")
    suspend fun updateShelfPosition(id: String, x: Float, y: Float)

    @Query("UPDATE library_items SET shelfX = -1, shelfY = -1")
    suspend fun resetShelfPositions()

    @Query("UPDATE library_items SET readingMode = :mode WHERE id = :id")
    suspend fun updateReadingMode(id: String, mode: String)

    @Query("UPDATE library_items SET bookmarkStyle = :style WHERE id = :id")
    suspend fun updateBookmarkStyle(id: String, style: String)

    @Query("UPDATE library_items SET isFavorite = :fav WHERE id = :id")
    suspend fun setFavorite(id: String, fav: Int)
}

@Dao
interface HighlightDao {

    @Query("SELECT * FROM highlights ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Highlight>>

    @Query("SELECT * FROM highlights WHERE bookId = :bookId ORDER BY chapterIndex ASC, createdAt ASC")
    fun observeByBook(bookId: String): Flow<List<Highlight>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(h: Highlight)

    @Update
    suspend fun update(h: Highlight)

    @Query("DELETE FROM highlights WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM highlights WHERE bookId = :bookId")
    suspend fun deleteByBook(bookId: String)
}

// ══════════════════════════════════════════════════════════════════════════════
// MIGRACIONES
// ══════════════════════════════════════════════════════════════════════════════

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE library_items ADD COLUMN coverPath TEXT")
        db.execSQL("ALTER TABLE library_items ADD COLUMN lastPosition INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE library_items ADD COLUMN durationMs INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE library_items ADD COLUMN shelfX REAL NOT NULL DEFAULT -1")
        db.execSQL("ALTER TABLE library_items ADD COLUMN shelfY REAL NOT NULL DEFAULT -1")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Nuevas columnas en library_items
        db.execSQL("ALTER TABLE library_items ADD COLUMN readingMode TEXT NOT NULL DEFAULT 'VERTICAL'")
        db.execSQL("ALTER TABLE library_items ADD COLUMN bookmarkStyle TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE library_items ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
        // Tabla de subrayados
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS highlights (
                id TEXT NOT NULL PRIMARY KEY,
                bookId TEXT NOT NULL,
                bookTitle TEXT NOT NULL,
                chapterIndex INTEGER NOT NULL,
                chapterTitle TEXT NOT NULL,
                selectedText TEXT NOT NULL,
                note TEXT NOT NULL DEFAULT '',
                colorArgb INTEGER NOT NULL DEFAULT ${0xFFFFF176L},
                createdAt INTEGER NOT NULL
            )
        """.trimIndent())
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// BASE DE DATOS
// ══════════════════════════════════════════════════════════════════════════════

@Database(
    entities = [LibraryItem::class, Highlight::class],
    version = 3,
    exportSchema = false
)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
    abstract fun highlightDao(): HighlightDao

    companion object {
        @Volatile private var INSTANCE: LibraryDatabase? = null

        fun get(context: Context): LibraryDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    LibraryDatabase::class.java,
                    "libra-library.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
