package com.edgepulse.monitor.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.edgepulse.monitor.security.SecureSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(entities = [GardenMapEntity::class, PlantNodeEntity::class, PlantObservationEntity::class,
    MonitoringSessionEntity::class, InventoryEventEntity::class, MonitoringGapEntity::class,
    ArchiveIndexEntity::class], version = 1, exportSchema = true)
abstract class EdgePulseDatabase : RoomDatabase() {
    abstract fun monitoringDao(): MonitoringDao
    private var driverKey: ByteArray? = null

    override fun close() {
        try { super.close() }
        finally { driverKey?.fill(0); driverKey = null }
    }

    companion object {
        suspend fun open(context: Context, settings: SecureSettingsStore): EdgePulseDatabase = withContext(Dispatchers.IO) {
            System.loadLibrary("sqlcipher")
            val passphrase = settings.databasePassphrase()
            // SQLCipher retains this buffer to open later pooled connections. Clear it only on close.
            val driverPassphrase = passphrase.copyOf()
            var db: EdgePulseDatabase? = null
            try {
                db = Room.databaseBuilder(context.applicationContext, EdgePulseDatabase::class.java, "edgepulse.db")
                    .openHelperFactory(SupportOpenHelperFactory(driverPassphrase, null, true))
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
