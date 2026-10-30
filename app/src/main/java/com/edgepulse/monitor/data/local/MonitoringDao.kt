package com.edgepulse.monitor.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MonitoringDao {
    @Insert suspend fun insertSession(value: MonitoringSessionEntity)
    @Insert suspend fun insertEvent(value: InventoryEventEntity): Long
    @Insert suspend fun insertGap(value: MonitoringGapEntity): Long
    @Insert suspend fun insertArchive(value: ArchiveIndexEntity)

    @Query("SELECT * FROM monitoring_sessions WHERE mode = 'CEMENT' ORDER BY started_wall_ms DESC")
    fun observeSessions(): Flow<List<MonitoringSessionEntity>>
    @Query("SELECT * FROM monitoring_sessions WHERE session_id = :id")
    suspend fun session(id: String): MonitoringSessionEntity?
    @Query("UPDATE monitoring_sessions SET last_heartbeat_elapsed_ms = :elapsed, last_heartbeat_wall_ms = :wall WHERE session_id = :id AND is_session_active = 1")
    suspend fun heartbeat(id: String, elapsed: Long, wall: Long)
    @Query("UPDATE monitoring_sessions SET is_session_active = :active, ended_wall_ms = :endedAt WHERE session_id = :id")
    suspend fun setActive(id: String, active: Boolean, endedAt: Long?)
    @Query("UPDATE monitoring_sessions SET active_burst_duty_cycle_pct = :duty, background_freeze_events = :freezes WHERE session_id = :id")
    suspend fun updateDutyMetrics(id: String, duty: Double, freezes: Long)
    @Query("UPDATE monitoring_sessions SET total_out_count = :outCount, total_in_count = :inCount WHERE session_id = :id")
    suspend fun updateCounts(id: String, outCount: Long, inCount: Long)
    @Query("SELECT COUNT(*) > 0 FROM monitoring_gaps WHERE session_id = :id AND reason = 'OS_KILL_DETECTED'")
    suspend fun hasProcessGap(id: String): Boolean
    @Query("SELECT audit_record_json FROM (SELECT audit_sequence, audit_record_json FROM inventory_events UNION ALL SELECT audit_sequence, audit_record_json FROM monitoring_gaps) ORDER BY audit_sequence")
    suspend fun auditRecordsJson(): List<String>
    @Query("SELECT audit_record_json FROM (SELECT audit_sequence, audit_record_json FROM inventory_events UNION ALL SELECT audit_sequence, audit_record_json FROM monitoring_gaps) ORDER BY audit_sequence DESC LIMIT 1")
    suspend fun latestEventOrGapJson(): String?
    @Query("SELECT authorized_rotation_records_json FROM monitoring_sessions WHERE authorized_rotation_records_json != '[]'")
    suspend fun rotationRecordsJson(): List<String>
    @Query("UPDATE monitoring_sessions SET authorized_rotation_records_json = :records WHERE session_id = :id")
    suspend fun saveRotationRecords(id: String, records: String)
    @Query("SELECT COALESCE(SUM(byte_size), 0) FROM archive_index WHERE is_purged = 0")
    suspend fun archiveBytes(): Long
    @Query("SELECT COALESCE(MAX(fifo_order), 0) + 1 FROM archive_index")
    suspend fun nextArchiveOrder(): Long
    @Query("SELECT * FROM archive_index WHERE is_purged = 0 ORDER BY fifo_order LIMIT 1")
    suspend fun oldestArchive(): ArchiveIndexEntity?
    @Query("SELECT * FROM archive_index WHERE is_purged = 0 ORDER BY fifo_order")
    suspend fun activeArchives(): List<ArchiveIndexEntity>
    @Query("UPDATE archive_index SET is_purged = 1 WHERE enc_webp_path = :path")
    suspend fun markArchivePurged(path: String)
    @Query("UPDATE inventory_events SET webp_purged = 1 WHERE enc_webp_path = :path")
    suspend fun markEventPurged(path: String)
}
