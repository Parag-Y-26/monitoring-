package com.edgepulse.monitor.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.edgepulse.monitor.security.SecureSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(entities = [MonitoringSessionEntity::class, InventoryEventEntity::class, MonitoringGapEntity::class,
    ArchiveIndexEntity::class], version = 2, exportSchema = true)
abstract class EdgePulseDatabase : RoomDatabase() {
    abstract fun monitoringDao(): MonitoringDao
    private var driverKey: ByteArray? = null

    override fun close() {
        try { super.close() }
        finally { driverKey?.fill(0); driverKey = null }
    }

    companion object {
        /** Retains all signed history/session identities; removes only the retired feature's storage. */
        private val CONSTRUCTION_ONLY_MIGRATION = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS plant_observations")
                db.execSQL("DROP TABLE IF EXISTS plant_nodes")
                db.execSQL("DROP TABLE IF EXISTS garden_maps")
                db.execSQL("DELETE FROM archive_index WHERE source_table = 'OBS'")
                for (column in listOf("align_method", "align_rot_rad", "align_tx_m", "align_tz_m", "align_sigma_m")) {
                    db.execSQL("ALTER TABLE monitoring_sessions DROP COLUMN $column")
                }
            }
        }

        suspend fun open(context: Context, settings: SecureSettingsStore): EdgePulseDatabase = withContext(Dispatchers.IO) {
            System.loadLibrary("sqlcipher")
            val passphrase = settings.databasePassphrase()
            // SQLCipher retains this buffer to open later pooled connections. Clear it only on close.
            val driverPassphrase = passphrase.copyOf()
            var db: EdgePulseDatabase? = null
            try {
                db = Room.databaseBuilder(context.applicationContext, EdgePulseDatabase::class.java, "edgepulse.db")
                    .openHelperFactory(SupportOpenHelperFactory(driverPassphrase, null, true))
                    .addMigrations(CONSTRUCTION_ONLY_MIGRATION)
                    .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                    .build()
                db.driverKey = driverPassphrase
                // Room opens lazily: force the encrypted connection before clearing its opening key.
                db.openHelper.writableDatabase
                db
            } catch (failure: Exception) {
                db?.close()
                throw failure
            } finally {
                passphrase.fill(0)
                if (db == null) driverPassphrase.fill(0)
            }
        }
    }
}
