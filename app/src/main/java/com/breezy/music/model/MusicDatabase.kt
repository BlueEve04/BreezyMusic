package com.breezy.music.model

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 歌曲实体（Room 持久化，支持 5w+ 级别）
 */
@Entity(
    tableName = "songs",
    indices = [
        Index("folderPath"),
        Index("artist"),
        Index("album")
    ]
)
data class SongEntity(
    @PrimaryKey val path: String,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val folderPath: String,
    /** 由 [com.breezy.music.model.BpmAnalyzer] 离线分析的 BPM；0 表示尚未分析 */
    @ColumnInfo(defaultValue = "0") val bpm: Float = 0f
)

/**
 * 已管理的文件夹
 */
@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val path: String
)

// ═══════════════════════════════════════════
// DAO
// ═══════════════════════════════════════════

@Dao
interface MusicDao {

    // ── 歌曲 CRUD ──

    @Query("SELECT * FROM songs GROUP BY path")
    suspend fun getAllSongs(): List<SongEntity>

    @Query("SELECT * FROM songs WHERE folderPath = :folderPath")
    suspend fun getSongsByFolder(folderPath: String): List<SongEntity>

    @Query("SELECT DISTINCT artist FROM songs ORDER BY artist")
    suspend fun getAllArtists(): List<String>

    @Query("SELECT * FROM songs WHERE artist = :artist ORDER BY album, title")
    suspend fun getSongsByArtist(artist: String): List<SongEntity>

    @Query("SELECT DISTINCT album FROM songs ORDER BY album")
    suspend fun getAllAlbums(): List<String>

    @Query("SELECT * FROM songs WHERE album = :album ORDER BY title")
    suspend fun getSongsByAlbum(album: String): List<SongEntity>

    @Query("SELECT COUNT(*) FROM songs")
    suspend fun getSongCount(): Int

    // ── BPM 分析（运动音乐） ──

    /** 尚未分析 BPM 的歌曲 */
    @Query("SELECT * FROM songs WHERE bpm = 0")
    suspend fun getSongsWithoutBpm(): List<SongEntity>

    /** 已分析 BPM 的歌曲 */
    @Query("SELECT * FROM songs WHERE bpm > 0")
    suspend fun getSongsWithBpm(): List<SongEntity>

    /** 已分析数量 */
    @Query("SELECT COUNT(*) FROM songs WHERE bpm > 0")
    suspend fun getAnalyzedCount(): Int

    @Query("UPDATE songs SET bpm = :bpm WHERE path = :path")
    suspend fun updateBpm(path: String, bpm: Float)

    /** 指定文件夹下已分析过的 BPM（重扫时用于保留缓存） */
    @Query("SELECT path, bpm FROM songs WHERE folderPath = :folderPath AND bpm != 0")
    suspend fun getBpmForFolder(folderPath: String): List<BpmRow>

    @Query("SELECT path, bpm FROM songs WHERE bpm > 0")
    suspend fun getBpmMap(): List<BpmRow>

    @Query("UPDATE songs SET bpm = 0")
    suspend fun clearAllBpm()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(songs: List<SongEntity>)

    @Query("DELETE FROM songs WHERE folderPath = :folderPath")
    suspend fun deleteByFolder(folderPath: String)

    @Query("DELETE FROM songs")
    suspend fun deleteAllSongs()

    // ── 文件夹 CRUD ──

    @Query("SELECT * FROM folders")
    suspend fun getAllFolders(): List<FolderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: FolderEntity)

    @Delete
    suspend fun deleteFolder(folder: FolderEntity)

    // ── 高效批量操作（事务内） ──

    @Transaction
    suspend fun replaceFolderSongs(folderPath: String, newSongs: List<SongEntity>) {
        deleteByFolder(folderPath)
        insertAll(newSongs)
    }
}

/**
 * BPM 查询投影
 */
data class BpmRow(val path: String, val bpm: Float)

// ═══════════════════════════════════════════
// Database
// ═══════════════════════════════════════════

@Database(
    entities = [SongEntity::class, FolderEntity::class],
    version = 2,
    exportSchema = false
)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun musicDao(): MusicDao

    companion object {
        @Volatile private var INSTANCE: MusicDatabase? = null

        /** v1 → v2：为 songs 增加 bpm 列（运动音乐 BPM 缓存）；保留用户曲库数据 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN bpm REAL NOT NULL DEFAULT 0")
            }
        }

        fun getInstance(context: android.content.Context): MusicDatabase {
            return INSTANCE ?: synchronized(this) {
                android.util.Log.i("MusicDatabase", "Creating DB instance (first time)")
                Room.databaseBuilder(
                    context.applicationContext,
                    MusicDatabase::class.java,
                    "breezy_music.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
