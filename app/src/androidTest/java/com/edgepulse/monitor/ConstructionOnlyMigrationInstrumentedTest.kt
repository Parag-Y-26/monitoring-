package com.edgepulse.monitor

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.edgepulse.monitor.data.local.EdgePulseDatabase
import com.edgepulse.monitor.security.SecureSettingsStore
import com.edgepulse.monitor.security.TinkKeyManager
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConstructionOnlyMigrationInstrumentedTest {
    private class ScratchContext(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir() = File(root, "no_backup").apply { mkdirs() }
        override fun getDatabasePath(name: String) = File(root, name)
    }

    @Test fun v1UpgradeRetainsConstructionAuditAndRemovesRetiredStorage() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val root = File(base.cacheDir, "migration-${UUID.randomUUID()}").apply { mkdirs() }
        val context = ScratchContext(base, root)
        val alias = "edgepulse.test.migration.${UUID.randomUUID()}"
        val settings = SecureSettingsStore(context, TinkKeyManager(context, alias))
        val schema = instrumentation.context.assets.open("com.edgepulse.monitor.data.local.EdgePulseDatabase/1.json")
            .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject.getValue("database").jsonObject }
        val entities = schema.getValue("entities").jsonArray.map { it.jsonObject }
        System.loadLibrary("sqlcipher")
        val key = settings.databasePassphrase()
        val helper = SupportOpenHelperFactory(key, null, true).create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("edgepulse.db").callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    for (entity in entities) {
                        val name = entity.getValue("tableName").jsonPrimitive.content
                        db.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", name))
                        for (index in entity["indices"]?.jsonArray.orEmpty()) {
                            db.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", name))
                        }
                    }
                    schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Fixture must stay v1")
            }).build())
        var database: EdgePulseDatabase? = null
        try {
            val legacy = helper.writableDatabase
            fun insert(table: String, values: Map<String, Any>) {
                val entity = entities.single { it.getValue("tableName").jsonPrimitive.content == table }
                val row = ContentValues()
                for (item in entity.getValue("fields").jsonArray) {
                    val field = item.jsonObject
                    val name = field.getValue("columnName").jsonPrimitive.content
                    val value = values[name] ?: if (field["notNull"]?.jsonPrimitive?.boolean != true) null
                        else if (field.getValue("affinity").jsonPrimitive.content == "TEXT") "" else 0L
                    when (value) {
                        null -> row.putNull(name)
                        is String -> row.put(name, value)
                        is Long -> row.put(name, value)
                        else -> error("Unsupported fixture value")
                    }
                }
                legacy.insert(table, 2, row)
            }
            insert("monitoring_sessions", mapOf("session_id" to "retained", "mode" to "CEMENT", "total_out_count" to 2L))
            insert("inventory_events", mapOf("event_id" to 1L, "session_id" to "retained", "audit_sequence" to 1L,
                "audit_record_json" to "unchanged-signed-event", "enc_webp_path" to "event.webp.enc"))
            insert("monitoring_gaps", mapOf("gap_id" to 1L, "session_id" to "retained", "audit_sequence" to 2L,
                "audit_record_json" to "unchanged-signed-gap"))
            insert("archive_index", mapOf("enc_webp_path" to "event.webp.enc", "source_table" to "EVENT", "source_row_id" to 1L, "byte_size" to 96L))
            insert("garden_maps", mapOf("map_id" to "retired"))
            insert("plant_nodes", mapOf("node_id" to "retired", "map_id" to "retired"))
            insert("plant_observations", mapOf("obs_id" to 1L, "node_id" to "retired", "session_id" to "retained"))
            insert("archive_index", mapOf("enc_webp_path" to "retired.webp.enc", "source_table" to "OBS", "source_row_id" to 1L, "byte_size" to 48L))
            helper.close(); key.fill(0)
            database = EdgePulseDatabase.open(context, settings)
            val reader = database.openHelper.readableDatabase
            val tables = reader.query("SELECT name FROM sqlite_master WHERE type='table'").use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            assertFalse(tables.any { it in setOf("garden_maps", "plant_nodes", "plant_observations") })
            assertEquals(2L, database.monitoringDao().session("retained")?.total_out_count)
            assertEquals(listOf("unchanged-signed-event", "unchanged-signed-gap"), database.monitoringDao().auditRecordsJson())
            assertEquals(96L, database.monitoringDao().archiveBytes())
            reader.query("PRAGMA table_info(monitoring_sessions)").use { cursor ->
                while (cursor.moveToNext()) assertFalse(cursor.getString(cursor.getColumnIndexOrThrow("name")).startsWith("align_"))
            }
            reader.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally {
            helper.close(); key.fill(0); database?.close(); settings.close()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
            check(root.canonicalFile.parentFile == base.cacheDir.canonicalFile)
            root.deleteRecursively()
        }
    }
}
