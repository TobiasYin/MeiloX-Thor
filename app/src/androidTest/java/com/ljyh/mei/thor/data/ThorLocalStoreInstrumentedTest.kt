package com.ljyh.mei.thor.data

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.di.AppDatabase
import com.ljyh.mei.thor.data.local.ThorFavoriteEntity
import com.ljyh.mei.thor.data.local.ThorPlaylistEntity
import com.ljyh.mei.thor.data.local.ThorPlaylistTrackEntity
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThorLocalStoreInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun localCollectionsPersistAndRemainSourceScoped() = runBlocking {
        val name = "thor-store-test-${UUID.randomUUID()}"
        var database = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            val dao = database.thorLocalDao()
            val local = ThorFavoriteEntity("device", "song", 7, true, "Local", "", null, "", "[]", 1)
            dao.putFavorite(local)
            dao.putFavorite(local.copy(scope = "account:42", local = false, title = "Remote"))
            dao.create(ThorPlaylistEntity("p", "Playlist", 1, 1), listOf(
                ThorPlaylistTrackEntity("p", "local:7", 0, "{}")))
            assertEquals(1, dao.append("p", listOf(
                ThorPlaylistTrackEntity("p", "local:7", 0, "{}"),
                ThorPlaylistTrackEntity("p", "netease:7", 0, "{}"))))
            database.close()
            database = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            val reopened = database.thorLocalDao()
            assertEquals(2, reopened.observeFavorites().first().size)
            assertEquals(listOf("local:7", "netease:7"), reopened.tracks("p").map { it.songKey })
            reopened.removeFavorite("device", "song", 7, true)
            assertEquals("Remote", reopened.observeFavorites().first().single().title)
            reopened.delete("p")
            assertTrue(reopened.tracks("p").isEmpty())
        } finally { database.close(); context.deleteDatabase(name) }
    }

    @Test fun failedCreateAndAddRollsBackTheNewPlaylist() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = database.thorLocalDao()
            val failed = runCatching {
                dao.create(ThorPlaylistEntity("p", "Draft", 1, 1), listOf(
                    ThorPlaylistTrackEntity("missing-parent", "local:1", 0, "{}")))
            }
            assertTrue(failed.isFailure)
            assertNull(dao.playlist("p"))
        } finally { database.close() }
    }

    @Test fun migrationAddsThorTablesWithoutLosingExistingData() {
        val name = "thor-migration-test-${UUID.randomUUID()}"
        fun helper(version: Int) = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(
                object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE legacy_probe (id INTEGER NOT NULL PRIMARY KEY, title TEXT NOT NULL)")
                        db.execSQL("INSERT INTO legacy_probe VALUES (1, 'keep')")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        assertEquals(18, oldVersion)
                        AppDatabase.MIGRATION_18_19.migrate(db)
                    }
                }).build())
        try {
            helper(18).also { it.writableDatabase; it.close() }
            val migrated = helper(19)
            try {
                val db = migrated.writableDatabase
                db.query("SELECT title FROM legacy_probe").use { assertTrue(it.moveToFirst()); assertEquals("keep", it.getString(0)) }
                listOf("thor_favorite", "thor_local_playlist", "thor_local_playlist_track").forEach { table ->
                    db.query("SELECT COUNT(*) FROM $table").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
                }
                db.execSQL("INSERT INTO thor_local_playlist VALUES ('p', 'keep-local', 1, 1)")
                AppDatabase.MIGRATION_18_19.migrate(db)
                db.query("SELECT name FROM thor_local_playlist WHERE id='p'").use {
                    assertTrue(it.moveToFirst()); assertEquals("keep-local", it.getString(0))
                }
            } finally { migrated.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun migrationPassesRoomValidationWithTheUnchangedLegacyTables() = runBlocking {
        val name = "thor-room-migration-${UUID.randomUUID()}"
        // The repository has no exported historical v18 schema. Derive a fixture
        // from unchanged legacy entities, remove only the three new tables and
        // lower user_version; Room itself validates the migrated complete schema.
        val seed = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            seed.openHelper.writableDatabase.execSQL("CREATE TABLE legacy_probe (value TEXT NOT NULL)")
            seed.openHelper.writableDatabase.execSQL("INSERT INTO legacy_probe VALUES ('keep')")
        } finally { seed.close() }
        try {
            android.database.sqlite.SQLiteDatabase.openDatabase(
                context.getDatabasePath(name).absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
            ).use { db ->
                db.execSQL("DROP TABLE thor_local_playlist_track")
                db.execSQL("DROP TABLE thor_local_playlist")
                db.execSQL("DROP TABLE thor_favorite")
                db.version = 18
            }
            val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(AppDatabase.MIGRATION_18_19).build()
            try {
                assertTrue(migrated.thorLocalDao().observePlaylists().first().isEmpty())
                migrated.openHelper.writableDatabase.query("SELECT value FROM legacy_probe").use {
                    assertTrue(it.moveToFirst()); assertEquals("keep", it.getString(0))
                }
                migrated.thorLocalDao().create(ThorPlaylistEntity("p", "After migration", 1, 1), emptyList())
                assertEquals("After migration", migrated.thorLocalDao().playlist("p")?.name)
            } finally { migrated.close() }
        } finally { context.deleteDatabase(name) }
    }
}
