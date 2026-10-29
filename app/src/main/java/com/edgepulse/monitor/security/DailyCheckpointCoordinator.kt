package com.edgepulse.monitor.security

import android.content.Context
import android.net.Uri
import com.edgepulse.core.audit.AuditBundle
import com.edgepulse.monitor.data.local.AuditHistory
import com.edgepulse.monitor.data.local.EdgePulseDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shares one exporter between the UI and worker. No endpoint or network client is involved. */
@Singleton
class DailyCheckpointCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SecureSettingsStore,
) {
    private val mutex = Mutex()

    suspend fun exportNow(): String = mutex.withLock {
        val current = settings.dataStore.data.first()
        val status = when {
            current.checkpointTreeUri.isEmpty() -> "FOLDER_REQUIRED"
            current.trustedAuditPublicKey.isEmpty() -> "IDENTITY_REQUIRED"
            else -> try {
                val tree = Uri.parse(current.checkpointTreeUri)
                LocalSafFolder.requireLocalTree(tree)
                val database = EdgePulseDatabase.open(context, settings)
                val records = try { AuditHistory.read(database) } finally { database.close() }
                if (records.isEmpty()) "NO_HISTORY"
                else {
                    DailyCheckpointExporter(context.contentResolver).export(tree, AuditBundle(records = records), current.trustedAuditPublicKey)
                    "EXPORTED"
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: SecurityException) { "FOLDER_ACCESS_LOST" }
            catch (_: IllegalArgumentException) { "INVALID_FOLDER_OR_HISTORY" }
            catch (_: IllegalStateException) { "INVALID_AUDIT_CHAIN_OR_STORAGE" }
            catch (_: Exception) { "EXPORT_FAILED" }
        }
        settings.dataStore.updateData { it.copy(lastCheckpointStatus = status, lastCheckpointWallMs = System.currentTimeMillis()) }
        status
    }
}
