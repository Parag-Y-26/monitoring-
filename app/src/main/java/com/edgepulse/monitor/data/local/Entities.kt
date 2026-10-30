package com.edgepulse.monitor.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "monitoring_sessions")
data class MonitoringSessionEntity(
    @PrimaryKey val session_id: String,
    val mode: String = "CEMENT",
    val is_session_active: Boolean = false,
    val last_heartbeat_elapsed_ms: Long = 0,
    val last_heartbeat_wall_ms: Long = 0,
    val boot_id: String,
    val active_burst_duty_cycle_pct: Double = 0.0,
    val background_freeze_events: Long = 0,
    val gate_ax: Double? = null,
    val gate_ay: Double? = null,
    val gate_bx: Double? = null,
    val gate_by: Double? = null,
    val out_normal_x: Double? = null,
    val out_normal_y: Double? = null,
    val total_out_count: Long = 0,
    val total_in_count: Long = 0,
    val pubkey_spki_b64: String,
    val attestation_cert_pem: String,
    val attestation_nonce_mode: String,
    val key_continuity_status: String,
    val last_checkpoint_sig: String? = null,
    val authorized_rotation_records_json: String = "[]",
    val started_elapsed_ms: Long,
    val started_wall_ms: Long,
    val ended_wall_ms: Long? = null,
    val cloud_audit_report: String? = null,
)

@Entity(tableName = "inventory_events", foreignKeys = [ForeignKey(
    entity = MonitoringSessionEntity::class, parentColumns = ["session_id"], childColumns = ["session_id"],
)], indices = [Index("session_id"), Index(value = ["audit_sequence"], unique = true)])
data class InventoryEventEntity(
    @PrimaryKey(autoGenerate = true) val event_id: Long = 0,
    val session_id: String,
    val track_id: Long,
    val direction: String,
    val bag_weight: Int,
    val running_out_total: Long,
    val enc_webp_path: String,
    val webp_purged: Boolean = false,
    val webp_sha256: String,
    val monotonic_elapsed_ms: Long,
    val wall_clock_ms: Long,
    val prev_chain_hash: String,
    val chain_hash: String,
    val ecdsa_sig_b64: String,
    val audit_sequence: Long,
    val audit_record_json: String,
)

@Entity(tableName = "monitoring_gaps", foreignKeys = [ForeignKey(
    entity = MonitoringSessionEntity::class, parentColumns = ["session_id"], childColumns = ["session_id"],
)], indices = [Index("session_id"), Index(value = ["audit_sequence"], unique = true)])
data class MonitoringGapEntity(
    @PrimaryKey(autoGenerate = true) val gap_id: Long = 0,
    val session_id: String,
    val gap_start_elapsed_ms: Long,
    val gap_end_elapsed_ms: Long,
    val gap_start_wall_ms: Long,
    val reason: String,
    val prev_chain_hash: String,
    val chain_hash: String,
    val ecdsa_sig_b64: String,
    val audit_sequence: Long,
    val audit_record_json: String,
)

@Entity(tableName = "archive_index", indices = [Index("fifo_order"), Index(value = ["is_purged", "fifo_order"])])
data class ArchiveIndexEntity(
    @PrimaryKey val enc_webp_path: String,
    val byte_size: Long,
    val created_elapsed_ms: Long,
    val fifo_order: Long,
    val source_table: String,
    val source_row_id: Long,
    val is_purged: Boolean = false,
)
