package com.edgepulse.monitor.data.local

import androidx.room.withTransaction
import com.edgepulse.core.audit.SignedAuditRecord
import kotlinx.serialization.json.Json

/** One consistent snapshot across events, gaps and outgoing-key rotation records. */
object AuditHistory {
    suspend fun read(database: EdgePulseDatabase): List<SignedAuditRecord> = database.withTransaction {
        val dao = database.monitoringDao()
        (dao.auditRecordsJson().map { Json.decodeFromString<SignedAuditRecord>(it) } +
            dao.rotationRecordsJson().flatMap { Json.decodeFromString<List<SignedAuditRecord>>(it) })
            .sortedBy { it.payload.sequence }
    }
}
